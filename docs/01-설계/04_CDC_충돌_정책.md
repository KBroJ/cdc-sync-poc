# CDC 충돌 정책 정의

> 최종 수정일: 2025-01-13
> 문서 번호: 04
> 이전 문서: `03_CDC_동기화_케이스_분류.md`
> 다음 문서: `05_CDC_에러코드_체계.md`

---

## 1. 충돌 정책 개요

### 1.1 정책 유형

| 정책 코드 | 정책명 | 설명 |
|-----------|--------|------|
| LWW | Last Writer Wins | 타임스탬프가 늦은 변경 승리 |
| ASIS_PRIORITY | ASIS 우선 | 항상 ASIS 값 적용 |
| TOBE_PRIORITY | TOBE 우선 | 항상 TOBE 값 적용 |
| FIELD_MERGE | 필드 병합 | 컬럼별로 병합 (다른 컬럼일 때) |
| MANUAL | 수동 해결 | 관리자가 화면에서 선택 |
| DELETE_PRIORITY | 삭제 우선 | UPDATE vs DELETE 시 삭제 우선 |
| UPDATE_PRIORITY | 수정 우선 | UPDATE vs DELETE 시 수정 우선 |

### 1.2 스키마 차이 고려사항

```
┌─────────────────────────────────────────────────────────────────┐
│  스키마가 다르므로 정책 적용 시 매핑 정보 참조 필요              │
│                                                                 │
│  예: ASIS.BOOK_INFO.BOOK_TITLE ↔ TOBE.TB_BOOK.TITLE            │
│  → 같은 데이터이므로 충돌 정책은 하나로 관리                     │
│  → 정책 테이블에서 매핑 ID로 연결                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 정책 적용 테이블

### 2.1 테이블 레벨 정책 (매핑 ID 기반)

```sql
CREATE TABLE SYNC_CONFLICT_POLICY (
    POLICY_ID           NUMBER PRIMARY KEY,
    MAPPING_ID          NUMBER NOT NULL,        -- 테이블 매핑 ID (FK)
    ASIS_COLUMN         VARCHAR2(100),          -- ASIS 컬럼명
    TOBE_COLUMN         VARCHAR2(100),          -- TOBE 컬럼명
    CONFLICT_TYPE       VARCHAR2(20),           -- UPD_UPD, UPD_DEL, INS_INS, ALL
    POLICY_TYPE         VARCHAR2(20),           -- LWW, ASIS_PRIORITY, TOBE_PRIORITY 등
    DESCRIPTION         VARCHAR2(500),
    IS_ACTIVE           CHAR(1) DEFAULT 'Y',
    CREATED_AT          TIMESTAMP DEFAULT SYSTIMESTAMP,
    UPDATED_AT          TIMESTAMP,
    FOREIGN KEY (MAPPING_ID) REFERENCES SYNC_TABLE_MAPPING(MAPPING_ID)
);

-- 컬럼이 NULL이면 테이블 전체 기본 정책
-- ASIS_COLUMN과 TOBE_COLUMN 둘 다 지정하여 매핑 관계 명확히 함
```

### 2.2 예시 데이터

```sql
-- 테이블 매핑 (01 문서 참조)
-- MAPPING_ID = 1: ASIS.BOOK_INFO ↔ TOBE.TB_BOOK

-- 테이블 전체 기본 정책
INSERT INTO SYNC_CONFLICT_POLICY (POLICY_ID, MAPPING_ID, ASIS_COLUMN, TOBE_COLUMN, CONFLICT_TYPE, POLICY_TYPE, DESCRIPTION)
VALUES (1, 1, NULL, NULL, 'ALL', 'MANUAL', 'BOOK 테이블 기본 정책: 수동 해결');

-- 제목 컬럼: ASIS 우선
INSERT INTO SYNC_CONFLICT_POLICY (POLICY_ID, MAPPING_ID, ASIS_COLUMN, TOBE_COLUMN, CONFLICT_TYPE, POLICY_TYPE, DESCRIPTION)
VALUES (2, 1, 'BOOK_TITLE', 'TITLE', 'UPD_UPD', 'ASIS_PRIORITY', '도서 제목은 ASIS가 원본');

-- 상태 컬럼: TOBE 우선
INSERT INTO SYNC_CONFLICT_POLICY (POLICY_ID, MAPPING_ID, ASIS_COLUMN, TOBE_COLUMN, CONFLICT_TYPE, POLICY_TYPE, DESCRIPTION)
VALUES (3, 1, 'STATUS', 'IS_ACTIVE', 'UPD_UPD', 'TOBE_PRIORITY', '대출 상태는 TOBE가 관리');

-- 설명 컬럼: LWW
INSERT INTO SYNC_CONFLICT_POLICY (POLICY_ID, MAPPING_ID, ASIS_COLUMN, TOBE_COLUMN, CONFLICT_TYPE, POLICY_TYPE, DESCRIPTION)
VALUES (4, 1, 'DESCRIPTION', 'DESCRIPTION', 'UPD_UPD', 'LWW', '설명은 최신 값 우선');
```

---

## 3. 충돌 유형별 정책 적용

### 3.1 UPDATE vs UPDATE (같은 컬럼) - 스키마 매핑 적용

**충돌 상황**
```
ASIS: UPDATE BOOK_INFO SET BOOK_TITLE = 'A' WHERE BOOK_ID = 1
TOBE: UPDATE TB_BOOK SET TITLE = 'B' WHERE BOOK_ID = 1

→ BOOK_TITLE과 TITLE은 매핑된 같은 컬럼 → 충돌!
```

**정책 조회 로직 (매핑 ID 기반)**
```sql
FUNCTION GET_CONFLICT_POLICY(
    p_mapping_id        NUMBER,
    p_asis_column       VARCHAR2,
    p_tobe_column       VARCHAR2,
    p_conflict_type     VARCHAR2
) RETURN VARCHAR2 AS
    v_policy VARCHAR2(20);
BEGIN
    -- 1차: 컬럼 레벨 정책 조회
    BEGIN
        SELECT POLICY_TYPE INTO v_policy
        FROM SYNC_CONFLICT_POLICY
        WHERE MAPPING_ID = p_mapping_id
          AND ASIS_COLUMN = p_asis_column
          AND TOBE_COLUMN = p_tobe_column
          AND CONFLICT_TYPE IN (p_conflict_type, 'ALL')
          AND IS_ACTIVE = 'Y'
        ORDER BY CASE WHEN CONFLICT_TYPE = p_conflict_type THEN 1 ELSE 2 END
        FETCH FIRST 1 ROW ONLY;
        RETURN v_policy;
    EXCEPTION WHEN NO_DATA_FOUND THEN NULL;
    END;

    -- 2차: 테이블 레벨 기본 정책 조회
    BEGIN
        SELECT POLICY_TYPE INTO v_policy
        FROM SYNC_CONFLICT_POLICY
        WHERE MAPPING_ID = p_mapping_id
          AND ASIS_COLUMN IS NULL
          AND TOBE_COLUMN IS NULL
          AND CONFLICT_TYPE IN (p_conflict_type, 'ALL')
          AND IS_ACTIVE = 'Y'
        ORDER BY CASE WHEN CONFLICT_TYPE = p_conflict_type THEN 1 ELSE 2 END
        FETCH FIRST 1 ROW ONLY;
        RETURN v_policy;
    EXCEPTION WHEN NO_DATA_FOUND THEN NULL;
    END;

    -- 정책 없음 → NULL (수동 처리)
    RETURN NULL;
END;
```

**정책 적용 예시**
```sql
PROCEDURE RESOLVE_UPD_UPD_CONFLICT(
    p_mapping_id    NUMBER,
    p_pk_value      VARCHAR2,
    p_asis_column   VARCHAR2,
    p_tobe_column   VARCHAR2,
    p_value_asis    VARCHAR2,
    p_value_tobe    VARCHAR2,
    p_time_asis     TIMESTAMP,
    p_time_tobe     TIMESTAMP
) AS
    v_policy        VARCHAR2(20);
    v_final_value   VARCHAR2(4000);
    v_final_system  VARCHAR2(10);
BEGIN
    -- 정책 조회
    v_policy := GET_CONFLICT_POLICY(p_mapping_id, p_asis_column, p_tobe_column, 'UPD_UPD');

    CASE v_policy
        WHEN 'LWW' THEN
            IF p_time_asis > p_time_tobe THEN
                v_final_value := p_value_asis;
                v_final_system := 'ASIS';
            ELSE
                v_final_value := p_value_tobe;
                v_final_system := 'TOBE';
            END IF;
        WHEN 'ASIS_PRIORITY' THEN
            v_final_value := p_value_asis;
            v_final_system := 'ASIS';
        WHEN 'TOBE_PRIORITY' THEN
            v_final_value := p_value_tobe;
            v_final_system := 'TOBE';
        ELSE
            -- MANUAL 또는 정책 없음
            LOG_CONFLICT_FOR_MANUAL(...);
            RETURN;
    END CASE;

    -- 양쪽에 최종 값 적용 (스키마 변환하여)
    APPLY_VALUE_TO_ASIS(p_mapping_id, p_pk_value, p_asis_column, v_final_value);
    APPLY_VALUE_TO_TOBE(p_mapping_id, p_pk_value, p_tobe_column, v_final_value);

    -- 해결 로그 기록
    LOG_RESOLUTION('UPD_UPD', v_policy, p_mapping_id, ...);
END;
```

---

### 3.2 UPDATE vs UPDATE (다른 컬럼) - 자동 병합

**충돌 상황**
```
ASIS: UPDATE BOOK_INFO SET BOOK_TITLE = '제목A' WHERE BOOK_ID = 1
TOBE: UPDATE TB_BOOK SET IS_ACTIVE = 0 WHERE BOOK_ID = 1

→ BOOK_TITLE↔TITLE, STATUS↔IS_ACTIVE는 다른 컬럼
→ 자동 병합 가능
```

**기본 정책: FIELD_MERGE**

**병합 결과**
```
ASIS: BOOK_TITLE = '제목A', STATUS = 'N' (역변환 적용)
TOBE: TITLE = '제목A', IS_ACTIVE = 0
```

---

### 3.3 UPDATE vs DELETE

**정책 옵션**

| 정책 | 동작 | 적합한 경우 |
|------|------|-------------|
| DELETE_PRIORITY | 양쪽 모두 삭제 | 삭제 의도 존중 |
| UPDATE_PRIORITY | 삭제 취소, 수정 유지 | 데이터 보존 우선 |
| MANUAL | 관리자 선택 | 중요 데이터 |

**처리 시 스키마 변환**
```sql
PROCEDURE HANDLE_UPDATE_DELETE(
    p_mapping_id    NUMBER,
    p_pk_value      VARCHAR2,
    p_update_data   CLOB,
    p_update_from   VARCHAR2
) AS
    v_policy        VARCHAR2(20);
    v_asis_table    VARCHAR2(100);
    v_tobe_table    VARCHAR2(100);
BEGIN
    -- 테이블 정보 조회
    SELECT SOURCE_TABLE, TARGET_TABLE
    INTO v_asis_table, v_tobe_table
    FROM SYNC_TABLE_MAPPING
    WHERE MAPPING_ID = p_mapping_id;

    -- 정책 조회
    v_policy := GET_CONFLICT_POLICY(p_mapping_id, NULL, NULL, 'UPD_DEL');

    CASE v_policy
        WHEN 'DELETE_PRIORITY' THEN
            -- 양쪽 모두 삭제 (각 스키마에 맞게)
            EXECUTE_DELETE_ASIS(v_asis_table, p_pk_value);
            EXECUTE_DELETE_TOBE(v_tobe_table, p_pk_value);

        WHEN 'UPDATE_PRIORITY' THEN
            -- 삭제된 쪽 복구 + 수정 적용
            IF p_update_from = 'ASIS' THEN
                -- ASIS 데이터로 TOBE 복구 (스키마 변환)
                v_tobe_data := TRANSFORM_ASIS_TO_TOBE(p_mapping_id, p_update_data);
                RESTORE_AND_UPDATE_TOBE(v_tobe_table, p_pk_value, v_tobe_data);
            ELSE
                -- TOBE 데이터로 ASIS 복구 (역변환)
                v_asis_data := TRANSFORM_TOBE_TO_ASIS(p_mapping_id, p_update_data);
                RESTORE_AND_UPDATE_ASIS(v_asis_table, p_pk_value, v_asis_data);
            END IF;

        ELSE
            -- MANUAL
            LOG_CONFLICT_FOR_MANUAL(...);
    END CASE;
END;
```

---

## 4. 테이블 유형별 권장 정책

### 4.1 마스터 데이터 (코드, 분류)

```sql
-- 코드 마스터: ASIS가 원본
-- MAPPING_ID = 10: ASIS.CODE_MASTER ↔ TOBE.TB_CODE_MASTER
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (10, 10, NULL, NULL, 'ALL', 'ASIS_PRIORITY', '코드 마스터는 ASIS 관리', 'Y', SYSTIMESTAMP, NULL);

-- 분류 코드: ASIS가 원본
-- MAPPING_ID = 11: ASIS.CATEGORY ↔ TOBE.TB_CATEGORY
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (11, 11, NULL, NULL, 'ALL', 'ASIS_PRIORITY', '분류 코드는 ASIS 관리', 'Y', SYSTIMESTAMP, NULL);
```

### 4.2 트랜잭션 데이터 (대출, 반납)

```sql
-- 대출/반납 이력: 수동 해결
-- MAPPING_ID = 20: ASIS.LOAN_HISTORY ↔ TOBE.TB_LOAN_HISTORY
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (20, 20, NULL, NULL, 'ALL', 'MANUAL', '대출 이력은 수동 확인 필요', 'Y', SYSTIMESTAMP, NULL);
```

### 4.3 로그/이력 데이터

```sql
-- 시스템 로그: LWW
-- MAPPING_ID = 30: ASIS.SYSTEM_LOG ↔ TOBE.TB_SYSTEM_LOG
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (30, 30, NULL, NULL, 'ALL', 'LWW', '시스템 로그는 최신 값 우선', 'Y', SYSTIMESTAMP, NULL);
```

### 4.4 혼합 정책 (컬럼별 다르게)

```sql
-- MAPPING_ID = 1: ASIS.BOOK_INFO ↔ TOBE.TB_BOOK
-- 테이블 기본: MANUAL
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (100, 1, NULL, NULL, 'ALL', 'MANUAL', '도서 테이블 기본: 수동', 'Y', SYSTIMESTAMP, NULL);

-- 제목: ASIS 우선
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (101, 1, 'BOOK_TITLE', 'TITLE', 'UPD_UPD', 'ASIS_PRIORITY', '제목은 ASIS', 'Y', SYSTIMESTAMP, NULL);

-- 상태: TOBE 우선
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (102, 1, 'STATUS', 'IS_ACTIVE', 'UPD_UPD', 'TOBE_PRIORITY', '상태는 TOBE', 'Y', SYSTIMESTAMP, NULL);

-- 설명: LWW
INSERT INTO SYNC_CONFLICT_POLICY
VALUES (103, 1, 'DESCRIPTION', 'DESCRIPTION', 'UPD_UPD', 'LWW', '설명은 최신', 'Y', SYSTIMESTAMP, NULL);
```

---

## 5. 충돌 로그 테이블

```sql
CREATE TABLE SYNC_CONFLICT_LOG (
    CONFLICT_ID         NUMBER PRIMARY KEY,
    CONFLICT_TYPE       VARCHAR2(20) NOT NULL,  -- UPD_UPD, UPD_DEL, INS_INS
    MAPPING_ID          NUMBER NOT NULL,        -- 테이블 매핑 ID
    PK_VALUE            VARCHAR2(500) NOT NULL,
    ASIS_COLUMN         VARCHAR2(100),
    TOBE_COLUMN         VARCHAR2(100),
    DATA_ASIS           CLOB,                   -- ASIS 스키마 데이터
    DATA_TOBE           CLOB,                   -- TOBE 스키마 데이터
    TIME_ASIS           TIMESTAMP,
    TIME_TOBE           TIMESTAMP,
    STATUS              VARCHAR2(20) NOT NULL,  -- PENDING, RESOLVED, SKIPPED
    APPLIED_POLICY      VARCHAR2(20),
    RESOLVED_VALUE      CLOB,
    RESOLVED_BY         VARCHAR2(50),
    RESOLVED_AT         TIMESTAMP,
    CREATED_AT          TIMESTAMP DEFAULT SYSTIMESTAMP,
    FOREIGN KEY (MAPPING_ID) REFERENCES SYNC_TABLE_MAPPING(MAPPING_ID)
);

CREATE INDEX IDX_CONFLICT_STATUS ON SYNC_CONFLICT_LOG(STATUS);
CREATE INDEX IDX_CONFLICT_MAPPING ON SYNC_CONFLICT_LOG(MAPPING_ID, STATUS);

CREATE SEQUENCE SYNC_CONFLICT_SEQ START WITH 1;
```

---

## 6. 정책 설정 가이드

### 정책 결정 플로우차트

```
데이터 중요도는?
    │
    ├─ 높음 (트랜잭션, 업무핵심) → MANUAL
    │
    ├─ 중간 (마스터, 참조) → 원본 시스템 우선
    │   │
    │   ├─ ASIS가 원본? → ASIS_PRIORITY
    │   └─ TOBE가 원본? → TOBE_PRIORITY
    │
    └─ 낮음 (로그, 임시) → LWW
```

### 체크리스트

| 질문 | Yes → 정책 |
|------|------------|
| 데이터 유실 시 업무 영향이 큰가? | MANUAL |
| 특정 시스템이 데이터 원본인가? | XXX_PRIORITY |
| 최신 정보가 항상 정확한가? | LWW |
| 다른 컬럼 변경은 병합해도 되는가? | FIELD_MERGE |

---

## 7. 관련 문서

| 순서 | 문서명 | 내용 |
|:----:|--------|------|
| 01 | `01_CDC_동기화_설계_정리.md` | 전체 설계, 스키마 매핑 |
| 02 | `02_CDC_무한루프_방지_대안.md` | 무한루프 방지 |
| 03 | `03_CDC_동기화_케이스_분류.md` | 케이스별 분류 |
| **04** | 본 문서 | 충돌 해결 정책 |
| 05 | `05_CDC_에러코드_체계.md` | 에러 코드 |
