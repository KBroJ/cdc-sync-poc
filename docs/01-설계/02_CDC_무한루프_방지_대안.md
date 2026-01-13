# CDC 무한루프 방지 대안 분석

> 최종 수정일: 2025-01-13
> 문서 번호: 02
> 이전 문서: `01_CDC_동기화_설계_정리.md`
> 다음 문서: `03_CDC_동기화_케이스_분류.md`

---

## 1. 문제 정의

### 1.1 무한루프 발생 시나리오

```
1. ASIS에서 사용자가 UPDATE BOOK_INFO SET BOOK_TITLE='A' WHERE BOOK_ID=1
2. ASIS Redo Log 기록
3. ASIS CDC Agent 캡처 → CDC_TOBE_TABLES 전송 (ASIS 스키마)
4. 가공 프로시저: ASIS→TOBE 스키마 변환 (BOOK_TITLE → TITLE)
5. STAGING_TOBES → WORKER → TOBE 원본 반영 (UPDATE TB_BOOK SET TITLE='A')
6. TOBE Redo Log 기록 ← 여기서 다시 캡처됨!
7. TOBE CDC Agent → CDC_ASIS_TABLES 전송 (TOBE 스키마)
8. 가공 프로시저: TOBE→ASIS 스키마 변환 (TITLE → BOOK_TITLE)
9. STAGING_ASIS → WORKER → ASIS 원본 반영 시도
10. ASIS Redo Log 기록 ← 또 캡처됨!
11. ... 무한 반복
```

### 1.2 스키마 변환이 있는 경우의 특수성

```
┌─────────────────────────────────────────────────────────────────┐
│  스키마가 다르므로 단순 비교가 어려움                            │
│                                                                 │
│  ASIS: BOOK_TITLE = 'A', STATUS = 'Y'                          │
│  TOBE: TITLE = 'A', IS_ACTIVE = 1                              │
│                                                                 │
│  동일한 데이터인지 판단하려면 매핑 정보를 참조해야 함            │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 제약조건 재확인
- 기존 테이블 컬럼 추가 불가
- 기존 소스 수정 불가
- CDC 솔루션 미정 (특정 기능 의존 불가)
- 동기화용 테이블 생성 가능

---

## 2. 대안 비교표

| 대안 | 솔루션 의존 | 구현 복잡도 | 신뢰도 | 스키마 차이 대응 | 권장 |
|------|:-----------:|:-----------:|:------:|:----------------:|:----:|
| 1. SYNC_USER 계정 분리 | O | 낮음 | 높음 | 무관 | **보류** |
| 2. 트랜잭션 ID 기반 | △ | 중간 | 중간 | 가능 | △ |
| 3. 해시 기반 중복 감지 | X | 중간 | 높음 | **매핑 필요** | **권장** |
| 4. 버전/시퀀스 번호 | X | 중간 | 높음 | 가능 | **권장** |
| 5. 마스터 시스템 분리 | X | 낮음 | 높음 | 무관 | 부분적용 |

---

## 3. 대안 1: 동기화 전용 DB 계정 (보류)

### 개념
```
일반 사용자 계정: APP_USER  → CDC Agent가 캡처함
동기화 전용 계정: SYNC_USER → CDC Agent가 캡처 안함

WORKER 프로시저는 SYNC_USER로 실행
→ 동기화로 인한 변경은 다시 캡처되지 않음
```

### 보류 사유
- CDC 솔루션이 계정/세션 기반 필터링을 지원해야 함
- 솔루션 미정 상태에서 확정 불가
- **솔루션 선정 후 재검토 필요**

---

## 4. 대안 2: 트랜잭션 ID 기반

### 개념
- 모든 동기화 건에 고유 SYNC_TX_ID 부여
- 처리 완료된 TX_ID 기록
- 이미 처리된 TX_ID가 다시 돌아오면 무시

### 구현
```sql
CREATE TABLE SYNC_PROCESSED_LOG (
    SYNC_TX_ID      VARCHAR2(50) PRIMARY KEY,
    SOURCE_SYSTEM   VARCHAR2(10),
    SOURCE_TABLE    VARCHAR2(100),
    TARGET_SYSTEM   VARCHAR2(10),
    TARGET_TABLE    VARCHAR2(100),    -- 스키마 변환 후 테이블명
    PK_VALUE        VARCHAR2(500),
    OPERATION_TYPE  VARCHAR2(10),
    PROCESSED_AT    TIMESTAMP DEFAULT SYSTIMESTAMP,
    EXPIRED_AT      TIMESTAMP
);

CREATE INDEX IDX_SYNC_PROCESSED_EXPIRED ON SYNC_PROCESSED_LOG(EXPIRED_AT);
```

### 한계
- CDC Agent가 TX_ID를 부여할 수 있어야 함 (솔루션 의존)

---

## 5. 대안 3: 해시 기반 중복 감지 (권장)

### 개념
- 변경 내용의 해시 생성
- 동일 해시가 일정 시간 내에 반대 방향에서 오면 무시
- **스키마 변환 후의 값으로 해시 생성** (핵심!)

### 스키마 차이 대응 방법

```
┌─────────────────────────────────────────────────────────────────┐
│  해시 생성 시점: 가공 프로시저에서 변환 후                        │
│                                                                 │
│  ASIS→TOBE 동기화:                                              │
│  - 변환 후 데이터로 해시 생성: HASH('TB_BOOK|1|UPDATE|TITLE=A')  │
│  - TOBE 측 SYNC_CHANGE_HASH에 기록                              │
│                                                                 │
│  TOBE→ASIS 역동기화 시:                                          │
│  - 수신 데이터: TB_BOOK, TITLE='A'                               │
│  - 해시 생성: HASH('TB_BOOK|1|UPDATE|TITLE=A')                  │
│  - 동일 해시 발견 → 스킵                                         │
└─────────────────────────────────────────────────────────────────┘
```

### 구현
```sql
-- 변경 해시 테이블 (양쪽 DB에 생성)
CREATE TABLE SYNC_CHANGE_HASH (
    CHANGE_HASH     VARCHAR2(64) PRIMARY KEY,  -- SHA256
    SOURCE_SYSTEM   VARCHAR2(10),
    SOURCE_TABLE    VARCHAR2(100),
    TARGET_SYSTEM   VARCHAR2(10),
    TARGET_TABLE    VARCHAR2(100),             -- 변환 후 테이블명
    PK_VALUE        VARCHAR2(500),
    OPERATION_TYPE  VARCHAR2(10),
    DIRECTION       VARCHAR2(20),              -- ASIS_TO_TOBE, TOBE_TO_ASIS
    CREATED_AT      TIMESTAMP DEFAULT SYSTIMESTAMP
);

CREATE INDEX IDX_SYNC_HASH_CREATED ON SYNC_CHANGE_HASH(CREATED_AT);
```

### 해시 생성 함수 (스키마 변환 고려)
```sql
CREATE OR REPLACE FUNCTION GENERATE_CHANGE_HASH(
    p_target_table  VARCHAR2,    -- 변환 후 대상 테이블명
    p_pk_value      VARCHAR2,
    p_operation     VARCHAR2,
    p_change_data   CLOB         -- 변환 후 데이터 (JSON)
) RETURN VARCHAR2 AS
    v_input     VARCHAR2(4000);
    v_normalized CLOB;
BEGIN
    -- 데이터 정규화 (키 정렬 등)
    v_normalized := NORMALIZE_JSON(p_change_data);

    -- 해시 입력 문자열 생성
    v_input := p_target_table || '|' || p_pk_value || '|' ||
               p_operation || '|' || DBMS_LOB.SUBSTR(v_normalized, 3000, 1);

    RETURN RAWTOHEX(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW(v_input), DBMS_CRYPTO.HASH_SH256));
END;
```

### 흐름 (ASIS → TOBE 동기화)
```
1. ASIS에서 UPDATE BOOK_INFO SET BOOK_TITLE='A' WHERE BOOK_ID=1
2. CDC Agent 캡처 → CDC_TOBE_TABLES 적재 (ASIS 스키마)
3. 가공 프로시저에서:
   a. ASIS→TOBE 스키마 변환: BOOK_INFO.BOOK_TITLE → TB_BOOK.TITLE
   b. 변환 후 데이터로 해시 생성: HASH('TB_BOOK|1|UPDATE|{"TITLE":"A"}')
   c. SYNC_CHANGE_HASH에 기록 (DIRECTION='ASIS_TO_TOBE')
4. STAGING_TOBES 적재 → WORKER → TOBE 원본 반영
5. TOBE CDC Agent 캡처 (TB_BOOK.TITLE='A')
6. CDC_ASIS_TABLES로 전송
7. ASIS 가공 프로시저에서:
   a. 수신 데이터로 해시 생성: HASH('TB_BOOK|1|UPDATE|{"TITLE":"A"}')
   b. SYNC_CHANGE_HASH 조회: 최근 1시간 내 ASIS_TO_TOBE로 기록 있음
   c. → 스킵 (동기화로 인한 변경)
```

### 장점
- 솔루션 독립적
- 스키마 변환 후 비교하므로 정확함
- 빠른 감지

### 단점
- 해시 생성 시 스키마 변환이 완료되어야 함
- 시간 윈도우 설정 필요

---

## 6. 대안 4: 버전/시퀀스 번호 기반 (권장)

### 개념
- 각 레코드별 버전 번호를 관리
- 마지막 업데이트 시스템 기록
- 자신이 보낸 변경이 돌아오면 무시

### 구현 (스키마 매핑 정보 포함)
```sql
CREATE TABLE SYNC_VERSION_CONTROL (
    MAPPING_ID      NUMBER,                    -- 테이블 매핑 ID
    TARGET_TABLE    VARCHAR2(100),             -- 대상 테이블명 (표준화된 이름)
    PK_VALUE        VARCHAR2(500),
    CURRENT_VERSION NUMBER DEFAULT 0,
    LAST_UPDATED_BY VARCHAR2(10),              -- ASIS or TOBE
    LAST_CHANGE_HASH VARCHAR2(64),
    UPDATED_AT      TIMESTAMP DEFAULT SYSTIMESTAMP,
    PRIMARY KEY (TARGET_TABLE, PK_VALUE)
);
```

### 표준화된 키 사용

```
┌─────────────────────────────────────────────────────────────────┐
│  버전 관리 시 표준화된 테이블/PK 사용                            │
│                                                                 │
│  ASIS: BOOK_INFO (BOOK_ID = 1)                                 │
│  TOBE: TB_BOOK (BOOK_ID = 1)                                   │
│                                                                 │
│  → 버전 관리: TARGET_TABLE = 'TB_BOOK', PK_VALUE = '1'         │
│    (TOBE 스키마 기준으로 통일)                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 흐름
```
1. ASIS에서 BOOK_INFO 변경 (BOOK_ID=1)
2. 가공 프로시저:
   - TOBE 스키마로 변환
   - SYNC_VERSION_CONTROL 조회: TARGET_TABLE='TB_BOOK', PK='1'
   - VERSION 증가, LAST_UPDATED_BY='ASIS' 기록
3. TOBE로 전송 및 반영
4. TOBE CDC Agent 캡처 → ASIS로 전송
5. ASIS 가공 프로시저:
   - SYNC_VERSION_CONTROL 조회: VERSION=1, LAST_UPDATED_BY='ASIS'
   - 자신(ASIS)이 마지막 업데이트 → 스킵
```

### 판단 로직
```sql
FUNCTION SHOULD_SKIP_SYNC(
    p_target_table  VARCHAR2,
    p_pk_value      VARCHAR2,
    p_source_system VARCHAR2,
    p_version       NUMBER DEFAULT NULL
) RETURN BOOLEAN AS
    v_current_version NUMBER;
    v_last_updated_by VARCHAR2(10);
BEGIN
    SELECT CURRENT_VERSION, LAST_UPDATED_BY
    INTO v_current_version, v_last_updated_by
    FROM SYNC_VERSION_CONTROL
    WHERE TARGET_TABLE = p_target_table AND PK_VALUE = p_pk_value;

    -- 자신이 보낸 변경이 돌아옴
    IF v_last_updated_by = p_source_system THEN
        RETURN TRUE;  -- 스킵
    END IF;

    -- 버전 체크 (있는 경우)
    IF p_version IS NOT NULL AND p_version <= v_current_version THEN
        RETURN TRUE;  -- 이미 처리된 버전
    END IF;

    RETURN FALSE;  -- 처리 필요

EXCEPTION
    WHEN NO_DATA_FOUND THEN
        RETURN FALSE;  -- 신규 레코드
END;
```

---

## 7. 대안 5: 마스터 시스템 분리 (부분 적용)

### 개념
- 특정 테이블/컬럼은 한쪽 시스템만 변경 가능하도록 정책 설정
- 충돌 및 무한루프 자체를 원천 차단

### 구현 (스키마 매핑과 통합)
```sql
CREATE TABLE SYNC_MASTER_POLICY (
    MAPPING_ID      NUMBER,                    -- 테이블 매핑 ID
    SOURCE_COLUMN   VARCHAR2(100),             -- ASIS 컬럼 (NULL이면 전체)
    TARGET_COLUMN   VARCHAR2(100),             -- TOBE 컬럼
    MASTER_SYSTEM   VARCHAR2(10),              -- ASIS or TOBE
    DESCRIPTION     VARCHAR2(500)
);

-- 예시: BOOK 테이블
-- TITLE은 ASIS 마스터, STATUS는 TOBE 마스터
INSERT INTO SYNC_MASTER_POLICY VALUES (1, 'BOOK_TITLE', 'TITLE', 'ASIS', '제목은 ASIS에서만 변경');
INSERT INTO SYNC_MASTER_POLICY VALUES (1, 'STATUS', 'IS_ACTIVE', 'TOBE', '상태는 TOBE에서만 변경');
```

### 적용 대상
- 명확하게 한쪽에서만 관리하는 데이터
- 마스터 데이터 (코드, 분류 등)

---

## 8. 권장 조합: 다중 방어막

```
┌─────────────────────────────────────────────────────────────────┐
│                        무한루프 방지 체계                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  [1차 방어] 해시 기반 중복 감지 (대안 3)                         │
│  - 스키마 변환 후 데이터로 해시 생성                             │
│  - 동일 해시 발견 시 즉시 스킵                                   │
│                                                                 │
│  [2차 방어] 버전 번호 기반 (대안 4)                              │
│  - 표준화된 테이블/PK로 버전 관리                                │
│  - LAST_UPDATED_BY로 원본 시스템 확인                           │
│                                                                 │
│  [3차 방어] 마스터 정책 (대안 5) - 부분 적용                     │
│  - 명확한 마스터가 있는 테이블/컬럼에 적용                       │
│                                                                 │
│  [추가] 솔루션 선정 후                                          │
│  - CDC 솔루션이 계정 필터 지원 시 대안 1 추가 적용               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 판단 흐름도

```
변경 데이터 수신 (CDC_TABLES)
    │
    ▼
스키마 변환 (가공 프로시저)
    │
    ▼
[1차] 해시 중복 체크
    │
    ├─ 중복 → SKIP (로그 기록)
    │
    ▼
[2차] 버전/출처 체크
    │
    ├─ 자신이 보낸 변경 → SKIP
    ├─ 낮은 버전 → SKIP
    │
    ▼
[3차] 마스터 정책 체크
    │
    ├─ 마스터 아닌 시스템에서 온 변경 → SKIP (해당 컬럼만)
    │
    ▼
STAGING 적재 → WORKER 처리
```

---

## 9. 구현 우선순위

| 순위 | 대안 | 이유 |
|:----:|------|------|
| 1 | 해시 기반 (대안 3) | 스키마 변환 후 비교, 정확함 |
| 2 | 버전 기반 (대안 4) | 감사 추적, 명확한 로직 |
| 3 | 마스터 정책 (대안 5) | 테이블 분석 후 적용 |
| 4 | 계정 분리 (대안 1) | 솔루션 선정 후 검토 |

---

## 10. 추가 고려사항

### 시간 윈도우 설정
- 해시 중복 체크 시 시간 윈도우 필요
- 네트워크 지연 + 스키마 변환 시간 고려
- **권장: 1시간** (보수적 설정, 운영 후 조정)

### 데이터 정리
- SYNC_CHANGE_HASH, SYNC_VERSION_CONTROL 테이블 증가
- 정기 정리 Job 필요
- 보관 기간: 24시간 ~ 7일

---

## 11. 관련 문서

| 순서 | 문서명 | 내용 |
|:----:|--------|------|
| 01 | `01_CDC_동기화_설계_정리.md` | 전체 설계, 스키마 매핑 |
| **02** | 본 문서 | 무한루프 방지 |
| 03 | `03_CDC_동기화_케이스_분류.md` | 케이스별 분류 |
| 04 | `04_CDC_충돌_정책.md` | 충돌 해결 정책 |
| 05 | `05_CDC_에러코드_체계.md` | 에러 코드 |
