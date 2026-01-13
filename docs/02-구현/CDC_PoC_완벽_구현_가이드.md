# CDC PoC 완벽 구현 가이드

> **이 문서의 목표**: 이 문서 하나만 읽으면 **CDC가 뭔지 모르는 상태에서 시작해서**, **왜 이렇게 구현했는지 이해하면서**, **처음부터 끝까지 혼자 구현**할 수 있습니다.
>
> **예상 소요 시간**: 완독 2~3시간, 실습 포함 4~5시간
>
> **블로그 활용**: 이 문서를 기반으로 블로그 시리즈를 작성할 수 있습니다.

---

## 목차

1. [들어가기 전에: 이 프로젝트가 해결하는 문제](#1-들어가기-전에-이-프로젝트가-해결하는-문제)
2. [CDC 개념 완전 정복](#2-cdc-개념-완전-정복)
3. [전체 아키텍처 한눈에 보기](#3-전체-아키텍처-한눈에-보기)
4. [Part 1: ASIS Oracle 구현 (레거시 시스템)](#4-part-1-asis-oracle-구현-레거시-시스템)
5. [Part 2: Kafka + Debezium 구현 (CDC 파이프라인)](#5-part-2-kafka--debezium-구현-cdc-파이프라인)
6. [Part 3: TOBE Oracle 구현 (신규 시스템)](#6-part-3-tobe-oracle-구현-신규-시스템)
7. [Part 4: Java Sync Service 구현 (중계 서비스)](#7-part-4-java-sync-service-구현-중계-서비스)
8. [Part 5: 전체 실행 및 테스트](#8-part-5-전체-실행-및-테스트)
9. [Part 6: 트러블슈팅 & FAQ](#9-part-6-트러블슈팅--faq)
10. [마치며: 핵심 정리](#10-마치며-핵심-정리)

---

## 1. 들어가기 전에: 이 프로젝트가 해결하는 문제

### 1.1 실제 상황

"법원도서관 시스템"이라는 실제 프로젝트를 예로 들어보겠습니다.

```
현재 상황:
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   ASIS (레거시 시스템)              TOBE (신규 시스템)            │
│   ├── 25년간 사용                   ├── 새로 개발 중              │
│   ├── 171개 테이블                  ├── 스키마 표준화             │
│   ├── 컬럼명이 약어                 ├── 컬럼명이 명확             │
│   │   (BOOK_TITLE, STATUS)          │   (TITLE, IS_ACTIVE)        │
│   └── 기존 소스 수정 불가           └── 점진적 전환 예정          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**문제**: 1차에서 일부 기능만 TOBE로 전환 → **양쪽 시스템이 동시에 운영됨**

```
ASIS에서 도서 정보 수정  →  TOBE에는 반영 안 됨  →  데이터 불일치!
TOBE에서 회원 정보 수정  →  ASIS에는 반영 안 됨  →  데이터 불일치!
```

### 1.2 왜 기존 방식으로는 안 되는가?

**방안 1: 배치 동기화 (매일 밤 전체 비교)**
```
❌ 문제점:
- 실시간 반영 안 됨 (낮에 수정하면 다음 날까지 불일치)
- 171개 테이블 전체 비교는 시간과 리소스 낭비
- 어디서 수정했는지 추적 어려움
```

**방안 2: 트리거 (변경 시 즉시 전송)**
```
❌ 문제점:
- 기존 테이블에 트리거 추가 = 기존 소스 수정 (금지됨)
- 트리거는 성능 저하 유발
- 양방향 시 무한루프 위험
```

**방안 3: CDC (Change Data Capture) - 우리가 선택한 방법**
```
✅ 장점:
- 기존 테이블/소스 수정 없음
- 실시간 동기화
- 변경분만 처리 (효율적)
- 무한루프 방지 가능
```

### 1.3 제약조건 정리

| 제약 | 내용 |
|------|------|
| ❌ 금지 | 기존 ASIS 테이블에 컬럼 추가 |
| ❌ 금지 | 기존 ASIS 소스코드 수정 |
| ✅ 허용 | 별도 동기화용 테이블 생성 |
| ✅ 허용 | 별도 프로시저/Job 생성 |

---

## 2. CDC 개념 완전 정복

### 2.1 CDC가 뭔가요? (비유로 이해하기)

**은행 거래 장부에 비유:**

```
┌─────────────────────────────────────────────────────────────────┐
│                     은행 거래 시스템                              │
│                                                                  │
│   고객: "100만원 입금해주세요"                                    │
│           │                                                      │
│           ▼                                                      │
│   ┌─────────────┐     ┌─────────────────────┐                   │
│   │ 계좌 잔액   │     │  거래 장부 (Ledger)  │                   │
│   │  갱신       │ ──► │  자동으로 기록됨     │                   │
│   │ 900→1000만 │     │  "입금 100만원"      │                   │
│   └─────────────┘     └─────────────────────┘                   │
│                                │                                 │
│                                ▼                                 │
│                       누군가가 장부를 읽어서                       │
│                       다른 시스템에 전달 가능                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**CDC는 이 "장부를 읽는 사람" 역할을 합니다:**
- 데이터베이스의 "거래 장부" = **Redo Log** (Oracle) / **Binary Log** (MySQL)
- "장부를 읽는 사람" = **Debezium** (CDC 도구)
- 장부를 읽어서 전달하는 곳 = **Kafka** (메시지 브로커)

### 2.2 왜 Debezium인가?

CDC 도구는 여러 가지가 있습니다:

| 도구 | 장점 | 단점 |
|------|------|------|
| **Debezium** | 무료, Oracle 지원, Kafka 통합, 활발한 커뮤니티 | 설정 복잡 |
| Oracle GoldenGate | 고성능, Oracle 공식 | 비쌈 (라이선스) |
| AWS DMS | 클라우드 통합 | AWS 종속 |

**Debezium 선택 이유:**
1. **무료** - 오픈소스 (Apache License 2.0)
2. **Oracle 공식 지원** - LogMiner 기반
3. **Kafka 통합** - 메시지 전달이 자연스러움
4. **커뮤니티** - 자료가 많음, 문제 해결이 쉬움

### 2.3 Debezium이 Oracle을 읽는 방법: LogMiner

**Oracle의 Redo Log 구조:**

```
┌─────────────────────────────────────────────────────────────────┐
│                      Oracle Database                             │
│                                                                  │
│   사용자 쿼리                                                     │
│   UPDATE BOOK_INFO SET BOOK_TITLE = '새제목' WHERE BOOK_ID = 1;  │
│           │                                                      │
│           ▼                                                      │
│   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐       │
│   │  Buffer     │ ──► │  Redo Log   │ ──► │ Archive Log │       │
│   │  Cache      │     │  (순환)     │     │  (보관)     │       │
│   └─────────────┘     └─────────────┘     └─────────────┘       │
│                              │                                   │
│                              ▼                                   │
│                       LogMiner API                               │
│                       (Debezium이 호출)                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**LogMiner가 뭔가요?**
- Oracle이 제공하는 **Redo Log 분석 도구**
- SQL로 Redo Log 내용을 조회할 수 있음
- Debezium이 이걸 이용해서 변경사항을 추출

**왜 ARCHIVELOG 모드가 필요한가?**
```
NOARCHIVELOG 모드 (기본값):
┌──────────┐  가득 차면  ┌──────────┐
│ Redo Log │────덮어쓰기──►│ Redo Log │ = 과거 데이터 손실!
└──────────┘             └──────────┘

ARCHIVELOG 모드 (우리가 설정):
┌──────────┐  가득 차면  ┌──────────────┐
│ Redo Log │────복사────►│ Archive Log  │ = 과거 데이터 보존
└──────────┘             └──────────────┘
                         (Debezium이 읽을 수 있음)
```

### 2.4 무한루프 문제와 해결

**양방향 동기화의 함정:**

```
1. ASIS에서 BOOK_TITLE 변경
2. → Debezium이 감지 → TOBE로 전송
3. → TOBE에서 TITLE 변경됨 (동기화)
4. → Debezium이 TOBE 변경 감지 → ASIS로 전송
5. → ASIS에서 BOOK_TITLE 변경됨 (동기화)
6. → Debezium이 ASIS 변경 감지 → TOBE로 전송
7. → 무한 반복... 💀
```

**해결책: 해시(Hash) 기반 탐지**

```
변경 데이터 → SHA-256 해시 생성 → 기록

데이터 수신 시:
1. 해시 계산
2. "이 해시, 내가 최근에 보낸 거 아니야?" 확인
3. 맞으면 → 무시 (내가 보낸 게 돌아온 것)
   아니면 → 처리 (상대방이 보낸 것)
```

---

## 3. 전체 아키텍처 한눈에 보기

### 3.1 컴포넌트 구성도

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          CDC PoC 전체 아키텍처                             │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌─────────────────────┐                    ┌─────────────────────┐    │
│   │    ASIS Oracle      │                    │    TOBE Oracle      │    │
│   │    (레거시)          │                    │    (신규)           │    │
│   │    Port: 15210      │                    │    Port: 15220      │    │
│   ├─────────────────────┤                    ├─────────────────────┤    │
│   │ 원본 테이블          │                    │ 원본 테이블          │    │
│   │ ├ BOOK_INFO         │                    │ ├ TB_BOOK           │    │
│   │ ├ MEMBER_INFO       │                    │ ├ TB_MEMBER         │    │
│   │ └ LEGACY_CODE       │                    │ └ TB_NEW_SERVICE    │    │
│   ├─────────────────────┤                    ├─────────────────────┤    │
│   │ CDC 수신 테이블      │                    │ CDC 수신 테이블      │    │
│   │ ├ CDC_ASIS_BOOK  ◄──┼────────────────────┼── CDC_TOBE_BOOK     │    │
│   │ └ STAGING_ASIS_*    │                    │ └ STAGING_TOBE_*    │    │
│   ├─────────────────────┤                    ├─────────────────────┤    │
│   │ WORKER 프로시저      │                    │ WORKER 프로시저      │    │
│   │ (5초마다 자동 실행)   │                    │ (5초마다 자동 실행)   │    │
│   └─────────┬───────────┘                    └───────────┬─────────┘    │
│             │                                            │              │
│             │ Debezium이 Redo Log 읽음                    │              │
│             ▼                                            ▼              │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │                        Kafka Connect                             │  │
│   │                    (Debezium Connector)                          │  │
│   │                       Port: 8083                                 │  │
│   │  ┌─────────────────┐              ┌─────────────────┐           │  │
│   │  │ asis-connector  │              │ tobe-connector  │           │  │
│   │  │ (ASIS 변경 감시) │              │ (TOBE 변경 감시) │           │  │
│   │  └────────┬────────┘              └────────┬────────┘           │  │
│   └───────────┼────────────────────────────────┼────────────────────┘  │
│               │                                │                       │
│               ▼                                ▼                       │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │                           Kafka                                  │  │
│   │                        Port: 9092                                │  │
│   │  ┌───────────────────────┐    ┌───────────────────────┐        │  │
│   │  │ Topic:                │    │ Topic:                │        │  │
│   │  │ asis.ASIS_USER.       │    │ tobe.TOBE_USER.       │        │  │
│   │  │ BOOK_INFO             │    │ TB_BOOK               │        │  │
│   │  └───────────┬───────────┘    └───────────┬───────────┘        │  │
│   └──────────────┼────────────────────────────┼────────────────────┘  │
│                  │                            │                       │
│                  └──────────┬─────────────────┘                       │
│                             ▼                                         │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │                      Sync Service                                │  │
│   │                   (Java Spring Boot)                             │  │
│   │                      Port: 8082                                  │  │
│   │                                                                  │  │
│   │   asis 토픽 메시지 수신 → TOBE DB CDC 테이블에 INSERT            │  │
│   │   tobe 토픽 메시지 수신 → ASIS DB CDC 테이블에 INSERT            │  │
│   │                                                                  │  │
│   └─────────────────────────────────────────────────────────────────┘  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 3.2 데이터 흐름 예시 (ASIS → TOBE)

```
[STEP 1] 사용자가 ASIS DB에서 UPDATE 실행

         UPDATE BOOK_INFO SET BOOK_TITLE = '새제목' WHERE BOOK_ID = 1;

[STEP 2] Oracle이 Redo Log에 변경사항 기록 (자동)

         Redo Log: { BOOK_ID: 1, BOOK_TITLE: '새제목', ... }

[STEP 3] Debezium(asis-connector)이 LogMiner로 Redo Log 읽기

         5초마다 폴링하면서 변경사항 탐지

[STEP 4] Kafka 토픽에 이벤트 발행

         Topic: asis.ASIS_USER.BOOK_INFO
         Message: {
           "op": "u",
           "before": { "BOOK_TITLE": "기존제목", ... },
           "after": { "BOOK_TITLE": "새제목", ... }
         }

[STEP 5] Sync Service가 Kafka 메시지 수신

         @KafkaListener가 메시지 수신
         → JSON 파싱 → CdcEvent 객체로 변환
         → 해시 생성

[STEP 6] TOBE DB의 CDC 테이블에 INSERT

         INSERT INTO CDC_TOBE_BOOK (OPERATION, BOOK_ID, BOOK_TITLE, ...)
         VALUES ('UPDATE', 1, '새제목', ...);

[STEP 7] TOBE WORKER 프로시저가 처리 (5초마다 자동 실행)

         1. CDC_TOBE_BOOK에서 미처리 데이터 조회
         2. 무한루프 체크 (해시 비교)
         3. 스키마 변환: BOOK_TITLE → TITLE, 'Y' → 1
         4. STAGING_TOBE_BOOK에 INSERT
         5. TB_BOOK 원본 테이블에 UPDATE
         6. 해시 기록 (무한루프 방지)
```

### 3.3 각 컴포넌트 역할 비유

| 컴포넌트 | 비유 | 역할 |
|---------|------|------|
| Oracle Redo Log | 은행 거래 장부 | 모든 변경사항 기록 |
| Debezium | 장부 감시자 | 장부를 읽어서 이벤트 생성 |
| Kafka | 우체국 | 이벤트 전달 및 임시 저장 |
| Sync Service | 우편 배달부 | 메시지 수신 후 상대 DB로 전달 |
| CDC 테이블 | 우편함 | 받은 이벤트 임시 저장 |
| STAGING 테이블 | 정리 서랍 | 스키마 변환 후 대기 |
| WORKER | 우편물 처리자 | CDC → STAGING → 원본 처리 |

### 3.4 Docker 컨테이너 구성

```yaml
# 총 7개 컨테이너
services:
  asis-oracle:     # ASIS DB        | Port 15210
  tobe-oracle:     # TOBE DB        | Port 15220
  zookeeper:       # Kafka 관리     | Port 2181
  kafka:           # 메시지 브로커  | Port 9092
  kafka-connect:   # Debezium       | Port 8083
  sync-service:    # Java 앱        | Port 8082
  kafka-ui:        # 모니터링       | Port 8081
```

---

## 4. Part 1: ASIS Oracle 구현 (레거시 시스템)

### 4.1 전체 구조 이해

ASIS Oracle에 만들어야 하는 것들:

```
ASIS Oracle
├── 사용자 (ASIS_USER)
├── 원본 테이블 (BOOK_INFO, MEMBER_INFO, LEGACY_CODE)
├── CDC 수신 테이블 (CDC_ASIS_BOOK, ...)  ← TOBE에서 온 데이터
├── STAGING 테이블 (STAGING_ASIS_BOOK, ...)
├── 코드 매핑 테이블 (SYNC_CODE_MAPPING)
├── 해시 저장 테이블 (CDC_PROCESSED_HASH)
├── WORKER 프로시저 (SP_WORKER_BOOK, ...)
└── Scheduler Job (5초마다 WORKER 실행)
```

### 4.2 Docker Compose 설정

```yaml
# docker-compose.yml 중 asis-oracle 부분

asis-oracle:
  image: gvenzl/oracle-xe:21-slim
  # ↑ 왜 이 이미지인가?
  # - gvenzl/oracle-xe: Gerald Venzl이 만든 경량화 Oracle XE 이미지
  # - 공식 이미지보다 크기 작고 시작 빠름
  # - 21-slim: Oracle 21c 버전, slim 태그는 최소 설치

  container_name: asis-oracle

  environment:
    ORACLE_PASSWORD: oracle123
    # ↑ SYS/SYSTEM 계정 비밀번호

    APP_USER: asis_user
    APP_USER_PASSWORD: asis123
    # ↑ 이미지가 자동으로 이 사용자를 생성해줌

  ports:
    - "15210:1521"
    # ↑ 왜 1521이 아니라 15210인가?
    # - Windows Hyper-V가 1521 포트를 예약하는 경우가 있음
    # - 충돌 방지를 위해 15210 사용

  volumes:
    - asis-oracle-data:/opt/oracle/oradata
    # ↑ 데이터 영속성 (컨테이너 삭제해도 데이터 유지)

    - ./asis-oracle/init:/container-entrypoint-initdb.d
    # ↑ 핵심! 이 폴더의 SQL 파일들이 첫 시작 시 자동 실행됨
    # - 파일명 순서대로 실행 (00 → 01 → 02 → ...)

  networks:
    - cdc-net
    # ↑ 같은 네트워크의 컨테이너끼리 이름으로 통신 가능
    # - 예: kafka-connect에서 "asis-oracle:1521"로 접속

  healthcheck:
    test: ["CMD", "healthcheck.sh"]
    interval: 30s
    timeout: 10s
    retries: 10
    # ↑ Oracle이 완전히 시작될 때까지 기다림
    # - 다른 컨테이너가 depends_on으로 대기 가능
```

**왜 volumes로 init 스크립트를 마운트하나?**
```
이 이미지의 특징:
- /container-entrypoint-initdb.d/ 폴더에 있는 스크립트를
- 컨테이너 첫 시작 시 자동으로 실행함
- MySQL의 /docker-entrypoint-initdb.d/와 비슷한 개념
```

### 4.3 사용자 생성 (00_setup_user.sql)

```sql
-- ============================================
-- ASIS Oracle 초기화 스크립트
-- 00. 사용자 생성 및 권한 부여
-- ============================================

-- [왜 PDB로 전환하나?]
-- Oracle 12c부터 CDB(Container DB) / PDB(Pluggable DB) 구조 도입
-- - CDB: 컨테이너 데이터베이스 (시스템 레벨)
-- - PDB: 플러거블 데이터베이스 (실제 사용자 데이터)
-- 실제 테이블은 PDB(XEPDB1)에 생성해야 함
ALTER SESSION SET CONTAINER = XEPDB1;

-- 사용자 생성 (이미 존재하면 건너뜀)
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

-- [왜 이 권한들이 필요한가?]
GRANT CONNECT, RESOURCE TO ASIS_USER;         -- 기본 접속 및 리소스 권한
GRANT CREATE SESSION TO ASIS_USER;            -- 세션 생성
GRANT CREATE TABLE TO ASIS_USER;              -- 테이블 생성
GRANT CREATE PROCEDURE TO ASIS_USER;          -- 프로시저 생성 (WORKER)
GRANT CREATE SEQUENCE TO ASIS_USER;           -- 시퀀스 생성
GRANT CREATE VIEW TO ASIS_USER;               -- 뷰 생성
GRANT CREATE JOB TO ASIS_USER;                -- ★ Scheduler Job 생성 권한
GRANT EXECUTE ON SYS.DBMS_CRYPTO TO ASIS_USER; -- ★ 해시 함수 사용 권한
```

**왜 CREATE JOB 권한이 필요한가?**
```
WORKER 프로시저를 5초마다 자동 실행하기 위해
Oracle Scheduler Job을 사용함
→ CREATE JOB 권한 필요
```

**왜 DBMS_CRYPTO 권한이 필요한가?**
```
무한루프 방지를 위한 해시 생성에 SHA-256 사용
→ DBMS_CRYPTO.HASH 함수 필요
→ EXECUTE ON SYS.DBMS_CRYPTO 권한 필요
```

### 4.4 원본 테이블 생성 (01_create_tables.sql)

```sql
ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = ASIS_USER;

-- ============================================
-- ASIS 스키마 특징:
-- - 컬럼명이 약어 (BOOK_TITLE, SERVICE_NM)
-- - 상태값이 'Y'/'N' 문자
-- - 코드가 숫자 ('01', '02', '03')
-- - 날짜가 DATE 타입
-- ============================================

-- 도서 정보 (양방향 동기화)
CREATE TABLE BOOK_INFO (
    BOOK_ID     NUMBER PRIMARY KEY,
    BOOK_TITLE  VARCHAR2(200) NOT NULL,    -- ← TOBE에서는 TITLE
    AUTHOR      VARCHAR2(100),              -- ← TOBE에서는 AUTHOR_NAME
    CATEGORY    VARCHAR2(2),                -- ← '01', '02' (TOBE는 'LIT', 'SCI')
    STATUS      CHAR(1) DEFAULT 'Y',        -- ← 'Y'/'N' (TOBE는 1/0)
    REG_DATE    DATE DEFAULT SYSDATE,       -- ← DATE (TOBE는 TIMESTAMP)
    MOD_DATE    DATE
);

-- 회원 정보 (양방향 동기화)
CREATE TABLE MEMBER_INFO (
    MEMBER_ID   NUMBER PRIMARY KEY,
    MEMBER_NAME VARCHAR2(50) NOT NULL,
    EMAIL       VARCHAR2(100),              -- ← TOBE에서는 EMAIL_ADDR
    MEMBER_TYPE CHAR(1),                    -- ← 'A', 'B', 'C' (TOBE는 'ADMIN', 'USER')
    STATUS      CHAR(1) DEFAULT 'Y',
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 레거시 코드 (ASIS → TOBE 단방향)
CREATE TABLE LEGACY_CODE (
    CODE_ID     VARCHAR2(10) PRIMARY KEY,
    CODE_NAME   VARCHAR2(100),
    USE_YN      CHAR(1) DEFAULT 'Y',        -- ← TOBE에서는 IS_ACTIVE
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 신규 서비스 수신용 (TOBE → ASIS 단방향)
-- TOBE에서만 관리하고 ASIS는 읽기만 함
CREATE TABLE NEW_SERVICE_RECV (
    SERVICE_ID  NUMBER PRIMARY KEY,
    SERVICE_NM  VARCHAR2(100),
    SVC_TYPE    VARCHAR2(10),
    USE_YN      CHAR(1),
    REG_DATE    DATE
);
```

**ASIS vs TOBE 스키마 차이 정리:**

| 항목 | ASIS | TOBE |
|------|------|------|
| 도서 제목 | `BOOK_TITLE` | `TITLE` |
| 저자 | `AUTHOR` | `AUTHOR_NAME` |
| 상태 | `'Y'` / `'N'` | `1` / `0` |
| 카테고리 | `'01'`, `'02'`, `'03'` | `'LIT'`, `'SCI'`, `'HIS'` |
| 회원타입 | `'A'`, `'B'`, `'C'` | `'ADMIN'`, `'USER'`, `'GUEST'` |
| 날짜 | `DATE` | `TIMESTAMP` |

### 4.5 CDC 수신 테이블 생성 (02_create_cdc_tables.sql)

**CDC 테이블이란?**
```
TOBE에서 변경된 데이터가 Kafka를 통해 전달되면
Sync Service가 이 테이블에 INSERT함

즉, "상대방에서 온 변경사항을 임시 저장하는 테이블"
```

```sql
-- CDC_ASIS_BOOK: TOBE의 TB_BOOK 변경 데이터를 받는 테이블
CREATE TABLE CDC_ASIS_BOOK (
    -- [자동 증가 PK]
    CDC_SEQ         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- [작업 유형]
    OPERATION       VARCHAR2(10) NOT NULL,  -- INSERT, UPDATE, DELETE

    -- [TOBE 스키마 기준 컬럼들]
    -- 왜 TOBE 스키마인가? → Sync Service가 TOBE에서 온 데이터를 그대로 저장하므로
    BOOK_ID         NUMBER,
    TITLE           VARCHAR2(500),          -- TOBE 컬럼명
    AUTHOR_NAME     VARCHAR2(200),          -- TOBE 컬럼명
    CATEGORY_CD     VARCHAR2(10),           -- TOBE 코드: 'LIT', 'SCI'
    IS_ACTIVE       NUMBER(1),              -- TOBE 스타일: 1/0
    CREATED_AT      TIMESTAMP,
    UPDATED_AT      TIMESTAMP,
    CREATED_BY      VARCHAR2(50),
    UPDATED_BY      VARCHAR2(50),

    -- [메타 정보]
    SOURCE_TIMESTAMP TIMESTAMP,             -- 원본에서 변경된 시간
    RECEIVED_AT     TIMESTAMP DEFAULT SYSTIMESTAMP,  -- 이 DB에 도착한 시간
    PROCESSED_YN    CHAR(1) DEFAULT 'N',    -- 처리 상태
                                            -- N: 미처리
                                            -- Y: 처리 완료
                                            -- E: 에러
                                            -- S: 스킵 (무한루프 방지)
    PROCESSED_AT    TIMESTAMP,              -- 처리된 시간
    ERROR_MSG       VARCHAR2(1000),         -- 에러 발생 시 메시지
    CHANGE_HASH     VARCHAR2(64)            -- 무한루프 방지용 해시 (SHA-256)
);

-- [인덱스 생성]
-- 왜 PROCESSED_YN에 인덱스를 거나?
-- → WORKER가 "WHERE PROCESSED_YN = 'N'" 조건으로 미처리 데이터를 조회하므로
CREATE INDEX IDX_CDC_ASIS_BOOK_PROC ON CDC_ASIS_BOOK(PROCESSED_YN);
```

**STAGING 테이블이란?**
```
CDC 테이블 (TOBE 스키마) → 스키마 변환 → STAGING 테이블 (ASIS 스키마)

왜 중간 단계가 필요한가?
1. 스키마 변환과 원본 반영을 분리하여 트랜잭션 관리 용이
2. 에러 발생 시 어느 단계에서 실패했는지 추적 가능
3. 변환 로직과 반영 로직을 독립적으로 수정 가능
```

```sql
-- STAGING_ASIS_BOOK: 스키마 변환 후 대기 테이블
CREATE TABLE STAGING_ASIS_BOOK (
    STAGING_SEQ     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    CDC_SEQ         NUMBER,                 -- 원본 CDC 레코드 참조
    OPERATION       VARCHAR2(10) NOT NULL,

    -- [ASIS 스키마 기준 컬럼들]
    -- CDC 테이블의 TOBE 스키마 → 여기서 ASIS 스키마로 변환됨
    BOOK_ID         NUMBER,
    BOOK_TITLE      VARCHAR2(200),          -- TITLE → BOOK_TITLE
    AUTHOR          VARCHAR2(100),          -- AUTHOR_NAME → AUTHOR
    CATEGORY        VARCHAR2(2),            -- 'LIT' → '01'
    STATUS          CHAR(1),                -- 1 → 'Y'
    REG_DATE        DATE,
    MOD_DATE        DATE,

    STAGED_AT       TIMESTAMP DEFAULT SYSTIMESTAMP,
    PROCESSED_YN    CHAR(1) DEFAULT 'N',
    PROCESSED_AT    TIMESTAMP,
    ERROR_MSG       VARCHAR2(1000)
);
```

**데이터 흐름:**
```
TOBE TB_BOOK 변경
       │
       ▼
Kafka → Sync Service
       │
       ▼ (TOBE 스키마 그대로)
CDC_ASIS_BOOK
       │
       ▼ (스키마 변환: TITLE→BOOK_TITLE, 1→'Y')
STAGING_ASIS_BOOK
       │
       ▼ (원본 테이블에 반영)
BOOK_INFO
```

### 4.6 코드 매핑 테이블 (03_create_mapping_tables.sql)

**왜 코드 매핑이 필요한가?**
```
ASIS: 카테고리 '01' = 문학
TOBE: 카테고리 'LIT' = 문학

같은 의미지만 코드 체계가 다름
→ 변환 테이블 필요
```

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

**코드 변환 함수:**

```sql
-- 코드 변환 함수
CREATE OR REPLACE FUNCTION FN_CONVERT_CODE(
    p_map_group   VARCHAR2,    -- 매핑 그룹 (예: 'CATEGORY_MAP')
    p_source_sys  VARCHAR2,    -- 원본 시스템 (예: 'TOBE')
    p_source_val  VARCHAR2     -- 원본 코드값 (예: 'LIT')
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
        -- 매핑이 없으면 원본값 그대로 반환
        -- (예: 새로운 코드가 추가됐는데 매핑이 아직 없는 경우)
        RETURN p_source_val;
END;
/
```

**사용 예시:**
```sql
SELECT FN_CONVERT_CODE('CATEGORY_MAP', 'TOBE', 'LIT') FROM DUAL;
-- 결과: '01'

SELECT FN_CONVERT_CODE('STATUS_MAP', 'TOBE', '1') FROM DUAL;
-- 결과: 'Y'
```

### 4.7 WORKER 프로시저 (04_create_procedures.sql)

**WORKER가 하는 일:**
```
1. CDC 테이블에서 미처리 데이터 조회 (PROCESSED_YN = 'N')
2. 무한루프 체크 (해시 비교)
3. 스키마 변환 후 STAGING 테이블에 INSERT
4. STAGING에서 원본 테이블에 INSERT/UPDATE/DELETE
5. 해시 기록 (무한루프 방지)
```

#### 4.7.1 무한루프 방지 함수들

```sql
-- [해시 생성 함수]
-- 데이터를 SHA-256으로 해시화
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
            UTL_RAW.CAST_TO_RAW(
                p_table_name || '|' || p_pk_value || '|' || p_operation || '|' || p_data
            ),
            DBMS_CRYPTO.HASH_SH256
        )
    );
END;
/

-- [무한루프 체크 함수]
-- 최근 5분 이내에 동일 해시가 처리된 적 있는지 확인
CREATE OR REPLACE FUNCTION FN_IS_LOOP(
    p_hash VARCHAR2
) RETURN BOOLEAN
IS
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM CDC_PROCESSED_HASH
    WHERE HASH_VALUE = p_hash
      AND PROCESSED_AT > SYSTIMESTAMP - INTERVAL '5' MINUTE;

    -- 왜 5분인가?
    -- - 너무 짧으면: 네트워크 지연으로 정상 데이터도 무시될 수 있음
    -- - 너무 길면: 해시 테이블이 너무 커짐
    -- - 5분: 적당한 타협점

    RETURN v_count > 0;  -- 있으면 TRUE (무한루프)
END;
/

-- [해시 기록 프로시저]
-- 처리한 데이터의 해시를 저장 (MERGE 사용으로 중복 방지)
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
        UPDATE SET PROCESSED_AT = SYSTIMESTAMP  -- 이미 있으면 시간만 갱신
    WHEN NOT MATCHED THEN
        INSERT (HASH_VALUE, TABLE_NAME, PK_VALUE, PROCESSED_AT)
        VALUES (p_hash, p_table_name, p_pk_value, SYSTIMESTAMP);
    COMMIT;
END;
/

-- [오래된 해시 정리 프로시저]
-- 10분 이상 된 해시 삭제 (테이블 크기 관리)
CREATE OR REPLACE PROCEDURE SP_CLEANUP_HASH
IS
BEGIN
    DELETE FROM CDC_PROCESSED_HASH
    WHERE PROCESSED_AT < SYSTIMESTAMP - INTERVAL '10' MINUTE;
    COMMIT;
END;
/
```

#### 4.7.2 BOOK WORKER 프로시저

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
        WHERE PROCESSED_YN = 'N'       -- 미처리 데이터만
        ORDER BY CDC_SEQ               -- 순서대로 처리 (중요!)
    ) LOOP
        BEGIN
            -- [무한루프 체크]
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                -- 루프 감지됨 → 건너뛰기
                UPDATE CDC_ASIS_BOOK
                SET PROCESSED_YN = 'S',  -- S = Skipped
                    PROCESSED_AT = SYSTIMESTAMP,
                    ERROR_MSG = 'LOOP_BLOCKED'
                WHERE CDC_SEQ = rec.CDC_SEQ;

                -- 로그 기록
                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, CHANGE_HASH)
                VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'LOOP_BLOCKED', rec.CHANGE_HASH);
            ELSE
                -- [정상 처리: STAGING에 삽입 (스키마 변환 적용)]
                INSERT INTO STAGING_ASIS_BOOK (
                    CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE, AUTHOR,
                    CATEGORY, STATUS, REG_DATE, MOD_DATE
                ) VALUES (
                    rec.CDC_SEQ,
                    rec.OPERATION,
                    rec.BOOK_ID,
                    rec.TITLE,                                            -- TITLE → BOOK_TITLE
                    rec.AUTHOR_NAME,                                      -- AUTHOR_NAME → AUTHOR
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
                -- [에러 처리]
                -- 주의: SQLERRM을 변수에 먼저 저장해야 함 (Oracle 제약)
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
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
            -- [OPERATION에 따라 다른 SQL 실행]
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

            -- [해시 기록 (무한루프 방지)]
            -- 내가 처리한 데이터의 해시를 저장
            -- → 나중에 Debezium이 이 변경을 감지해서 TOBE로 보내면
            -- → TOBE에서 다시 이쪽으로 보내올 때 이 해시로 무한루프 감지
            v_hash := FN_GENERATE_HASH('BOOK_INFO', TO_CHAR(rec.BOOK_ID), rec.OPERATION,
                                       rec.BOOK_TITLE || rec.AUTHOR || rec.CATEGORY || rec.STATUS);
            SP_RECORD_HASH(v_hash, 'BOOK_INFO', TO_CHAR(rec.BOOK_ID));

            -- [성공 로그 기록]
            INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS)
            VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'SUCCESS');

            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                -- [INSERT 시 이미 존재하는 경우 → UPDATE로 대체]
                -- 왜? 동기화 타이밍에 따라 먼저 들어온 경우가 있을 수 있음
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

                -- [실패 로그 기록]
                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, ERROR_MSG)
                VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'FAILED', v_err_msg);
                COMMIT;
        END;
    END LOOP;
END;
/
```

#### 4.7.3 전체 WORKER 실행 및 Scheduler Job

```sql
-- [전체 WORKER 실행 프로시저]
CREATE OR REPLACE PROCEDURE SP_RUN_ALL_WORKERS
IS
BEGIN
    SP_CLEANUP_HASH;         -- 오래된 해시 정리
    SP_WORKER_BOOK;          -- BOOK 동기화
    SP_WORKER_MEMBER;        -- MEMBER 동기화
    SP_WORKER_NEW_SERVICE;   -- NEW_SERVICE 동기화
END;
/

-- [Oracle Scheduler Job 생성]
-- 5초마다 SP_RUN_ALL_WORKERS 실행

-- 기존 Job이 있으면 삭제
BEGIN
    DBMS_SCHEDULER.DROP_JOB(job_name => 'JOB_CDC_WORKER', force => TRUE);
EXCEPTION
    WHEN OTHERS THEN NULL;  -- Job이 없으면 무시
END;
/

-- Scheduler Job 생성
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

**왜 5초인가?**
```
- 너무 짧으면 (1초): DB 부하 증가
- 너무 길면 (1분): 실시간 느낌이 안 남
- 5초: 적당한 타협점 (PoC 기준)
- 운영에서는 상황에 따라 조정
```

**Job 상태 확인:**
```sql
SELECT job_name, state, last_start_date, next_run_date
FROM USER_SCHEDULER_JOBS
WHERE job_name = 'JOB_CDC_WORKER';
```

---

## 5. Part 2: Kafka + Debezium 구현 (CDC 파이프라인)

### 5.1 Kafka 개념 이해

**Kafka란?**
```
분산 메시지 큐 시스템
- Producer: 메시지 보내는 쪽 (Debezium)
- Topic: 메시지 저장소 (카테고리별 우편함)
- Consumer: 메시지 받는 쪽 (Sync Service)
```

```
┌─────────────────────────────────────────────────────────────────┐
│                         Kafka 구조                               │
│                                                                  │
│   Producer ─────► Topic ─────► Consumer                         │
│  (Debezium)      (메시지 저장)  (Sync Service)                   │
│                                                                  │
│   Topic 내부:                                                    │
│   ┌──────────────────────────────────────┐                      │
│   │ [msg1] [msg2] [msg3] [msg4] [msg5] ...│ ← 순서대로 쌓임      │
│   └──────────────────────────────────────┘                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 Docker Compose 설정

```yaml
# Zookeeper: Kafka 클러스터 관리
# (Kafka가 자체적으로 관리 못하는 메타데이터를 저장)
zookeeper:
  image: confluentinc/cp-zookeeper:7.5.0
  container_name: zookeeper
  environment:
    ZOOKEEPER_CLIENT_PORT: 2181
    ZOOKEEPER_TICK_TIME: 2000
  networks:
    - cdc-net

# Kafka: 메시지 브로커
kafka:
  image: confluentinc/cp-kafka:7.5.0
  container_name: kafka
  depends_on:
    - zookeeper
  ports:
    - "9092:9092"      # 호스트에서 접속용
    - "29092:29092"    # Docker 내부 접속용
  environment:
    KAFKA_BROKER_ID: 1
    KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181

    # [리스너 설정 - 중요!]
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
    # ↑ 왜 2개인가?
    # - PLAINTEXT://kafka:29092: Docker 내부에서 접속할 때 (컨테이너 이름 사용)
    # - PLAINTEXT_HOST://localhost:9092: 호스트에서 접속할 때

    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
    KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT

    # [복제 설정]
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    # ↑ 1 = 단일 브로커 (PoC용)
    # ↑ 운영에서는 3 이상 권장
  networks:
    - cdc-net

# Kafka Connect: Debezium 실행 환경
kafka-connect:
  image: debezium/connect:2.4
  # ↑ Debezium 공식 이미지
  # ↑ Kafka Connect + Debezium 커넥터가 포함되어 있음

  container_name: kafka-connect
  depends_on:
    - kafka
  ports:
    - "8083:8083"   # REST API 포트
  volumes:
    - ./kafka-connect/libs/ojdbc11.jar:/kafka/libs/ojdbc11.jar
    # ↑ 중요! Oracle JDBC 드라이버
    # ↑ Debezium 이미지에는 포함되어 있지 않음 (라이선스 문제)
    # ↑ Maven Central에서 직접 다운로드 필요
  environment:
    BOOTSTRAP_SERVERS: kafka:29092
    GROUP_ID: cdc-connect-cluster

    # [내부 토픽]
    # Kafka Connect가 자체 상태를 저장하는 토픽
    CONFIG_STORAGE_TOPIC: cdc_connect_configs    # 커넥터 설정 저장
    OFFSET_STORAGE_TOPIC: cdc_connect_offsets    # 어디까지 읽었는지 저장
    STATUS_STORAGE_TOPIC: cdc_connect_statuses   # 커넥터 상태 저장
    CONFIG_STORAGE_REPLICATION_FACTOR: 1
    OFFSET_STORAGE_REPLICATION_FACTOR: 1
    STATUS_STORAGE_REPLICATION_FACTOR: 1

    # [메시지 형식]
    KEY_CONVERTER: org.apache.kafka.connect.json.JsonConverter
    VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
    KEY_CONVERTER_SCHEMAS_ENABLE: "false"    # 스키마 정보 제외 (메시지 간결)
    VALUE_CONVERTER_SCHEMAS_ENABLE: "false"
  networks:
    - cdc-net
```

### 5.3 Debezium 사전 설정 (setup-debezium-oracle.sh)

**Debezium이 Oracle을 읽으려면 사전 설정이 필요합니다:**

1. **ARCHIVELOG 모드 활성화**
2. **Supplemental Logging 활성화**
3. **c##dbzuser (Debezium 전용 사용자) 생성**

```bash
#!/bin/bash
# setup-debezium-oracle.sh

echo "=== Debezium Oracle 설정 시작 ==="

# ==========================================
# 1. ARCHIVELOG 모드 활성화
# ==========================================
# 왜? Redo Log가 덮어쓰기되지 않고 보관되어야 Debezium이 읽을 수 있음

echo "1. ASIS Oracle - ARCHIVELOG 모드 활성화..."
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
SHUTDOWN IMMEDIATE;       -- DB 중지
STARTUP MOUNT;            -- 마운트 모드로 시작 (데이터 파일 열지 않음)
ALTER DATABASE ARCHIVELOG; -- ARCHIVELOG 모드 전환
ALTER DATABASE OPEN;       -- DB 열기
ALTER PLUGGABLE DATABASE ALL OPEN;  -- PDB도 열기
SELECT LOG_MODE FROM V\$DATABASE;   -- 확인
exit;
EOF'

# ==========================================
# 2. Supplemental Logging 활성화
# ==========================================
# 왜? UPDATE 시 변경된 컬럼만 기록되면 전체 행 정보를 모름
#     모든 컬럼이 기록되어야 Debezium이 before/after를 만들 수 있음

echo "2. ASIS Oracle - Supplemental Logging 활성화..."
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
exit;
EOF'

# ==========================================
# 3. Debezium 전용 사용자 생성
# ==========================================
# 왜 c## 접두사?
# - Oracle 12c부터 CDB/PDB 구조
# - c## = Common User (CDB 레벨 사용자)
# - CONTAINER=ALL = 모든 PDB에서 사용 가능

echo "3. ASIS Oracle - c##dbzuser 생성..."
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
-- 사용자 생성
CREATE USER c##dbzuser IDENTIFIED BY dbz
  DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS CONTAINER=ALL;

-- 기본 권한
GRANT CREATE SESSION TO c##dbzuser CONTAINER=ALL;
GRANT SET CONTAINER TO c##dbzuser CONTAINER=ALL;

-- LogMiner 관련 권한
GRANT SELECT ON V_\$DATABASE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOG_HISTORY TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_LOGS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_CONTENTS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_PARAMETERS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGFILE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$ARCHIVED_LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$ARCHIVE_DEST_STATUS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$TRANSACTION TO c##dbzuser CONTAINER=ALL;

-- LogMiner 프로시저 실행 권한
GRANT EXECUTE ON DBMS_LOGMNR TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR_D TO c##dbzuser CONTAINER=ALL;

-- 테이블 조회 권한
GRANT SELECT ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT FLASHBACK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY TRANSACTION TO c##dbzuser CONTAINER=ALL;

-- LogMining 권한 (Oracle 12c+)
GRANT LOGMINING TO c##dbzuser CONTAINER=ALL;

-- 스냅샷용 (초기 전체 데이터 읽기)
GRANT CREATE TABLE TO c##dbzuser CONTAINER=ALL;
GRANT LOCK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT CREATE SEQUENCE TO c##dbzuser CONTAINER=ALL;
exit;
EOF'

# TOBE Oracle도 동일하게 설정...
echo "4. TOBE Oracle - 동일 설정..."
# (TOBE 설정 생략 - 위와 동일)

echo "=== Debezium Oracle 설정 완료 ==="
```

### 5.4 Debezium 커넥터 등록 (register-connectors.sh)

```bash
#!/bin/bash
# register-connectors.sh

CONNECT_URL="http://localhost:8083"

echo "=== Debezium 커넥터 등록 ==="

# Kafka Connect 준비 확인
echo "1. Kafka Connect 준비 확인..."
until curl -s ${CONNECT_URL}/ > /dev/null; do
    echo "   Kafka Connect 대기중..."
    sleep 5
done
echo "   Kafka Connect 준비 완료!"

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
echo ""
echo "4. 커넥터 상태 확인..."
echo "--- ASIS Connector ---"
curl -s ${CONNECT_URL}/connectors/asis-connector/status | python -m json.tool
echo ""
echo "--- TOBE Connector ---"
curl -s ${CONNECT_URL}/connectors/tobe-connector/status | python -m json.tool
```

**커넥터 설정 상세 설명:**

| 설정 | 값 | 설명 |
|------|---|------|
| `connector.class` | `io.debezium.connector.oracle.OracleConnector` | Oracle용 Debezium 커넥터 |
| `database.hostname` | `asis-oracle` | Docker 컨테이너명 (Docker 네트워크 내에서) |
| `database.dbname` | `XE` | CDB 이름 (Oracle XE 기본값) |
| `database.pdb.name` | `XEPDB1` | PDB 이름 (실제 데이터가 있는 곳) |
| `topic.prefix` | `asis` | Kafka 토픽 접두사 |
| `table.include.list` | `ASIS_USER.BOOK_INFO,...` | 감시할 테이블 목록 |
| `log.mining.strategy` | `online_catalog` | LogMiner 전략 (온라인 딕셔너리 사용) |

**생성되는 Kafka 토픽:**
```
토픽 이름 규칙: {topic.prefix}.{스키마}.{테이블}

asis.ASIS_USER.BOOK_INFO      ← BOOK_INFO 테이블 변경
asis.ASIS_USER.MEMBER_INFO    ← MEMBER_INFO 테이블 변경
asis.ASIS_USER.LEGACY_CODE    ← LEGACY_CODE 테이블 변경
tobe.TOBE_USER.TB_BOOK        ← TB_BOOK 테이블 변경
tobe.TOBE_USER.TB_MEMBER      ← TB_MEMBER 테이블 변경
tobe.TOBE_USER.TB_NEW_SERVICE ← TB_NEW_SERVICE 테이블 변경
```

### 5.5 Debezium 메시지 구조

**Debezium이 발행하는 메시지 예시:**

```json
{
  "schema": { ... },      // 스키마 정보 (우리는 제외 설정)

  "payload": {
    "op": "u",            // 작업 유형
                          // c = INSERT (create)
                          // u = UPDATE
                          // d = DELETE
                          // r = 스냅샷 읽기 (read)

    "before": {           // 변경 전 데이터 (UPDATE, DELETE 시)
      "BOOK_ID": {"scale": 0, "value": "AQ=="},
      "BOOK_TITLE": "기존제목",
      ...
    },

    "after": {            // 변경 후 데이터 (INSERT, UPDATE 시)
      "BOOK_ID": {"scale": 0, "value": "AQ=="},
      "BOOK_TITLE": "새제목",
      ...
    },

    "source": {           // 원본 정보
      "version": "2.4.2.Final",
      "connector": "oracle",
      "name": "asis",
      "ts_ms": 1768284010000,    // 변경 발생 시간 (epoch ms)
      "db": "XEPDB1",
      "schema": "ASIS_USER",
      "table": "BOOK_INFO",
      "scn": "3009564"           // System Change Number
    },

    "ts_ms": 1768284013301       // Debezium 처리 시간
  }
}
```

**NUMBER 타입이 왜 이상하게 보이나?**

```json
"BOOK_ID": {"scale": 0, "value": "AQ=="}
```

- Oracle NUMBER는 최대 38자리까지 가능
- JavaScript/Java Long은 15자리만 정확
- → Debezium은 Base64로 인코딩하여 정밀도 보장

```
디코딩 과정:
"AQ==" → Base64 decode → bytes → BigInteger → 1
```

---

## 6. Part 3: TOBE Oracle 구현 (신규 시스템)

TOBE는 ASIS의 **반대 방향**입니다.

- CDC 테이블: ASIS에서 온 데이터 저장 (ASIS 스키마)
- STAGING 테이블: TOBE 스키마로 변환
- 코드 매핑: ASIS 코드 → TOBE 코드

### 6.1 TOBE 스키마 특징

| 항목 | ASIS | TOBE |
|------|------|------|
| 도서 제목 | `BOOK_TITLE` VARCHAR2(200) | `TITLE` VARCHAR2(500) |
| 저자 | `AUTHOR` VARCHAR2(100) | `AUTHOR_NAME` VARCHAR2(200) |
| 상태 | `'Y'` / `'N'` | `1` / `0` (NUMBER) |
| 카테고리 | `'01'`, `'02'`, `'03'` | `'LIT'`, `'SCI'`, `'HIS'` |
| 회원타입 | `'A'`, `'B'`, `'C'` | `'ADMIN'`, `'USER'`, `'GUEST'` |
| 날짜 | `DATE` | `TIMESTAMP` |

### 6.2 TOBE 코드 매핑 (역방향)

```sql
-- 카테고리 코드 매핑 (ASIS → TOBE)
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '01', 'TOBE', 'LIT', '문학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '02', 'TOBE', 'SCI', '과학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '03', 'TOBE', 'HIS', '역사');

-- 상태 코드 매핑 (ASIS → TOBE)
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'ASIS', 'Y', 'TOBE', '1', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'ASIS', 'N', 'TOBE', '0', '비활성');
```

### 6.3 TOBE WORKER 스키마 변환 (핵심 부분)

```sql
-- TOBE WORKER에서의 스키마 변환 (ASIS → TOBE)
INSERT INTO STAGING_TOBE_BOOK (
    CDC_SEQ, OPERATION, BOOK_ID, TITLE, AUTHOR_NAME,
    CATEGORY_CD, IS_ACTIVE, CREATED_AT, UPDATED_AT
) VALUES (
    rec.CDC_SEQ,
    rec.OPERATION,
    rec.BOOK_ID,
    rec.BOOK_TITLE,                                           -- BOOK_TITLE → TITLE
    rec.AUTHOR,                                               -- AUTHOR → AUTHOR_NAME
    FN_CONVERT_CODE('CATEGORY_MAP', 'ASIS', rec.CATEGORY),   -- '01' → 'LIT'
    CASE rec.STATUS WHEN 'Y' THEN 1 ELSE 0 END,              -- 'Y' → 1, 'N' → 0
    NVL(CAST(rec.REG_DATE AS TIMESTAMP), SYSTIMESTAMP),      -- DATE → TIMESTAMP
    CAST(rec.MOD_DATE AS TIMESTAMP)
);
```

---

## 7. Part 4: Java Sync Service 구현 (중계 서비스)

### 7.1 Sync Service의 역할

```
역할: Kafka 메시지를 받아서 상대 DB의 CDC 테이블에 INSERT

asis 토픽 메시지 → TOBE DB CDC 테이블에 INSERT
tobe 토픽 메시지 → ASIS DB CDC 테이블에 INSERT
```

**왜 직접 원본 테이블에 안 넣고 CDC 테이블에 넣나?**
```
1. 스키마 변환이 복잡함 → DB 프로시저(WORKER)에서 처리
2. 트랜잭션 관리 → DB에서 하는 게 자연스러움
3. 에러 추적 → CDC 테이블에 기록이 남음
4. 무한루프 방지 → WORKER에서 해시 체크
```

### 7.2 프로젝트 구조

```
sync-service-java/
├── pom.xml                          # Maven 설정
├── Dockerfile                       # Docker 빌드 설정
└── src/main/java/com/cdc/sync/
    ├── SyncServiceApplication.java  # Spring Boot 메인
    ├── config/
    │   ├── DataSourceConfig.java    # DB 연결 설정 (2개)
    │   └── KafkaConfig.java         # Kafka 설정
    ├── consumer/
    │   └── CdcKafkaConsumer.java    # Kafka 메시지 수신
    ├── service/
    │   └── CdcSyncService.java      # 비즈니스 로직
    └── model/
        └── CdcEvent.java            # 데이터 모델
```

### 7.3 DataSourceConfig.java - 왜 DataSource가 2개인가?

```java
@Configuration
public class DataSourceConfig {

    /**
     * 왜 DataSource가 2개인가?
     *
     * ASIS 이벤트 수신 → TOBE DB에 INSERT (tobeJdbcTemplate 사용)
     * TOBE 이벤트 수신 → ASIS DB에 INSERT (asisJdbcTemplate 사용)
     *
     * 즉, 양방향 동기화를 위해 양쪽 DB에 모두 접속해야 함
     */

    @Bean(name = "asisDataSource")
    @ConfigurationProperties(prefix = "asis.datasource")
    public DataSource asisDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .build();
    }
    // ↑ @ConfigurationProperties: application.yml의 asis.datasource.* 값을 자동 바인딩
    // ↑ HikariDataSource: Spring Boot 기본 커넥션 풀 (고성능)

    @Bean(name = "asisJdbcTemplate")
    public JdbcTemplate asisJdbcTemplate(
            @Qualifier("asisDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
    // ↑ @Qualifier: 같은 타입의 빈이 여러 개일 때 특정 빈 지정

    @Bean(name = "tobeDataSource")
    @Primary  // ← 기본 DataSource로 지정
    @ConfigurationProperties(prefix = "tobe.datasource")
    public DataSource tobeDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .build();
    }
    // ↑ @Primary: DataSource 타입 주입 시 기본값

    @Bean(name = "tobeJdbcTemplate")
    public JdbcTemplate tobeJdbcTemplate(
            @Qualifier("tobeDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
```

### 7.4 CdcKafkaConsumer.java - 왜 @KafkaListener인가?

```java
@Component
public class CdcKafkaConsumer {

    private final CdcSyncService syncService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 왜 @KafkaListener를 사용하나?
     *
     * 대안 1: 직접 KafkaConsumer API 사용
     *   while (true) {
     *       ConsumerRecords records = consumer.poll(...);
     *       ...
     *   }
     *   → 복잡함, 스레드 관리 직접 해야 함
     *
     * 대안 2: @KafkaListener (우리가 선택)
     *   → 간단함, Spring이 스레드/에러 관리
     */

    // ASIS BOOK_INFO 테이블 변경 이벤트 처리
    @KafkaListener(topics = "asis.ASIS_USER.BOOK_INFO", groupId = "cdc-sync-service")
    public void consumeAsisBookInfo(ConsumerRecord<String, String> record) {
        processAsisEvent(record, "CDC_TOBE_BOOK");
    }
    // ↑ topics: 구독할 Kafka 토픽
    // ↑ groupId: Consumer Group ID (같은 그룹끼리 메시지 분담)

    // TOBE TB_BOOK 테이블 변경 이벤트 처리
    @KafkaListener(topics = "tobe.TOBE_USER.TB_BOOK", groupId = "cdc-sync-service")
    public void consumeTobeBook(ConsumerRecord<String, String> record) {
        processTobeEvent(record, "CDC_ASIS_BOOK");
    }

    /**
     * 왜 토픽마다 별도 메서드인가?
     *
     * 이론적으로 하나로 합칠 수 있음:
     *   @KafkaListener(topics = {"asis...", "tobe..."})
     *   public void consumeAll(...) { ... }
     *
     * 하지만 분리한 이유:
     * 1. 코드가 명확함 (어떤 토픽 → 어떤 테이블)
     * 2. 테이블별로 다른 처리 필요할 수 있음
     * 3. 에러 발생 시 어디서 문제인지 바로 알 수 있음
     */

    private void processAsisEvent(ConsumerRecord<String, String> record, String targetTable) {
        try {
            CdcEvent event = parseDebeziumMessage(record.value());
            if (event != null) {
                syncService.syncAsisToTobe(event, targetTable);
            }
        } catch (Exception e) {
            log.error("Failed to process ASIS event: {}", e.getMessage(), e);
        }
    }

    /**
     * Debezium 메시지 파싱
     */
    private CdcEvent parseDebeziumMessage(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        try {
            // JSON → Map 변환
            Map<String, Object> json = objectMapper.readValue(
                    message, new TypeReference<Map<String, Object>>() {});

            // payload 추출 (schema 제외)
            Map<String, Object> payload = json.containsKey("payload")
                    ? (Map<String, Object>) json.get("payload")
                    : json;

            CdcEvent event = new CdcEvent();

            // 작업 유형 변환 (c→INSERT, u→UPDATE, d→DELETE)
            String op = (String) payload.get("op");
            event.setOperation(CdcEvent.convertOperation(op));

            // before/after 데이터
            event.setBefore((Map<String, Object>) payload.get("before"));
            event.setAfter((Map<String, Object>) payload.get("after"));

            // 해시 생성 (무한루프 방지용)
            Map<String, Object> data = event.getData();
            if (data != null) {
                event.setChangeHash(CdcSyncService.generateHash(data));
            }

            return event;
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Debezium message: {}", e.getMessage());
            return null;
        }
    }
}
```

### 7.5 CdcSyncService.java - CDC 테이블에 INSERT

```java
@Service
public class CdcSyncService {

    private final JdbcTemplate asisJdbcTemplate;
    private final JdbcTemplate tobeJdbcTemplate;

    public CdcSyncService(
            @Qualifier("asisJdbcTemplate") JdbcTemplate asisJdbcTemplate,
            @Qualifier("tobeJdbcTemplate") JdbcTemplate tobeJdbcTemplate) {
        this.asisJdbcTemplate = asisJdbcTemplate;
        this.tobeJdbcTemplate = tobeJdbcTemplate;
    }

    // ASIS → TOBE 동기화
    public void syncAsisToTobe(CdcEvent event, String targetTable) {
        insertToCdcTable(tobeJdbcTemplate, event, targetTable, "ASIS->TOBE");
    }

    // TOBE → ASIS 동기화
    public void syncTobeToAsis(CdcEvent event, String targetTable) {
        insertToCdcTable(asisJdbcTemplate, event, targetTable, "TOBE->ASIS");
    }

    /**
     * CDC 테이블에 INSERT
     *
     * 왜 동적 SQL인가?
     * - 테이블마다 컬럼이 다름
     * - 새 테이블 추가 시 코드 수정 최소화
     */
    private void insertToCdcTable(JdbcTemplate jdbc, CdcEvent event,
                                  String targetTable, String direction) {
        try {
            Map<String, Object> data = event.getData();
            if (data == null || data.isEmpty()) {
                return;
            }

            List<String> columns = new ArrayList<>();
            List<Object> values = new ArrayList<>();

            // 메타 컬럼
            columns.add("OPERATION");
            values.add(event.getOperation());

            columns.add("SOURCE_TIMESTAMP");
            values.add(event.getSourceTimestamp());

            columns.add("CHANGE_HASH");
            values.add(event.getChangeHash());

            columns.add("PROCESSED_YN");
            values.add("N");  // 미처리 상태

            // 데이터 컬럼
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Debezium NUMBER 타입 디코딩
                // { "scale": 0, "value": "AQ==" } → 1
                if (value instanceof Map) {
                    value = decodeDebeziumNumber((Map<String, Object>) value);
                }

                columns.add(key.toUpperCase());
                values.add(value);
            }

            // 동적 SQL 생성
            String sql = String.format(
                "INSERT INTO %s (%s) VALUES (%s)",
                targetTable,
                String.join(", ", columns),
                columns.stream().map(c -> "?").collect(Collectors.joining(", "))
            );

            jdbc.update(sql, values.toArray());

            log.info("[{}] Inserted into {}: {}", direction, targetTable, event.getOperation());

        } catch (Exception e) {
            log.error("[{}] Failed to insert: {}", direction, e.getMessage(), e);
        }
    }

    /**
     * Debezium NUMBER 타입 디코딩
     *
     * Debezium은 Oracle NUMBER를 다음 형식으로 전송:
     * { "scale": 0, "value": "AQ==" }
     *
     * value는 Base64로 인코딩된 BigInteger
     */
    private Object decodeDebeziumNumber(Map<String, Object> complexValue) {
        try {
            Object valueObj = complexValue.get("value");
            if (valueObj instanceof String base64Value) {
                byte[] bytes = Base64.getDecoder().decode(base64Value);
                BigInteger bigInt = new BigInteger(bytes);

                Object scaleObj = complexValue.get("scale");
                int scale = scaleObj != null ? ((Number) scaleObj).intValue() : 0;

                if (scale == 0) {
                    return bigInt.longValue();  // 정수
                } else {
                    return new BigDecimal(bigInt, scale);  // 소수
                }
            }
        } catch (Exception e) {
            log.warn("Failed to decode: {}", e.getMessage());
        }
        return complexValue;
    }

    /**
     * 해시 생성 (SHA-256)
     *
     * 무한루프 방지용:
     * 같은 데이터 → 항상 같은 해시
     * 다른 데이터 → 다른 해시
     */
    public static String generateHash(Map<String, Object> data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

### 7.6 application.yml 설정

```yaml
server:
  port: 8080

spring:
  application:
    name: cdc-sync-service

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:29092}
    # ↑ ${환경변수:기본값} 형식
    # ↑ docker-compose에서 환경변수로 오버라이드 가능

    consumer:
      group-id: cdc-sync-service
      # ↑ Consumer Group ID
      # ↑ 같은 그룹의 Consumer끼리 메시지를 나눠서 처리

      auto-offset-reset: earliest
      # ↑ earliest: 토픽의 처음부터 읽기 (놓친 메시지 없음)
      # ↑ latest: 현재 시점부터 읽기 (과거 메시지 무시)

      enable-auto-commit: true
      # ↑ 메시지 처리 후 자동으로 오프셋 커밋

      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

# ASIS DB
asis:
  datasource:
    jdbc-url: jdbc:oracle:thin:@${ASIS_DB_HOST:asis-oracle}:${ASIS_DB_PORT:1521}/${ASIS_DB_SERVICE:XEPDB1}
    # ↑ 주의: HikariCP 사용 시 'url'이 아니라 'jdbc-url'

    username: ${ASIS_DB_USER:asis_user}
    password: ${ASIS_DB_PASSWORD:asis123}
    driver-class-name: oracle.jdbc.OracleDriver
    pool-name: ASIS-Pool
    maximum-pool-size: 5
    minimum-idle: 2
    connection-timeout: 30000

# TOBE DB
tobe:
  datasource:
    jdbc-url: jdbc:oracle:thin:@${TOBE_DB_HOST:tobe-oracle}:${TOBE_DB_PORT:1521}/${TOBE_DB_SERVICE:XEPDB1}
    username: ${TOBE_DB_USER:tobe_user}
    password: ${TOBE_DB_PASSWORD:tobe123}
    driver-class-name: oracle.jdbc.OracleDriver
    pool-name: TOBE-Pool
    maximum-pool-size: 5
    minimum-idle: 2
    connection-timeout: 30000

# 로깅
logging:
  level:
    root: INFO
    com.cdc.sync: DEBUG
    org.springframework.kafka: INFO
    org.apache.kafka: WARN

# Actuator (Health Check)
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

---

## 8. Part 5: 전체 실행 및 테스트

### 8.1 사전 준비

1. **Docker Desktop 설치 및 실행**
2. **Oracle JDBC 드라이버 다운로드**
   - https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html
   - `ojdbc11.jar` 파일을 `poc/kafka-connect/libs/` 폴더에 복사

### 8.2 실행 순서

```bash
# 1. poc 폴더로 이동
cd C:\Devel\cdc-sync-poc\poc

# 2. Docker 컨테이너 시작
docker-compose up -d

# 3. Oracle 완전 기동 대기 (약 2-3분)
# 로그 확인
docker logs -f asis-oracle
# "DATABASE IS READY TO USE!" 메시지가 나올 때까지 대기

# 4. 모든 컨테이너 상태 확인
docker-compose ps
# 모든 컨테이너가 healthy 또는 running 상태인지 확인

# 5. Debezium 설정 (ARCHIVELOG, c##dbzuser)
# Windows에서는 Git Bash 또는 WSL에서 실행
bash scripts/setup-debezium-oracle.sh

# 6. Kafka Connect 준비 확인
curl http://localhost:8083/
# 정상이면 {"version":"...","commit":"..."} 반환

# 7. Debezium 커넥터 등록
bash scripts/register-connectors.sh

# 8. 커넥터 상태 확인
curl http://localhost:8083/connectors/asis-connector/status
curl http://localhost:8083/connectors/tobe-connector/status
# "state": "RUNNING" 확인
```

### 8.3 ASIS → TOBE 테스트

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
echo "15초 대기..."
sleep 15

# 3. TOBE에서 확인
docker exec tobe-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER SESSION SET CONTAINER = XEPDB1;
SELECT BOOK_ID, TITLE, AUTHOR_NAME, CATEGORY_CD, IS_ACTIVE
FROM TOBE_USER.TB_BOOK
WHERE BOOK_ID = 100;
exit;
EOF'
```

**예상 결과:**
```
   BOOK_ID TITLE           AUTHOR_NAME    CATEGORY_CD  IS_ACTIVE
---------- --------------- -------------- ------------ ----------
       100 테스트도서ASIS    테스트저자     LIT                 1
```
- `BOOK_TITLE` → `TITLE` (컬럼명 변환)
- `'01'` → `'LIT'` (카테고리 코드 변환)
- `'Y'` → `1` (상태값 변환)

### 8.4 TOBE → ASIS 테스트

```bash
# 1. TOBE에 데이터 INSERT
docker exec tobe-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER SESSION SET CONTAINER = XEPDB1;
INSERT INTO TOBE_USER.TB_BOOK (BOOK_ID, TITLE, AUTHOR_NAME, CATEGORY_CD, IS_ACTIVE, CREATED_AT)
VALUES (200, '\''테스트도서TOBE'\'', '\''TOBE저자'\'', '\''SCI'\'', 1, SYSTIMESTAMP);
COMMIT;
exit;
EOF'

# 2. 15초 대기
sleep 15

# 3. ASIS에서 확인
docker exec asis-oracle bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER SESSION SET CONTAINER = XEPDB1;
SELECT BOOK_ID, BOOK_TITLE, AUTHOR, CATEGORY, STATUS
FROM ASIS_USER.BOOK_INFO
WHERE BOOK_ID = 200;
exit;
EOF'
```

**예상 결과:**
```
   BOOK_ID BOOK_TITLE      AUTHOR         CATEGORY STATUS
---------- --------------- -------------- -------- ------
       200 테스트도서TOBE    TOBE저자       02       Y
```
- `TITLE` → `BOOK_TITLE` (컬럼명 역변환)
- `'SCI'` → `'02'` (카테고리 코드 역변환)
- `1` → `'Y'` (상태값 역변환)

### 8.5 모니터링

**CDC 동기화 시뮬레이터**: http://localhost:8082/simulator
- **테스트 실행**: 모든 테이블(BOOK, MEMBER, LEGACY_CODE, NEW_SERVICE) CUD 테스트
- **동기화 흐름**: 원본→CDC→STAGING→대상 4단계 추적
- **이력/통계**: CDC_SYNC_LOG 조회, 성공/실패 통계

```
지원 테이블:
- BOOK (도서): 양방향 동기화
- MEMBER (회원): 양방향 동기화
- LEGACY_CODE (레거시코드): ASIS→TOBE 단방향
- NEW_SERVICE (신규서비스): TOBE→ASIS 단방향
```

**Kafka UI**: http://localhost:8081
- 토픽 목록 확인
- 메시지 내용 확인

**Sync Service Health**: http://localhost:8082/actuator/health
```json
{
  "status": "UP",
  "components": {
    "kafka": { "status": "UP" }
  }
}
```

**시뮬레이터 API 예시:**
```bash
# BOOK 테이블 조회
curl http://localhost:8082/api/simulator/BOOK/asis/data
curl http://localhost:8082/api/simulator/BOOK/tobe/data

# INSERT 테스트
curl -X POST http://localhost:8082/api/simulator/BOOK/asis/insert \
  -H "Content-Type: application/json" \
  -d '{"BOOK_TITLE": "테스트도서"}'

# 동기화 로그 조회
curl "http://localhost:8082/api/simulator/sync-log?table=BOOK"
```

**동기화 로그 확인 (SQL):**
```sql
-- ASIS 동기화 로그
SELECT * FROM ASIS_USER.CDC_SYNC_LOG ORDER BY LOG_SEQ DESC;

-- TOBE 동기화 로그
SELECT * FROM TOBE_USER.CDC_SYNC_LOG ORDER BY LOG_SEQ DESC;
```

---

## 9. Part 6: 트러블슈팅 & FAQ

### 9.1 포트 충돌 (Windows)

**증상**: `docker-compose up` 시 포트 바인딩 실패
```
Error: bind: An attempt was made to access a socket...
```

**원인**: Windows Hyper-V가 특정 포트 범위를 예약

**해결**: docker-compose.yml에서 포트 변경
```yaml
ports:
  - "15210:1521"  # 1521 대신 15210 사용
```

### 9.2 커넥터 FAILED 상태

**증상**:
```bash
curl http://localhost:8083/connectors/asis-connector/status
# "state": "FAILED"
```

**확인 방법**:
```bash
curl -s http://localhost:8083/connectors/asis-connector/status | python -m json.tool
# trace 필드에서 에러 원인 확인
```

**흔한 원인 및 해결:**

| 원인 | 해결 |
|------|------|
| ARCHIVELOG 미설정 | `setup-debezium-oracle.sh` 재실행 |
| c##dbzuser 없음 | 사용자 생성 스크립트 확인 |
| ojdbc11.jar 없음 | 파일 존재 여부 확인 |
| Oracle 미기동 | `docker logs asis-oracle` 확인 |

### 9.3 프로시저 INVALID

**증상**:
```sql
SELECT object_name, status FROM user_objects WHERE object_type = 'PROCEDURE';
-- STATUS: INVALID
```

**원인**: SQLERRM을 SQL 문장 내에서 직접 사용

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

**확인**:
```sql
SELECT * FROM CDC_ASIS_BOOK WHERE PROCESSED_YN = 'S';
-- S = Skipped (무한루프로 건너뜀)
```

**원인**: 해시 기록이 안 됨

**해결**: `SP_RECORD_HASH` 프로시저 정상 동작 확인

### 9.5 Kafka Connect 크래시

**증상**: Kafka Connect 컨테이너가 재시작됨

**확인**:
```bash
docker logs kafka-connect
# Zookeeper 연결 실패 등 에러 메시지 확인
```

**해결**:
```bash
docker restart kafka kafka-connect
```

### 9.6 FAQ

**Q: 왜 Java로 만들었나요?**

A: 팀 기술 스택 (대부분 Java 개발자), 운영 환경 호환성 (기존 시스템이 Java/Spring), 성숙한 라이브러리 생태계

**Q: Kafka 대신 다른 거 쓰면 안 되나요?**

A: 가능하지만 Debezium이 Kafka 기반이라 가장 자연스러운 조합. RabbitMQ, ActiveMQ 등도 가능하지만 추가 어댑터 필요

**Q: 운영에서 5초 간격이면 충분한가요?**

A: PoC 기준. 운영에서는 상황에 따라 조정. 더 빠른 동기화가 필요하면 간격 줄이거나 이벤트 기반으로 변경

---

## 10. 마치며: 핵심 정리

### 10.1 이 프로젝트가 한 일

```
문제: ASIS(레거시)와 TOBE(신규) 시스템이 동시 운영되는데 데이터 동기화 필요
제약: 기존 테이블/소스 수정 불가

해결:
1. Debezium으로 변경 감지 (Redo Log 기반, 무침입적)
2. Kafka로 이벤트 전달 (안정적인 메시지 브로커)
3. Sync Service로 상대 DB에 전달 (Java Spring Boot)
4. CDC 테이블에 임시 저장 (스키마 변환 분리)
5. WORKER 프로시저로 원본 반영 (트랜잭션 관리)
6. 해시 기반 무한루프 방지 (양방향 동기화 핵심)
```

### 10.2 기술 스택 요약

| 컴포넌트 | 기술 | 역할 |
|---------|------|------|
| DBMS | Oracle XE 21c | 레거시/신규 데이터 저장 |
| CDC | Debezium 2.4 | 변경 감지 및 이벤트 발행 |
| 메시지 브로커 | Apache Kafka | 이벤트 전달 및 보관 |
| 중계 서비스 | Spring Boot | Kafka → DB 전달 |
| 스키마 변환 | PL/SQL 프로시저 | 컬럼/코드 매핑 |
| 자동화 | Oracle Scheduler | 주기적 WORKER 실행 |
| 컨테이너 | Docker Compose | 개발 환경 구성 |

### 10.3 블로그 시리즈 구성 제안

이 문서를 기반으로 블로그를 작성한다면:

1. **[1편] CDC란 무엇인가? - 실시간 데이터 동기화의 필요성**
2. **[2편] Debezium + Oracle 설정 - LogMiner 기반 CDC**
3. **[3편] Kafka 기초 - 메시지 브로커 이해하기**
4. **[4편] Spring Boot Kafka Consumer 구현**
5. **[5편] 양방향 동기화와 무한루프 방지**
6. **[6편] 스키마 변환 전략 - WORKER 프로시저 설계**
7. **[7편] Docker Compose로 전체 환경 구성**
8. **[8편] 트러블슈팅 - 실제 겪은 문제들**

---

> **문서 작성**: 2025년 1월
> **마지막 테스트 환경**: Windows 11, Docker Desktop, Oracle XE 21c
> **참고 자료**:
> - [Debezium 공식 문서](https://debezium.io/documentation/)
> - [Debezium Oracle Tutorial](https://debezium.io/blog/2022/09/30/debezium-oracle-series-part-1/)
> - [44BITS 기술 블로그 작성법](https://www.44bits.io/ko/post/8-suggestions-for-tech-programming-blog)
