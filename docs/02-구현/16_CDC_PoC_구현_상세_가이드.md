# CDC PoC 구현 상세 가이드

> **목적**: 이 문서를 읽고 **처음부터 끝까지 혼자서 다시 구현**할 수 있도록 모든 것을 상세하게 설명합니다.
>
> **대상 독자**: CDC를 처음 접하는 개발자, 블로그 글 작성을 원하는 분

---

## 목차

1. [전체 개념 이해하기](#1-전체-개념-이해하기)
2. [프로젝트 구조](#2-프로젝트-구조)
3. [Part 1: ASIS (레거시) Oracle 구현](#3-part-1-asis-레거시-oracle-구현)
4. [Part 2: Kafka + Debezium 구현](#4-part-2-kafka--debezium-구현)
5. [Part 3: TOBE (신규) Oracle 구현](#5-part-3-tobe-신규-oracle-구현)
6. [Part 4: Sync Service (Java) 구현](#6-part-4-sync-service-java-구현)
7. [Part 5: 전체 실행 순서](#7-part-5-전체-실행-순서)
8. [Part 6: 테스트 및 검증](#8-part-6-테스트-및-검증)
9. [트러블슈팅](#9-트러블슈팅)

---

## 1. 전체 개념 이해하기

### 1.1 CDC(Change Data Capture)란?

**CDC**는 데이터베이스의 변경 사항을 실시간으로 감지하여 다른 시스템으로 전달하는 기술입니다.

```
┌──────────────────────────────────────────────────────────────────────┐
│                        CDC가 하는 일                                  │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   사용자가 INSERT/UPDATE/DELETE 실행                                  │
│           │                                                          │
│           ▼                                                          │
│   ┌─────────────┐                                                    │
│   │   Oracle    │ ← Debezium이 Redo Log를 읽어서 변경 감지           │
│   │  Database   │                                                    │
│   └─────────────┘                                                    │
│           │                                                          │
│           ▼                                                          │
│   ┌─────────────┐                                                    │
│   │   Kafka     │ ← 변경 내용을 JSON 메시지로 발행                    │
│   │   Topic     │                                                    │
│   └─────────────┘                                                    │
│           │                                                          │
│           ▼                                                          │
│   ┌─────────────┐                                                    │
│   │   다른 DB   │ ← Kafka 메시지를 받아서 동기화                      │
│   │   or 시스템 │                                                    │
│   └─────────────┘                                                    │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 1.2 이 프로젝트에서 CDC가 필요한 이유

**문제 상황**:
- 기존 시스템(ASIS)에서 새 시스템(TOBE)으로 전환 중
- 1차에서 일부만 전환 → **양쪽 시스템이 동시에 운영**됨
- ASIS에서 데이터를 수정하면 TOBE에도 반영되어야 하고, 그 반대도 마찬가지

**해결책**: CDC를 사용한 **양방향 실시간 동기화**

```
ASIS Oracle ←──── CDC ────→ TOBE Oracle
 (레거시)                      (신규)
```

### 1.3 Debezium이란?

**Debezium**은 오픈소스 CDC 플랫폼입니다.

- **지원 DB**: Oracle, MySQL, PostgreSQL, SQL Server 등
- **동작 원리**: 데이터베이스의 트랜잭션 로그(Oracle의 경우 Redo Log)를 읽어서 변경 감지
- **출력**: Kafka 토픽으로 JSON 메시지 발행

```
Debezium이 발행하는 메시지 예시:
{
  "op": "c",                    // c=INSERT, u=UPDATE, d=DELETE
  "before": null,               // 변경 전 데이터 (INSERT는 null)
  "after": {                    // 변경 후 데이터
    "BOOK_ID": 100,
    "BOOK_TITLE": "테스트도서",
    "AUTHOR": "테스트저자"
  },
  "source": {                   // 원본 정보
    "table": "BOOK_INFO"
  },
  "ts_ms": 1705123456789       // 변경 시간
}
```

---

## 2. 프로젝트 구조

```
cdc-sync-poc/
├── poc/
│   ├── docker-compose.yml          # 전체 인프라 정의
│   │
│   ├── asis-oracle/                # ASIS (레거시) Oracle
│   │   └── init/                   # 컨테이너 시작 시 자동 실행되는 SQL
│   │       ├── 00_setup_user.sql       # 사용자 생성
│   │       ├── 01_create_tables.sql    # 원본 테이블 생성
│   │       ├── 02_create_cdc_tables.sql # CDC 테이블 생성
│   │       ├── 03_create_mapping_tables.sql # 코드 매핑
│   │       ├── 04_create_procedures.sql # WORKER 프로시저
│   │       └── 05_insert_sample_data.sql # 샘플 데이터
│   │
│   ├── tobe-oracle/                # TOBE (신규) Oracle
│   │   └── init/                   # 동일한 구조
│   │
│   ├── kafka-connect/
│   │   └── libs/
│   │       └── ojdbc11.jar         # Oracle JDBC 드라이버
│   │
│   ├── sync-service-java/          # Java Spring Boot 앱
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/main/java/...
│   │
│   └── scripts/
│       ├── setup-debezium-oracle.sh    # Debezium 설정
│       └── register-connectors.sh      # 커넥터 등록
│
└── docs/                           # 문서
```

---

## 3. Part 1: ASIS (레거시) Oracle 구현

### 3.1 Docker 설정 (docker-compose.yml)

```yaml
# ASIS Oracle XE 컨테이너 정의
asis-oracle:
  image: gvenzl/oracle-xe:21-slim    # 경량 Oracle XE 21c 이미지
  container_name: asis-oracle
  environment:
    ORACLE_PASSWORD: oracle123        # SYS/SYSTEM 비밀번호
    APP_USER: asis_user               # 추가 사용자 (자동 생성)
    APP_USER_PASSWORD: asis123
  ports:
    - "15210:1521"                    # 호스트:컨테이너 포트 매핑
  volumes:
    - asis-oracle-data:/opt/oracle/oradata                    # 데이터 영속화
    - ./asis-oracle/init:/container-entrypoint-initdb.d       # 초기화 스크립트
  networks:
    - cdc-net                         # Docker 네트워크
```

**포인트**:
- `volumes`의 `./asis-oracle/init` 폴더에 있는 SQL 파일들이 **컨테이너 최초 시작 시 자동 실행**됩니다
- 파일명 순서대로 실행됨 (00 → 01 → 02 → ...)

### 3.2 사용자 생성 (00_setup_user.sql)

```sql
-- ============================================
-- ASIS Oracle 초기화 스크립트
-- 00. 사용자 생성 및 권한 부여
-- ============================================

-- PDB로 전환 (Oracle 21c는 CDB/PDB 구조)
ALTER SESSION SET CONTAINER = XEPDB1;

-- ASIS_USER가 없으면 생성
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM DBA_USERS WHERE username = 'ASIS_USER';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE USER ASIS_USER IDENTIFIED BY asis123
                          DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS';
    END IF;
END;
/

-- 권한 부여
GRANT CONNECT, RESOURCE TO ASIS_USER;
GRANT CREATE SESSION TO ASIS_USER;
GRANT CREATE TABLE TO ASIS_USER;
GRANT CREATE PROCEDURE TO ASIS_USER;
GRANT CREATE SEQUENCE TO ASIS_USER;
GRANT CREATE VIEW TO ASIS_USER;
GRANT CREATE JOB TO ASIS_USER;                 -- Scheduler Job 생성 권한
GRANT EXECUTE ON SYS.DBMS_CRYPTO TO ASIS_USER; -- 해시 함수 사용 권한
```

**왜 이렇게 했나?**
- `ALTER SESSION SET CONTAINER = XEPDB1`: Oracle 21c는 CDB(Container DB)와 PDB(Pluggable DB) 구조입니다. 실제 데이터는 PDB(XEPDB1)에 저장합니다.
- `CREATE JOB` 권한: WORKER 프로시저를 5초마다 자동 실행하기 위한 Oracle Scheduler Job에 필요합니다.
- `DBMS_CRYPTO` 권한: 무한루프 방지용 해시 생성에 필요합니다.

### 3.3 원본 테이블 생성 (01_create_tables.sql)

```sql
-- PDB로 전환
ALTER SESSION SET CONTAINER = XEPDB1;

-- ASIS_USER 스키마로 전환
ALTER SESSION SET CURRENT_SCHEMA = ASIS_USER;

-- 레거시 코드 마스터 (ASIS→TOBE 단방향 동기화)
CREATE TABLE LEGACY_CODE (
    CODE_ID     VARCHAR2(10) PRIMARY KEY,
    CODE_NAME   VARCHAR2(100),
    USE_YN      CHAR(1) DEFAULT 'Y',     -- ASIS 스타일: Y/N
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 도서 정보 (양방향 동기화)
CREATE TABLE BOOK_INFO (
    BOOK_ID     NUMBER PRIMARY KEY,
    BOOK_TITLE  VARCHAR2(200) NOT NULL,  -- ASIS 컬럼명
    AUTHOR      VARCHAR2(100),           -- ASIS 컬럼명
    CATEGORY    VARCHAR2(2),             -- ASIS 코드: '01', '02', '03'
    STATUS      CHAR(1) DEFAULT 'Y',     -- ASIS 스타일: Y/N
    REG_DATE    DATE DEFAULT SYSDATE,
    MOD_DATE    DATE
);

-- 회원 정보 (양방향 동기화)
CREATE TABLE MEMBER_INFO (
    MEMBER_ID   NUMBER PRIMARY KEY,
    MEMBER_NAME VARCHAR2(50) NOT NULL,
    EMAIL       VARCHAR2(100),           -- ASIS 컬럼명
    MEMBER_TYPE CHAR(1),                 -- ASIS 코드: 'A', 'B', 'C'
    STATUS      CHAR(1) DEFAULT 'Y',
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 신규 서비스 수신용 (TOBE→ASIS 단방향 동기화)
CREATE TABLE NEW_SERVICE_RECV (
    SERVICE_ID  NUMBER PRIMARY KEY,
    SERVICE_NM  VARCHAR2(100),
    SVC_TYPE    VARCHAR2(10),
    USE_YN      CHAR(1),
    REG_DATE    DATE
);
```

**ASIS 스키마 특징**:
- 컬럼명이 약어 사용 (`BOOK_TITLE`, `SERVICE_NM`)
- 상태값이 'Y'/'N' 문자
- 카테고리 코드가 '01', '02', '03' 숫자 코드

### 3.4 CDC 테이블 생성 (02_create_cdc_tables.sql)

**CDC 테이블이란?**
- TOBE에서 변경된 데이터를 받아서 저장하는 **중간 테이블**입니다
- WORKER 프로시저가 이 테이블을 읽어서 원본 테이블에 반영합니다

```sql
-- CDC_ASIS_BOOK: TOBE의 TB_BOOK 변경 데이터를 받는 테이블
CREATE TABLE CDC_ASIS_BOOK (
    CDC_SEQ         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- 자동증가 PK
    OPERATION       VARCHAR2(10) NOT NULL,  -- INSERT, UPDATE, DELETE

    -- TOBE 스키마 기준 컬럼들 (받는 데이터 형식)
    BOOK_ID         NUMBER,
    TITLE           VARCHAR2(500),          -- TOBE 컬럼명
    AUTHOR_NAME     VARCHAR2(200),          -- TOBE 컬럼명
    CATEGORY_CD     VARCHAR2(10),           -- TOBE 코드: 'LIT', 'SCI', 'HIS'
    IS_ACTIVE       NUMBER(1),              -- TOBE 스타일: 1/0
    CREATED_AT      TIMESTAMP,
    UPDATED_AT      TIMESTAMP,
    CREATED_BY      VARCHAR2(50),
    UPDATED_BY      VARCHAR2(50),

    -- 메타 정보
    SOURCE_TIMESTAMP TIMESTAMP,             -- 원본 변경 시간
    RECEIVED_AT     TIMESTAMP DEFAULT SYSTIMESTAMP,  -- 수신 시간
    PROCESSED_YN    CHAR(1) DEFAULT 'N',    -- 처리 여부 (N:미처리, Y:완료, E:에러, S:스킵)
    PROCESSED_AT    TIMESTAMP,              -- 처리 시간
    ERROR_MSG       VARCHAR2(1000),         -- 에러 메시지
    CHANGE_HASH     VARCHAR2(64)            -- 무한루프 방지용 해시
);

-- 인덱스 (미처리 데이터 조회 성능 향상)
CREATE INDEX IDX_CDC_ASIS_BOOK_PROC ON CDC_ASIS_BOOK(PROCESSED_YN);
```

**STAGING 테이블이란?**
- CDC 테이블의 데이터를 **ASIS 스키마로 변환**한 후 저장하는 테이블입니다
- 스키마 변환 ↔ 원본 반영을 분리하여 트랜잭션 관리를 용이하게 합니다

```sql
-- STAGING_ASIS_BOOK: 스키마 변환 후 대기 테이블
CREATE TABLE STAGING_ASIS_BOOK (
    STAGING_SEQ     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    CDC_SEQ         NUMBER,
    OPERATION       VARCHAR2(10) NOT NULL,

    -- ASIS 스키마 기준 컬럼들 (변환된 데이터)
    BOOK_ID         NUMBER,
    BOOK_TITLE      VARCHAR2(200),          -- TITLE → BOOK_TITLE
    AUTHOR          VARCHAR2(100),          -- AUTHOR_NAME → AUTHOR
    CATEGORY        VARCHAR2(2),            -- 'LIT' → '01'
    STATUS          CHAR(1),                -- 1 → 'Y'
    REG_DATE        DATE,
    MOD_DATE        DATE,

    -- 메타 정보
    STAGED_AT       TIMESTAMP DEFAULT SYSTIMESTAMP,
    PROCESSED_YN    CHAR(1) DEFAULT 'N',
    PROCESSED_AT    TIMESTAMP,
    ERROR_MSG       VARCHAR2(1000)
);
```

**데이터 흐름**:
```
TOBE 변경 → Kafka → Sync Service → CDC_ASIS_BOOK → STAGING_ASIS_BOOK → BOOK_INFO
                                    (TOBE 스키마)    (ASIS 스키마)       (원본)
```

### 3.5 코드 매핑 테이블 (03_create_mapping_tables.sql)

**왜 필요한가?**
- ASIS와 TOBE의 코드 체계가 다릅니다
- TOBE의 'LIT' → ASIS의 '01'로 변환해야 합니다

```sql
-- 코드 매핑 테이블
CREATE TABLE SYNC_CODE_MAPPING (
    MAP_GROUP       VARCHAR2(50),     -- 매핑 그룹 (CATEGORY_MAP, STATUS_MAP 등)
    SOURCE_SYSTEM   VARCHAR2(10),     -- 원본 시스템 (TOBE)
    SOURCE_VALUE    VARCHAR2(100),    -- 원본 코드값 (LIT)
    TARGET_SYSTEM   VARCHAR2(10),     -- 대상 시스템 (ASIS)
    TARGET_VALUE    VARCHAR2(100),    -- 대상 코드값 (01)
    DESCRIPTION     VARCHAR2(200),    -- 설명
    PRIMARY KEY (MAP_GROUP, SOURCE_SYSTEM, SOURCE_VALUE)
);

-- 카테고리 코드 매핑 (TOBE → ASIS)
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'TOBE', 'LIT', 'ASIS', '01', '문학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'TOBE', 'SCI', 'ASIS', '02', '과학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'TOBE', 'HIS', 'ASIS', '03', '역사');

-- 상태 코드 매핑 (TOBE → ASIS)
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'TOBE', '1', 'ASIS', 'Y', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'TOBE', '0', 'ASIS', 'N', '비활성');

-- 회원 유형 코드 매핑 (TOBE → ASIS)
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'TOBE', 'ADMIN', 'ASIS', 'A', '관리자');
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'TOBE', 'USER', 'ASIS', 'B', '일반');
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'TOBE', 'GUEST', 'ASIS', 'C', '게스트');

COMMIT;
```

**코드 변환 함수**:
```sql
-- 코드 변환 함수
CREATE OR REPLACE FUNCTION FN_CONVERT_CODE(
    p_map_group   VARCHAR2,    -- 매핑 그룹
    p_source_sys  VARCHAR2,    -- 원본 시스템
    p_source_val  VARCHAR2     -- 원본 코드값
) RETURN VARCHAR2
IS
    v_target_val VARCHAR2(100);
BEGIN
    SELECT TARGET_VALUE INTO v_target_val
    FROM SYNC_CODE_MAPPING
    WHERE MAP_GROUP = p_map_group
      AND SOURCE_SYSTEM = p_source_sys
      AND SOURCE_VALUE = p_source_val;

    RETURN v_target_val;
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        RETURN p_source_val;  -- 매핑 없으면 원본값 반환
END;
/
```

**사용 예시**:
```sql
SELECT FN_CONVERT_CODE('CATEGORY_MAP', 'TOBE', 'LIT') FROM DUAL;
-- 결과: '01'

SELECT FN_CONVERT_CODE('STATUS_MAP', 'TOBE', '1') FROM DUAL;
-- 결과: 'Y'
```

### 3.6 WORKER 프로시저 (04_create_procedures.sql)

**WORKER가 하는 일**:
1. CDC 테이블에서 미처리 데이터 조회
2. 무한루프 여부 체크
3. 스키마 변환 후 STAGING 테이블에 INSERT
4. STAGING에서 원본 테이블에 INSERT/UPDATE/DELETE
5. 해시 기록 (무한루프 방지)

#### 3.6.1 무한루프 방지 함수

```sql
-- 해시 생성 함수 (SHA-256)
CREATE OR REPLACE FUNCTION FN_GENERATE_HASH(
    p_table_name  VARCHAR2,
    p_pk_value    VARCHAR2,
    p_operation   VARCHAR2,
    p_data        VARCHAR2
) RETURN VARCHAR2
IS
BEGIN
    RETURN RAWTOHEX(
        DBMS_CRYPTO.HASH(
            UTL_RAW.CAST_TO_RAW(p_table_name || '|' || p_pk_value || '|' || p_operation || '|' || p_data),
            DBMS_CRYPTO.HASH_SH256
        )
    );
END;
/

-- 무한루프 체크 함수
CREATE OR REPLACE FUNCTION FN_IS_LOOP(
    p_hash VARCHAR2
) RETURN BOOLEAN
IS
    v_count NUMBER;
BEGIN
    -- 최근 5분 이내에 동일 해시가 처리된 적 있는지 확인
    SELECT COUNT(*) INTO v_count
    FROM CDC_PROCESSED_HASH
    WHERE HASH_VALUE = p_hash
      AND PROCESSED_AT > SYSTIMESTAMP - INTERVAL '5' MINUTE;

    RETURN v_count > 0;  -- 있으면 TRUE (무한루프)
END;
/

-- 해시 기록 프로시저
CREATE OR REPLACE PROCEDURE SP_RECORD_HASH(
    p_hash        VARCHAR2,
    p_table_name  VARCHAR2,
    p_pk_value    VARCHAR2
)
IS
BEGIN
    MERGE INTO CDC_PROCESSED_HASH t
    USING (SELECT p_hash AS hash_value FROM DUAL) s
    ON (t.HASH_VALUE = s.hash_value)
    WHEN MATCHED THEN
        UPDATE SET PROCESSED_AT = SYSTIMESTAMP
    WHEN NOT MATCHED THEN
        INSERT (HASH_VALUE, TABLE_NAME, PK_VALUE, PROCESSED_AT)
        VALUES (p_hash, p_table_name, p_pk_value, SYSTIMESTAMP);
    COMMIT;
END;
/
```

**무한루프가 발생하는 이유**:
```
ASIS에서 INSERT → TOBE 동기화 → TOBE에서 해당 행이 변경됨
→ Debezium이 감지 → ASIS로 다시 전송 → ASIS에서 또 변경
→ Debezium이 감지 → TOBE로 다시 전송 → ...
```

**해시 기반 방지 원리**:
1. 데이터를 반영할 때 해시를 생성하여 저장
2. CDC 데이터를 처리하기 전에 해시가 이미 있는지 확인
3. 있으면 **내가 보낸 데이터가 돌아온 것** → 스킵

#### 3.6.2 BOOK WORKER 프로시저

```sql
CREATE OR REPLACE PROCEDURE SP_WORKER_BOOK
IS
    v_hash VARCHAR2(64);
    v_err_msg VARCHAR2(500);
BEGIN
    -- ============================================
    -- 1단계: CDC → STAGING (스키마 변환)
    -- ============================================
    FOR rec IN (
        SELECT CDC_SEQ, OPERATION, BOOK_ID, TITLE, AUTHOR_NAME,
               CATEGORY_CD, IS_ACTIVE, SOURCE_TIMESTAMP, CHANGE_HASH
        FROM CDC_ASIS_BOOK
        WHERE PROCESSED_YN = 'N'           -- 미처리 데이터만
        ORDER BY CDC_SEQ                    -- 순서대로 처리
    ) LOOP
        BEGIN
            -- 무한루프 체크
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                -- 루프 감지 - 건너뛰기
                UPDATE CDC_ASIS_BOOK
                SET PROCESSED_YN = 'S',     -- S = Skipped
                    PROCESSED_AT = SYSTIMESTAMP,
                    ERROR_MSG = 'LOOP_BLOCKED'
                WHERE CDC_SEQ = rec.CDC_SEQ;

                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, CHANGE_HASH)
                VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'LOOP_BLOCKED', rec.CHANGE_HASH);
            ELSE
                -- STAGING에 삽입 (스키마 변환 적용)
                INSERT INTO STAGING_ASIS_BOOK (
                    CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE, AUTHOR,
                    CATEGORY, STATUS, REG_DATE, MOD_DATE
                ) VALUES (
                    rec.CDC_SEQ,
                    rec.OPERATION,
                    rec.BOOK_ID,
                    rec.TITLE,              -- TOBE.TITLE → ASIS.BOOK_TITLE
                    rec.AUTHOR_NAME,        -- TOBE.AUTHOR_NAME → ASIS.AUTHOR
                    FN_CONVERT_CODE('CATEGORY_MAP', 'TOBE', rec.CATEGORY_CD),  -- 'LIT' → '01'
                    FN_CONVERT_CODE('STATUS_MAP', 'TOBE', TO_CHAR(rec.IS_ACTIVE)),  -- 1 → 'Y'
                    SYSDATE,
                    SYSDATE
                );

                UPDATE CDC_ASIS_BOOK
                SET PROCESSED_YN = 'Y',
                    PROCESSED_AT = SYSTIMESTAMP
                WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;

            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);  -- 에러 메시지 저장
                UPDATE CDC_ASIS_BOOK
                SET PROCESSED_YN = 'E',
                    PROCESSED_AT = SYSTIMESTAMP,
                    ERROR_MSG = v_err_msg
                WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- ============================================
    -- 2단계: STAGING → 원본 테이블 반영
    -- ============================================
    FOR rec IN (
        SELECT STAGING_SEQ, CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE,
               AUTHOR, CATEGORY, STATUS, REG_DATE, MOD_DATE
        FROM STAGING_ASIS_BOOK
        WHERE PROCESSED_YN = 'N'
        ORDER BY STAGING_SEQ
    ) LOOP
        BEGIN
            -- OPERATION에 따라 INSERT/UPDATE/DELETE 실행
            CASE rec.OPERATION
                WHEN 'INSERT' THEN
                    INSERT INTO BOOK_INFO (BOOK_ID, BOOK_TITLE, AUTHOR, CATEGORY, STATUS, REG_DATE, MOD_DATE)
                    VALUES (rec.BOOK_ID, rec.BOOK_TITLE, rec.AUTHOR, rec.CATEGORY, rec.STATUS, rec.REG_DATE, rec.MOD_DATE);

                WHEN 'UPDATE' THEN
                    UPDATE BOOK_INFO
                    SET BOOK_TITLE = rec.BOOK_TITLE,
                        AUTHOR = rec.AUTHOR,
                        CATEGORY = rec.CATEGORY,
                        STATUS = rec.STATUS,
                        MOD_DATE = rec.MOD_DATE
                    WHERE BOOK_ID = rec.BOOK_ID;

                WHEN 'DELETE' THEN
                    DELETE FROM BOOK_INFO WHERE BOOK_ID = rec.BOOK_ID;
            END CASE;

            UPDATE STAGING_ASIS_BOOK
            SET PROCESSED_YN = 'Y',
                PROCESSED_AT = SYSTIMESTAMP
            WHERE STAGING_SEQ = rec.STAGING_SEQ;

            -- 해시 기록 (무한루프 방지)
            v_hash := FN_GENERATE_HASH('BOOK_INFO', TO_CHAR(rec.BOOK_ID), rec.OPERATION,
                                       rec.BOOK_TITLE || rec.AUTHOR || rec.CATEGORY || rec.STATUS);
            SP_RECORD_HASH(v_hash, 'BOOK_INFO', TO_CHAR(rec.BOOK_ID));

            INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS)
            VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'SUCCESS');

            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                -- INSERT 시 이미 존재하면 UPDATE로 대체
                UPDATE BOOK_INFO
                SET BOOK_TITLE = rec.BOOK_TITLE,
                    AUTHOR = rec.AUTHOR,
                    CATEGORY = rec.CATEGORY,
                    STATUS = rec.STATUS,
                    MOD_DATE = rec.MOD_DATE
                WHERE BOOK_ID = rec.BOOK_ID;

                UPDATE STAGING_ASIS_BOOK
                SET PROCESSED_YN = 'Y',
                    PROCESSED_AT = SYSTIMESTAMP
                WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE STAGING_ASIS_BOOK
                SET PROCESSED_YN = 'E',
                    PROCESSED_AT = SYSTIMESTAMP,
                    ERROR_MSG = v_err_msg
                WHERE STAGING_SEQ = rec.STAGING_SEQ;

                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, ERROR_MSG)
                VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'FAILED', v_err_msg);
                COMMIT;
        END;
    END LOOP;
END;
/
```

#### 3.6.3 전체 WORKER 실행 프로시저

```sql
-- 전체 WORKER 실행 프로시저
CREATE OR REPLACE PROCEDURE SP_RUN_ALL_WORKERS
IS
BEGIN
    SP_CLEANUP_HASH;       -- 오래된 해시 정리 (10분 경과)
    SP_WORKER_BOOK;        -- BOOK 동기화
    SP_WORKER_MEMBER;      -- MEMBER 동기화
    SP_WORKER_NEW_SERVICE; -- NEW_SERVICE 동기화
END;
/
```

#### 3.6.4 Oracle Scheduler Job (자동 실행)

```sql
-- 기존 Job이 있으면 삭제
BEGIN
    DBMS_SCHEDULER.DROP_JOB(job_name => 'JOB_CDC_WORKER', force => TRUE);
EXCEPTION
    WHEN OTHERS THEN NULL;  -- Job이 없으면 무시
END;
/

-- Scheduler Job 생성 (5초마다 실행)
BEGIN
    DBMS_SCHEDULER.CREATE_JOB(
        job_name        => 'JOB_CDC_WORKER',
        job_type        => 'PLSQL_BLOCK',
        job_action      => 'BEGIN SP_RUN_ALL_WORKERS; END;',
        start_date      => SYSTIMESTAMP,
        repeat_interval => 'FREQ=SECONDLY;INTERVAL=5',  -- 5초마다
        enabled         => TRUE,
        comments        => 'CDC WORKER 프로시저 주기적 실행 (5초)'
    );
END;
/
```

**Job 상태 확인 쿼리**:
```sql
SELECT job_name, state, last_start_date, next_run_date
FROM USER_SCHEDULER_JOBS
WHERE job_name = 'JOB_CDC_WORKER';
```

---

## 4. Part 2: Kafka + Debezium 구현

### 4.1 Kafka 인프라 (docker-compose.yml)

```yaml
# Zookeeper (Kafka 클러스터 관리)
zookeeper:
  image: confluentinc/cp-zookeeper:7.5.0
  container_name: zookeeper
  environment:
    ZOOKEEPER_CLIENT_PORT: 2181       # 클라이언트 연결 포트
    ZOOKEEPER_TICK_TIME: 2000         # 기본 시간 단위 (ms)
  networks:
    - cdc-net

# Kafka 브로커
kafka:
  image: confluentinc/cp-kafka:7.5.0
  container_name: kafka
  depends_on:
    - zookeeper
  ports:
    - "9092:9092"                     # 외부 접속용
    - "29092:29092"                   # 내부 통신용
  environment:
    KAFKA_BROKER_ID: 1
    KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
    KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
  networks:
    - cdc-net
```

**Kafka 환경변수 설명**:
- `KAFKA_ADVERTISED_LISTENERS`: 클라이언트가 접속할 주소
  - `PLAINTEXT://kafka:29092`: Docker 내부에서 접속할 때
  - `PLAINTEXT_HOST://localhost:9092`: 호스트에서 접속할 때

### 4.2 Kafka Connect (Debezium)

```yaml
# Kafka Connect (Debezium 호스팅)
kafka-connect:
  image: debezium/connect:2.4
  container_name: kafka-connect
  depends_on:
    - kafka
  ports:
    - "8083:8083"                     # REST API 포트
  volumes:
    - ./kafka-connect/libs/ojdbc11.jar:/kafka/libs/ojdbc11.jar  # Oracle JDBC 드라이버
  environment:
    BOOTSTRAP_SERVERS: kafka:29092
    GROUP_ID: cdc-connect-cluster
    CONFIG_STORAGE_TOPIC: cdc_connect_configs
    OFFSET_STORAGE_TOPIC: cdc_connect_offsets
    STATUS_STORAGE_TOPIC: cdc_connect_statuses
    CONFIG_STORAGE_REPLICATION_FACTOR: 1
    OFFSET_STORAGE_REPLICATION_FACTOR: 1
    STATUS_STORAGE_REPLICATION_FACTOR: 1
    KEY_CONVERTER: org.apache.kafka.connect.json.JsonConverter
    VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
    KEY_CONVERTER_SCHEMAS_ENABLE: "false"      # 스키마 없이 순수 JSON
    VALUE_CONVERTER_SCHEMAS_ENABLE: "false"
  networks:
    - cdc-net
```

**중요**: `ojdbc11.jar` 파일은 Oracle 사이트에서 다운로드해야 합니다.
- https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html

### 4.3 Debezium Oracle 설정 (setup-debezium-oracle.sh)

Debezium이 Oracle을 읽으려면 **사전 설정**이 필요합니다:

1. **ARCHIVELOG 모드 활성화**: Redo Log를 유지하도록 설정
2. **Supplemental Logging**: 변경된 모든 컬럼 정보를 로그에 기록
3. **c##dbzuser 생성**: Debezium 전용 사용자

```bash
#!/bin/bash
# setup-debezium-oracle.sh

echo "=== Debezium Oracle 설정 시작 ==="

# 1. ARCHIVELOG 모드 활성화 (DB 재시작 필요)
echo "1. ASIS Oracle - ARCHIVELOG 모드 활성화..."
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
SHUTDOWN IMMEDIATE;       -- DB 중지
STARTUP MOUNT;            -- 마운트 모드로 시작
ALTER DATABASE ARCHIVELOG; -- ARCHIVELOG 모드 전환
ALTER DATABASE OPEN;       -- DB 열기
ALTER PLUGGABLE DATABASE ALL OPEN;
SELECT LOG_MODE FROM V\$DATABASE;  -- 확인
exit;
EOF'

# 2. Supplemental Logging & Debezium 사용자 생성
echo "2. ASIS Oracle - Supplemental Logging & c##dbzuser 생성..."
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
-- Supplemental Logging 활성화
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

-- Debezium 사용자 생성 (c## 접두사는 CDB 공통 사용자를 의미)
CREATE USER c##dbzuser IDENTIFIED BY dbz
  DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS CONTAINER=ALL;

-- 필요한 권한 부여 (Debezium 문서 참고)
GRANT CREATE SESSION TO c##dbzuser CONTAINER=ALL;
GRANT SET CONTAINER TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$DATABASE TO c##dbzuser CONTAINER=ALL;
GRANT FLASHBACK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY TRANSACTION TO c##dbzuser CONTAINER=ALL;
GRANT LOGMINING TO c##dbzuser CONTAINER=ALL;
GRANT CREATE TABLE TO c##dbzuser CONTAINER=ALL;
GRANT LOCK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT CREATE SEQUENCE TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR_D TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOG_HISTORY TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_LOGS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_CONTENTS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_PARAMETERS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGFILE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$ARCHIVED_LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$ARCHIVE_DEST_STATUS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$TRANSACTION TO c##dbzuser CONTAINER=ALL;
exit;
EOF'

# TOBE Oracle도 동일하게 설정...

echo "=== Debezium Oracle 설정 완료 ==="
```

**왜 이 설정들이 필요한가?**

| 설정 | 이유 |
|------|------|
| ARCHIVELOG | Redo Log를 삭제하지 않고 보관하여 Debezium이 읽을 수 있게 함 |
| Supplemental Logging | UPDATE 시 변경되지 않은 컬럼도 로그에 기록 (전체 행 정보 필요) |
| c##dbzuser | Debezium이 Log Mining API를 사용하기 위한 전용 계정 |

### 4.4 Debezium 커넥터 등록 (register-connectors.sh)

```bash
#!/bin/bash
# register-connectors.sh

CONNECT_URL="http://localhost:8083"

echo "=== Debezium 커넥터 등록 ==="

# ASIS 커넥터 등록
echo "2. ASIS 커넥터 등록..."
curl -s -X POST ${CONNECT_URL}/connectors \
  -H "Content-Type: application/json" \
  -d '{
  "name": "asis-connector",
  "config": {
    "connector.class": "io.debezium.connector.oracle.OracleConnector",
    "tasks.max": "1",
    "database.hostname": "asis-oracle",
    "database.port": "1521",
    "database.user": "c##dbzuser",
    "database.password": "dbz",
    "database.dbname": "XE",
    "database.pdb.name": "XEPDB1",
    "topic.prefix": "asis",
    "table.include.list": "ASIS_USER.BOOK_INFO,ASIS_USER.MEMBER_INFO,ASIS_USER.LEGACY_CODE",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:29092",
    "schema.history.internal.kafka.topic": "schema-changes.asis",
    "log.mining.strategy": "online_catalog"
  }
}'

# TOBE 커넥터 등록
echo "3. TOBE 커넥터 등록..."
curl -s -X POST ${CONNECT_URL}/connectors \
  -H "Content-Type: application/json" \
  -d '{
  "name": "tobe-connector",
  "config": {
    "connector.class": "io.debezium.connector.oracle.OracleConnector",
    "tasks.max": "1",
    "database.hostname": "tobe-oracle",
    "database.port": "1521",
    "database.user": "c##dbzuser",
    "database.password": "dbz",
    "database.dbname": "XE",
    "database.pdb.name": "XEPDB1",
    "topic.prefix": "tobe",
    "table.include.list": "TOBE_USER.TB_BOOK,TOBE_USER.TB_MEMBER,TOBE_USER.TB_NEW_SERVICE",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:29092",
    "schema.history.internal.kafka.topic": "schema-changes.tobe",
    "log.mining.strategy": "online_catalog"
  }
}'

# 상태 확인
echo "4. 커넥터 상태 확인..."
curl -s ${CONNECT_URL}/connectors/asis-connector/status
curl -s ${CONNECT_URL}/connectors/tobe-connector/status
```

**커넥터 설정 설명**:

| 설정 | 값 | 설명 |
|------|---|------|
| `connector.class` | `io.debezium.connector.oracle.OracleConnector` | Oracle용 Debezium 커넥터 |
| `database.hostname` | `asis-oracle` | Docker 컨테이너명 |
| `database.dbname` | `XE` | CDB 이름 |
| `database.pdb.name` | `XEPDB1` | PDB 이름 |
| `topic.prefix` | `asis` | Kafka 토픽 접두사 |
| `table.include.list` | `ASIS_USER.BOOK_INFO,...` | 감시할 테이블 목록 |
| `log.mining.strategy` | `online_catalog` | LogMiner 전략 |

**생성되는 Kafka 토픽**:
- `asis.ASIS_USER.BOOK_INFO`
- `asis.ASIS_USER.MEMBER_INFO`
- `asis.ASIS_USER.LEGACY_CODE`
- `tobe.TOBE_USER.TB_BOOK`
- `tobe.TOBE_USER.TB_MEMBER`
- `tobe.TOBE_USER.TB_NEW_SERVICE`

---

## 5. Part 3: TOBE (신규) Oracle 구현

TOBE는 ASIS와 **반대 방향**으로 구현됩니다.

### 5.1 TOBE 스키마 특징

| 항목 | ASIS | TOBE |
|------|------|------|
| 컬럼명 | 약어 (`BOOK_TITLE`) | 전체 명칭 (`TITLE`) |
| 상태값 | 'Y'/'N' | 1/0 (NUMBER) |
| 카테고리 | '01', '02', '03' | 'LIT', 'SCI', 'HIS' |
| 회원타입 | 'A', 'B', 'C' | 'ADMIN', 'USER', 'GUEST' |

### 5.2 TOBE 원본 테이블 (01_create_tables.sql)

```sql
-- 도서 정보 (양방향)
CREATE TABLE TB_BOOK (
    BOOK_ID      NUMBER PRIMARY KEY,
    TITLE        VARCHAR2(500) NOT NULL,    -- TOBE 컬럼명
    AUTHOR_NAME  VARCHAR2(200),             -- TOBE 컬럼명
    CATEGORY_CD  VARCHAR2(10),              -- TOBE 코드: 'LIT', 'SCI', 'HIS'
    IS_ACTIVE    NUMBER(1) DEFAULT 1,       -- TOBE 스타일: 1/0
    CREATED_AT   TIMESTAMP DEFAULT SYSTIMESTAMP,
    UPDATED_AT   TIMESTAMP,
    CREATED_BY   VARCHAR2(50),
    UPDATED_BY   VARCHAR2(50)
);
```

### 5.3 TOBE CDC 테이블 (02_create_cdc_tables.sql)

```sql
-- CDC_TOBE_BOOK: ASIS의 BOOK_INFO 변경 데이터를 받는 테이블
CREATE TABLE CDC_TOBE_BOOK (
    CDC_SEQ         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    OPERATION       VARCHAR2(10) NOT NULL,

    -- ASIS 스키마 기준 컬럼들 (받는 데이터)
    BOOK_ID         NUMBER,
    BOOK_TITLE      VARCHAR2(200),          -- ASIS 컬럼명
    AUTHOR          VARCHAR2(100),          -- ASIS 컬럼명
    CATEGORY        VARCHAR2(2),            -- ASIS 코드
    STATUS          CHAR(1),                -- ASIS 스타일
    USE_YN          CHAR(1),
    REG_DATE        DATE,
    MOD_DATE        DATE,

    -- 메타 정보
    SOURCE_TIMESTAMP TIMESTAMP,
    RECEIVED_AT     TIMESTAMP DEFAULT SYSTIMESTAMP,
    PROCESSED_YN    CHAR(1) DEFAULT 'N',
    PROCESSED_AT    TIMESTAMP,
    ERROR_MSG       VARCHAR2(1000),
    CHANGE_HASH     VARCHAR2(64)
);
```

### 5.4 TOBE 코드 매핑 (03_create_mapping_tables.sql)

```sql
-- 카테고리 코드 매핑 (ASIS → TOBE)
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '01', 'TOBE', 'LIT', '문학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '02', 'TOBE', 'SCI', '과학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '03', 'TOBE', 'HIS', '역사');

-- 상태 코드 매핑 (ASIS → TOBE)
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'ASIS', 'Y', 'TOBE', '1', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'ASIS', 'N', 'TOBE', '0', '비활성');
```

### 5.5 TOBE WORKER 프로시저 (04_create_procedures.sql)

```sql
CREATE OR REPLACE PROCEDURE SP_WORKER_BOOK
IS
    v_hash VARCHAR2(64);
    v_err_msg VARCHAR2(500);
BEGIN
    -- 1단계: CDC → STAGING (스키마 변환: ASIS → TOBE)
    FOR rec IN (
        SELECT * FROM CDC_TOBE_BOOK WHERE PROCESSED_YN = 'N' ORDER BY CDC_SEQ
    ) LOOP
        BEGIN
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                UPDATE CDC_TOBE_BOOK SET PROCESSED_YN = 'S', ERROR_MSG = 'LOOP_BLOCKED'
                WHERE CDC_SEQ = rec.CDC_SEQ;
            ELSE
                INSERT INTO STAGING_TOBE_BOOK (
                    CDC_SEQ, OPERATION, BOOK_ID, TITLE, AUTHOR_NAME,
                    CATEGORY_CD, IS_ACTIVE, CREATED_AT, UPDATED_AT
                ) VALUES (
                    rec.CDC_SEQ,
                    rec.OPERATION,
                    rec.BOOK_ID,
                    rec.BOOK_TITLE,     -- ASIS.BOOK_TITLE → TOBE.TITLE
                    rec.AUTHOR,         -- ASIS.AUTHOR → TOBE.AUTHOR_NAME
                    FN_CONVERT_CODE('CATEGORY_MAP', 'ASIS', rec.CATEGORY),  -- '01' → 'LIT'
                    CASE rec.STATUS WHEN 'Y' THEN 1 ELSE 0 END,  -- 'Y' → 1
                    NVL(CAST(rec.REG_DATE AS TIMESTAMP), SYSTIMESTAMP),
                    CAST(rec.MOD_DATE AS TIMESTAMP)
                );
                UPDATE CDC_TOBE_BOOK SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP
                WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;
            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE CDC_TOBE_BOOK SET PROCESSED_YN = 'E', ERROR_MSG = v_err_msg
                WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- 2단계: STAGING → 원본 (TB_BOOK)
    -- ... (ASIS와 동일한 패턴)
END;
/
```

---

## 6. Part 4: Sync Service (Java) 구현

### 6.1 역할

Sync Service는 **Kafka 메시지를 받아서 상대 DB의 CDC 테이블에 INSERT**하는 역할을 합니다.

```
Debezium → Kafka Topic → Sync Service → 상대 DB CDC 테이블
```

### 6.2 Docker 설정

```yaml
sync-service:
  build: ./sync-service-java        # Dockerfile 위치
  image: poc-sync-service-java
  container_name: sync-service
  depends_on:
    - kafka
    - asis-oracle
    - tobe-oracle
  ports:
    - "8082:8080"
  environment:
    KAFKA_BOOTSTRAP_SERVERS: kafka:29092
    ASIS_DB_HOST: asis-oracle
    ASIS_DB_PORT: 1521
    ASIS_DB_SERVICE: XEPDB1
    ASIS_DB_USER: asis_user
    ASIS_DB_PASSWORD: asis123
    TOBE_DB_HOST: tobe-oracle
    TOBE_DB_PORT: 1521
    TOBE_DB_SERVICE: XEPDB1
    TOBE_DB_USER: tobe_user
    TOBE_DB_PASSWORD: tobe123
  networks:
    - cdc-net
```

### 6.3 Kafka Consumer (CdcKafkaConsumer.java)

```java
@Component
public class CdcKafkaConsumer {

    private final CdcSyncService syncService;

    // ASIS -> TOBE 동기화 리스너
    @KafkaListener(topics = "asis.ASIS_USER.BOOK_INFO", groupId = "cdc-sync-service")
    public void consumeAsisBookInfo(ConsumerRecord<String, String> record) {
        // ASIS 테이블 변경 → TOBE DB의 CDC_TOBE_BOOK에 저장
        CdcEvent event = parseDebeziumMessage(record.value());
        if (event != null) {
            syncService.syncAsisToTobe(event, "CDC_TOBE_BOOK", record.topic());
        }
    }

    // TOBE -> ASIS 동기화 리스너
    @KafkaListener(topics = "tobe.TOBE_USER.TB_BOOK", groupId = "cdc-sync-service")
    public void consumeTobeBook(ConsumerRecord<String, String> record) {
        // TOBE 테이블 변경 → ASIS DB의 CDC_ASIS_BOOK에 저장
        CdcEvent event = parseDebeziumMessage(record.value());
        if (event != null) {
            syncService.syncTobeToAsis(event, "CDC_ASIS_BOOK", record.topic());
        }
    }

    // Debezium JSON 메시지 파싱
    private CdcEvent parseDebeziumMessage(String message) {
        // JSON 파싱 후 CdcEvent 객체로 변환
        Map<String, Object> json = objectMapper.readValue(message, ...);

        // payload 추출
        Map<String, Object> payload = (Map<String, Object>) json.get("payload");

        CdcEvent event = new CdcEvent();
        event.setOperation(convertOperation(payload.get("op")));  // c→INSERT
        event.setAfter((Map<String, Object>) payload.get("after"));
        event.setBefore((Map<String, Object>) payload.get("before"));

        return event;
    }
}
```

### 6.4 동기화 서비스 (CdcSyncService.java)

```java
@Service
public class CdcSyncService {

    @Qualifier("asisJdbcTemplate")
    private final JdbcTemplate asisJdbcTemplate;

    @Qualifier("tobeJdbcTemplate")
    private final JdbcTemplate tobeJdbcTemplate;

    // ASIS → TOBE 동기화
    public void syncAsisToTobe(CdcEvent event, String targetTable, String topic) {
        insertToCdcTable(tobeJdbcTemplate, event, targetTable, "ASIS->TOBE");
    }

    // TOBE → ASIS 동기화
    public void syncTobeToAsis(CdcEvent event, String targetTable, String topic) {
        insertToCdcTable(asisJdbcTemplate, event, targetTable, "TOBE->ASIS");
    }

    // CDC 테이블에 INSERT
    private void insertToCdcTable(JdbcTemplate jdbc, CdcEvent event,
                                  String targetTable, String direction) {
        Map<String, Object> data = event.getData();

        // 동적 INSERT SQL 생성
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        // 기본 컬럼
        columns.add("OPERATION");
        values.add(event.getOperation());

        columns.add("SOURCE_TIMESTAMP");
        values.add(event.getSourceTimestamp());

        columns.add("CHANGE_HASH");
        values.add(event.getChangeHash());

        // 데이터 컬럼
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            columns.add(entry.getKey().toUpperCase());
            values.add(entry.getValue());
        }

        // SQL 실행
        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                targetTable,
                String.join(", ", columns),
                columns.stream().map(c -> "?").collect(Collectors.joining(", ")));

        jdbc.update(sql, values.toArray());
    }
}
```

---

## 7. Part 5: 전체 실행 순서

### 7.1 사전 준비

1. **Docker Desktop** 설치 및 실행
2. **Oracle JDBC 드라이버** 다운로드
   - `ojdbc11.jar`를 `poc/kafka-connect/libs/` 폴더에 복사

### 7.2 실행 순서

```bash
# 1. poc 폴더로 이동
cd C:\Devel\cdc-sync-poc\poc

# 2. Docker 컨테이너 시작
docker-compose up -d

# 3. Oracle 완전 기동 대기 (약 3분)
# 로그 확인: docker logs asis-oracle
# "DATABASE IS READY TO USE!" 메시지 확인

# 4. Debezium 설정 (ARCHIVELOG, c##dbzuser)
# Windows: Git Bash나 WSL에서 실행
./scripts/setup-debezium-oracle.sh

# 5. Kafka Connect 준비 확인
curl http://localhost:8083/
# 정상이면 {"version":"...","commit":"..."} 반환

# 6. Debezium 커넥터 등록
./scripts/register-connectors.sh

# 7. 커넥터 상태 확인
curl http://localhost:8083/connectors/asis-connector/status
curl http://localhost:8083/connectors/tobe-connector/status
# "state": "RUNNING" 확인
```

### 7.3 각 단계별 확인 방법

**Oracle 준비 확인**:
```bash
docker logs asis-oracle 2>&1 | grep "DATABASE IS READY"
```

**Kafka Connect 준비 확인**:
```bash
curl -s http://localhost:8083/ | head
```

**커넥터 상태 확인**:
```bash
curl -s http://localhost:8083/connectors/asis-connector/status | python -m json.tool
```

---

## 8. Part 6: 테스트 및 검증

### 8.1 ASIS → TOBE 테스트

```bash
# 1. ASIS에 데이터 INSERT
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER SESSION SET CONTAINER = XEPDB1;
INSERT INTO ASIS_USER.BOOK_INFO (BOOK_ID, BOOK_TITLE, AUTHOR, CATEGORY, STATUS)
VALUES (100, '\''테스트도서ASIS'\'', '\''테스트저자'\'', '\''01'\'', '\''Y'\'');
COMMIT;
exit;
EOF'

# 2. 15초 대기 (CDC 처리 시간)
sleep 15

# 3. TOBE에서 확인
docker exec tobe-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER SESSION SET CONTAINER = XEPDB1;
SELECT BOOK_ID, TITLE, AUTHOR_NAME, IS_ACTIVE FROM TOBE_USER.TB_BOOK WHERE BOOK_ID = 100;
exit;
EOF'
```

**예상 결과**:
```
   BOOK_ID  TITLE           AUTHOR_NAME    IS_ACTIVE
---------- --------------- -------------- ----------
       100 테스트도서ASIS    테스트저자          1
```

### 8.2 TOBE → ASIS 테스트

```bash
# 1. TOBE에 데이터 INSERT
docker exec tobe-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER SESSION SET CONTAINER = XEPDB1;
INSERT INTO TOBE_USER.TB_BOOK (BOOK_ID, TITLE, AUTHOR_NAME, CATEGORY_CD, IS_ACTIVE, CREATED_AT)
VALUES (200, '\''테스트도서TOBE'\'', '\''TOBE저자'\'', '\''LIT'\'', 1, SYSTIMESTAMP);
COMMIT;
exit;
EOF'

# 2. 15초 대기
sleep 15

# 3. ASIS에서 확인
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER SESSION SET CONTAINER = XEPDB1;
SELECT BOOK_ID, BOOK_TITLE, AUTHOR, STATUS FROM ASIS_USER.BOOK_INFO WHERE BOOK_ID = 200;
exit;
EOF'
```

**예상 결과**:
```
   BOOK_ID  BOOK_TITLE      AUTHOR         STATUS
---------- --------------- -------------- ------
       200 테스트도서TOBE    TOBE저자         Y
```

### 8.3 동기화 로그 확인

```sql
-- ASIS 동기화 로그
SELECT * FROM ASIS_USER.CDC_SYNC_LOG ORDER BY LOG_SEQ DESC;

-- TOBE 동기화 로그
SELECT * FROM TOBE_USER.CDC_SYNC_LOG ORDER BY LOG_SEQ DESC;
```

### 8.4 모니터링 UI

- **Kafka UI**: http://localhost:8081
  - 토픽 목록, 메시지 확인
- **Sync Service**: http://localhost:8082/dashboard
  - 동기화 통계, 에러 로그

---

## 9. 트러블슈팅

### 9.1 포트 충돌 (Windows)

**증상**: `docker-compose up` 시 포트 바인딩 실패

**원인**: Windows Hyper-V가 특정 포트 범위를 예약

**해결**:
```yaml
# docker-compose.yml에서 포트 변경
ports:
  - "15210:1521"  # 1521 대신 15210 사용
```

### 9.2 커넥터 FAILED 상태

**증상**: `curl .../status`에서 `"state": "FAILED"`

**확인**:
```bash
curl -s http://localhost:8083/connectors/asis-connector/status | python -m json.tool
```

**흔한 원인**:
1. **ARCHIVELOG 미설정**: `setup-debezium-oracle.sh` 재실행
2. **c##dbzuser 없음**: 사용자 생성 스크립트 확인
3. **ojdbc11.jar 없음**: 파일 존재 여부 확인

### 9.3 프로시저 INVALID

**증상**: `SELECT object_name, status FROM user_objects WHERE object_type = 'PROCEDURE'`에서 INVALID

**원인**: SQLERRM을 SQL 문장 내에서 직접 사용 (Oracle 제약)

**해결**: 변수에 먼저 저장
```sql
-- 잘못된 방법
UPDATE ... SET ERROR_MSG = SUBSTR(SQLERRM, 1, 500) ...

-- 올바른 방법
v_err_msg := SUBSTR(SQLERRM, 1, 500);
UPDATE ... SET ERROR_MSG = v_err_msg ...
```

### 9.4 무한루프 발생

**증상**: CDC 테이블에 동일 데이터가 반복 생성

**확인**: `PROCESSED_YN = 'S'` (Skipped) 레코드 확인
```sql
SELECT * FROM CDC_ASIS_BOOK WHERE PROCESSED_YN = 'S';
```

**원인**: 해시 기록이 안 됨

**해결**: `SP_RECORD_HASH` 프로시저 정상 동작 확인

---

## 부록: 전체 컴포넌트 요약

| 컴포넌트 | 기술 | 포트 | 역할 |
|---------|------|------|------|
| ASIS Oracle | Oracle XE 21c | 15210 | 레거시 DB |
| TOBE Oracle | Oracle XE 21c | 15220 | 신규 DB |
| Zookeeper | Confluent | 2181 | Kafka 클러스터 관리 |
| Kafka | Confluent | 9092 | 메시지 브로커 |
| Kafka Connect | Debezium 2.4 | 8083 | CDC 커넥터 호스팅 |
| Sync Service | Spring Boot | 8082 | Kafka → CDC 테이블 |
| Kafka UI | Provectus | 8081 | 모니터링 |

---

> **문서 작성일**: 2025년 1월
> **마지막 테스트 환경**: Windows 11, Docker Desktop, Oracle XE 21c
