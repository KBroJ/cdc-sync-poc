# CDC 에러코드 체계

> 최종 수정일: 2025-01-13
> 문서 번호: 05
> 이전 문서: `04_CDC_충돌_정책.md`

---

## 1. 에러코드 형식

### 코드 구조

```
[카테고리]_[심각도]_[순번]

예: SYNC_E_001, CONF_W_002, MAP_E_003
```

### 카테고리

| 코드 | 카테고리 | 설명 |
|------|----------|------|
| SYNC | Synchronization | 동기화 처리 관련 에러 |
| CONF | Conflict | 충돌 관련 에러 |
| DATA | Data | 데이터 검증/변환 에러 |
| **MAP** | Mapping | **스키마 매핑 관련 에러** |
| NET | Network | 네트워크/연결 에러 |
| SYS | System | 시스템/프로시저 에러 |

### 심각도

| 코드 | 심각도 | 설명 | 대응 수준 |
|------|--------|------|-----------|
| E | Error | 즉시 조치 필요 | 알림 + 수동 처리 |
| W | Warning | 주의 필요, 자동 처리됨 | 로그 모니터링 |
| I | Info | 정보성 | 로그 기록 |

---

## 2. 에러코드 목록

### 2.1 SYNC (동기화 처리)

| 코드 | 설명 | 원인 | 자동처리 | 화면액션 |
|------|------|------|:--------:|----------|
| **SYNC_E_001** | 대상 레코드 없음 | UPDATE/DELETE 시 해당 PK 미존재 | X | 재시도 / INSERT 변환 / 무시 |
| **SYNC_E_002** | PK 중복 | INSERT 시 이미 존재 | X | UPDATE 변환 / 무시 |
| **SYNC_E_003** | FK 위반 | 참조 대상 미존재 | △ | 재시도 / 대기 / 무시 |
| **SYNC_E_004** | 자식 레코드 존재 | DELETE 시 자식 존재 | X | 자식 먼저 삭제 / 무시 |
| **SYNC_E_005** | 처리 순서 오류 | 선행 작업 미완료 | △ | 재시도 / 순서 조정 |
| **SYNC_W_001** | 대상 이미 삭제됨 | DELETE 시 이미 없음 | O | - |
| **SYNC_W_002** | 중복 처리 스킵 | 이미 처리된 건 | O | - |
| **SYNC_I_001** | 동기화 완료 | 정상 처리 완료 | O | - |

### 2.2 CONF (충돌)

| 코드 | 설명 | 원인 | 자동처리 | 화면액션 |
|------|------|------|:--------:|----------|
| **CONF_E_001** | UPDATE 충돌 (같은컬럼) | 양쪽 동시 수정 | △ | ASIS선택 / TOBE선택 / 직접입력 |
| **CONF_E_002** | UPDATE 충돌 (다른컬럼) | 양쪽 다른 컬럼 수정 | O | 병합 확인 |
| **CONF_E_003** | UPDATE vs DELETE | 수정 vs 삭제 충돌 | X | 삭제유지 / 복구후수정 |
| **CONF_E_004** | INSERT PK 충돌 | 양쪽 동일 PK INSERT | X | PK변경 / 하나삭제 |
| **CONF_W_001** | 자동 병합 완료 | 다른 컬럼 자동 병합 | O | - |
| **CONF_W_002** | 정책에 따라 처리됨 | LWW/Priority 자동 적용 | O | - |
| **CONF_I_001** | 충돌 해결 완료 | 수동 해결 완료 | - | - |

### 2.3 MAP (스키마 매핑) - 신규 추가

| 코드 | 설명 | 원인 | 자동처리 | 화면액션 |
|------|------|------|:--------:|----------|
| **MAP_E_001** | 테이블 매핑 없음 | 매핑 정의 누락 | X | 매핑 추가 |
| **MAP_E_002** | 컬럼 매핑 없음 | 필수 컬럼 매핑 누락 | X | 매핑 추가 |
| **MAP_E_003** | 코드 매핑 없음 | 코드값 매핑 누락 | X | 코드 매핑 추가 |
| **MAP_E_004** | 테이블 분리/통합 오류 | 복잡한 매핑 실패 | X | 매핑 규칙 확인 |
| **MAP_E_005** | 역변환 불가 | 양방향 매핑 미정의 | X | 역방향 매핑 추가 |
| **MAP_E_006** | 변환 프로시저 오류 | 가공 프로시저 실패 | X | 프로시저 확인 |
| **MAP_W_001** | 매핑 없는 컬럼 무시 | 선택 컬럼 매핑 없음 | O | - (로그 기록) |
| **MAP_I_001** | 스키마 변환 완료 | 정상 변환 완료 | O | - |

### 2.4 DATA (데이터)

| 코드 | 설명 | 원인 | 자동처리 | 화면액션 |
|------|------|------|:--------:|----------|
| **DATA_E_001** | 데이터 타입 불일치 | 형변환 실패 | X | 값 수정 / 변환규칙 수정 |
| **DATA_E_002** | 필수값 누락 | NOT NULL 위반 | X | 값 입력 / 기본값 설정 |
| **DATA_E_003** | 값 길이 초과 | 컬럼 길이 초과 | △ | 값 수정 / 자동절삭 |
| **DATA_E_004** | 유효하지 않은 값 | CHECK 제약 위반 | X | 값 수정 |
| **DATA_E_005** | 코드 변환 실패 | 매핑 규칙 오류 | X | 매핑 규칙 수정 |
| **DATA_W_001** | 값 자동 절삭 | 길이 초과로 절삭됨 | O | - (경고 로그) |
| **DATA_W_002** | 기본값 적용 | 누락된 값에 기본값 | O | - |

### 2.5 NET (네트워크)

| 코드 | 설명 | 원인 | 자동처리 | 화면액션 |
|------|------|------|:--------:|----------|
| **NET_E_001** | 연결 타임아웃 | 응답 시간 초과 | O | 자동재시도 (3회) |
| **NET_E_002** | 연결 거부 | 대상 DB 거부 | X | 연결상태 확인 |
| **NET_E_003** | 네트워크 단절 | 네트워크 끊김 | △ | 복구 대기 / 수동재시도 |
| **NET_W_001** | 재시도 성공 | 재시도 후 성공 | O | - |
| **NET_I_001** | 연결 복구 | 네트워크 복구됨 | O | - |

### 2.6 SYS (시스템)

| 코드 | 설명 | 원인 | 자동처리 | 화면액션 |
|------|------|------|:--------:|----------|
| **SYS_E_001** | 프로시저 실행 에러 | PL/SQL 에러 | X | 로그확인 / 개발자 문의 |
| **SYS_E_002** | 메모리 부족 | 리소스 부족 | X | 시스템 확인 |
| **SYS_E_003** | 락 타임아웃 | 테이블 락 | △ | 재시도 / 락 해제 확인 |
| **SYS_E_004** | 설정 오류 | 매핑/정책 설정 누락 | X | 설정 추가 |
| **SYS_W_001** | 처리 지연 | 예상보다 오래 걸림 | O | - (모니터링) |

---

## 3. 에러 테이블 구조

### 3.1 에러 코드 마스터

```sql
CREATE TABLE SYNC_ERROR_CODE (
    ERROR_CODE          VARCHAR2(20) PRIMARY KEY,
    CATEGORY            VARCHAR2(10) NOT NULL,
    SEVERITY            VARCHAR2(1) NOT NULL,
    ERROR_NAME          VARCHAR2(100) NOT NULL,
    ERROR_DESC          VARCHAR2(500),
    AUTO_RESOLVABLE     CHAR(1) DEFAULT 'N',    -- Y/N/P(Partial)
    MAX_RETRY           NUMBER DEFAULT 0,
    RETRY_INTERVAL_SEC  NUMBER DEFAULT 60,
    ALERT_REQUIRED      CHAR(1) DEFAULT 'N',
    IS_ACTIVE           CHAR(1) DEFAULT 'Y'
);

-- 주요 에러코드 등록
INSERT INTO SYNC_ERROR_CODE VALUES ('SYNC_E_001', 'SYNC', 'E', '대상 레코드 없음', 'UPDATE/DELETE 시 해당 PK의 레코드가 대상 시스템에 존재하지 않습니다.', 'N', 0, 0, 'Y', 'Y');
INSERT INTO SYNC_ERROR_CODE VALUES ('SYNC_E_003', 'SYNC', 'E', 'FK 위반', '참조하는 부모 레코드가 존재하지 않습니다.', 'P', 5, 60, 'N', 'Y');

INSERT INTO SYNC_ERROR_CODE VALUES ('MAP_E_001', 'MAP', 'E', '테이블 매핑 없음', '해당 테이블의 매핑 정의가 없습니다.', 'N', 0, 0, 'Y', 'Y');
INSERT INTO SYNC_ERROR_CODE VALUES ('MAP_E_002', 'MAP', 'E', '컬럼 매핑 없음', '필수 컬럼의 매핑 정의가 없습니다.', 'N', 0, 0, 'Y', 'Y');
INSERT INTO SYNC_ERROR_CODE VALUES ('MAP_E_003', 'MAP', 'E', '코드 매핑 없음', '해당 코드값의 매핑 정의가 없습니다.', 'N', 0, 0, 'Y', 'Y');
INSERT INTO SYNC_ERROR_CODE VALUES ('MAP_E_005', 'MAP', 'E', '역변환 불가', '양방향 동기화에 필요한 역방향 매핑이 정의되지 않았습니다.', 'N', 0, 0, 'Y', 'Y');

INSERT INTO SYNC_ERROR_CODE VALUES ('CONF_E_001', 'CONF', 'E', 'UPDATE 충돌 (같은컬럼)', '양쪽 시스템에서 동일 레코드의 동일 컬럼을 동시에 수정했습니다.', 'P', 0, 0, 'Y', 'Y');

INSERT INTO SYNC_ERROR_CODE VALUES ('DATA_E_001', 'DATA', 'E', '데이터 타입 불일치', '원본과 대상의 데이터 타입이 달라 변환에 실패했습니다.', 'N', 0, 0, 'Y', 'Y');
```

### 3.2 에러 로그 테이블

```sql
CREATE TABLE SYNC_ERROR_LOG (
    ERROR_LOG_ID        NUMBER PRIMARY KEY,
    ERROR_CODE          VARCHAR2(20) NOT NULL,
    SYNC_ID             NUMBER,
    MAPPING_ID          NUMBER,                 -- 스키마 매핑 ID
    SOURCE_TABLE        VARCHAR2(100),          -- 원본 테이블 (ASIS/TOBE 스키마)
    TARGET_TABLE        VARCHAR2(100),          -- 대상 테이블 (ASIS/TOBE 스키마)
    PK_VALUE            VARCHAR2(500),
    OPERATION_TYPE      VARCHAR2(10),
    SOURCE_SYSTEM       VARCHAR2(10),
    TARGET_SYSTEM       VARCHAR2(10),
    ERROR_MESSAGE       VARCHAR2(4000),
    ERROR_DETAIL        CLOB,
    CHANGE_DATA_ORIGINAL CLOB,                  -- 원본 스키마 데이터
    CHANGE_DATA_TRANSFORMED CLOB,               -- 변환 후 데이터 (있는 경우)
    RETRY_COUNT         NUMBER DEFAULT 0,
    STATUS              VARCHAR2(20) NOT NULL,
    RESOLVED_BY         VARCHAR2(50),
    RESOLVED_AT         TIMESTAMP,
    RESOLUTION_NOTE     VARCHAR2(1000),
    CREATED_AT          TIMESTAMP DEFAULT SYSTIMESTAMP
);

CREATE INDEX IDX_ERROR_LOG_STATUS ON SYNC_ERROR_LOG(STATUS);
CREATE INDEX IDX_ERROR_LOG_CODE ON SYNC_ERROR_LOG(ERROR_CODE, STATUS);
CREATE INDEX IDX_ERROR_LOG_MAPPING ON SYNC_ERROR_LOG(MAPPING_ID, STATUS);

CREATE SEQUENCE SYNC_ERROR_LOG_SEQ START WITH 1;
```

---

## 4. 화면 처리 액션 정의

### 4.1 에러별 가능한 액션

```sql
CREATE TABLE SYNC_ERROR_ACTION (
    ERROR_CODE      VARCHAR2(20),
    ACTION_CODE     VARCHAR2(30),
    ACTION_NAME     VARCHAR2(100),
    ACTION_DESC     VARCHAR2(500),
    DISPLAY_ORDER   NUMBER,
    IS_DEFAULT      CHAR(1) DEFAULT 'N',
    PRIMARY KEY (ERROR_CODE, ACTION_CODE)
);

-- MAP_E_001: 테이블 매핑 없음
INSERT INTO SYNC_ERROR_ACTION VALUES ('MAP_E_001', 'ADD_MAPPING', '매핑 추가', '해당 테이블의 매핑을 추가합니다. (관리자 페이지 이동)', 1, 'Y');
INSERT INTO SYNC_ERROR_ACTION VALUES ('MAP_E_001', 'SKIP', '무시', '이 건은 처리하지 않습니다.', 2, 'N');

-- MAP_E_003: 코드 매핑 없음
INSERT INTO SYNC_ERROR_ACTION VALUES ('MAP_E_003', 'ADD_CODE_MAPPING', '코드 매핑 추가', '해당 코드값의 매핑을 추가합니다.', 1, 'Y');
INSERT INTO SYNC_ERROR_ACTION VALUES ('MAP_E_003', 'INPUT_VALUE', '값 직접 입력', '변환된 값을 직접 입력합니다.', 2, 'N');
INSERT INTO SYNC_ERROR_ACTION VALUES ('MAP_E_003', 'SKIP', '무시', '이 건은 처리하지 않습니다.', 3, 'N');

-- SYNC_E_001: 대상 레코드 없음
INSERT INTO SYNC_ERROR_ACTION VALUES ('SYNC_E_001', 'RETRY', '재시도', '동기화를 다시 시도합니다.', 1, 'N');
INSERT INTO SYNC_ERROR_ACTION VALUES ('SYNC_E_001', 'CONVERT_INSERT', 'INSERT로 변환', 'UPDATE를 INSERT로 변환하여 처리합니다.', 2, 'Y');
INSERT INTO SYNC_ERROR_ACTION VALUES ('SYNC_E_001', 'SKIP', '무시', '이 건은 처리하지 않습니다.', 3, 'N');

-- CONF_E_001: UPDATE 충돌 (같은컬럼)
INSERT INTO SYNC_ERROR_ACTION VALUES ('CONF_E_001', 'SELECT_ASIS', 'ASIS 값 선택', 'ASIS 시스템의 값을 양쪽에 적용합니다.', 1, 'N');
INSERT INTO SYNC_ERROR_ACTION VALUES ('CONF_E_001', 'SELECT_TOBE', 'TOBE 값 선택', 'TOBE 시스템의 값을 양쪽에 적용합니다.', 2, 'N');
INSERT INTO SYNC_ERROR_ACTION VALUES ('CONF_E_001', 'INPUT_MANUAL', '직접 입력', '새로운 값을 직접 입력합니다.', 3, 'N');
INSERT INTO SYNC_ERROR_ACTION VALUES ('CONF_E_001', 'SKIP', '무시', '이 건은 처리하지 않습니다.', 4, 'N');

-- DATA_E_001: 데이터 타입 불일치
INSERT INTO SYNC_ERROR_ACTION VALUES ('DATA_E_001', 'FIX_VALUE', '값 수정', '올바른 형식으로 값을 수정합니다.', 1, 'Y');
INSERT INTO SYNC_ERROR_ACTION VALUES ('DATA_E_001', 'FIX_RULE', '변환 규칙 수정', '스키마 변환 규칙을 수정합니다.', 2, 'N');
INSERT INTO SYNC_ERROR_ACTION VALUES ('DATA_E_001', 'SKIP', '무시', '이 건은 처리하지 않습니다.', 3, 'N');
```

### 4.2 화면 표시 예시 (스키마 차이 표시)

```
┌─────────────────────────────────────────────────────────────────┐
│  에러 상세 정보                                                  │
├─────────────────────────────────────────────────────────────────┤
│  에러코드: MAP_E_003                                            │
│  에러명: 코드 매핑 없음                                          │
│  심각도: Error                                                   │
│  발생시각: 2025-01-13 14:30:25                                  │
├─────────────────────────────────────────────────────────────────┤
│  스키마 정보                                                     │
│  - 매핑 ID: 1 (BOOK_INFO ↔ TB_BOOK)                             │
│  - 원본 테이블: ASIS.BOOK_INFO                                   │
│  - 대상 테이블: TOBE.TB_BOOK                                     │
│  - PK: 123                                                       │
├─────────────────────────────────────────────────────────────────┤
│  에러 내용                                                       │
│                                                                 │
│  변환 실패 컬럼: CATEGORY → CATEGORY_CD                         │
│  원본 값: '99'                                                   │
│  매핑 그룹: CATEGORY_CODE_MAP                                   │
│  문제: 코드 '99'에 대한 매핑이 정의되지 않았습니다               │
├─────────────────────────────────────────────────────────────────┤
│  처리 방법 선택                                                  │
│                                                                 │
│  (●) 코드 매핑 추가                                              │
│      ASIS '99' → TOBE [______] (예: 'ETC')                      │
│  ( ) 값 직접 입력: CATEGORY_CD = [______]                        │
│  ( ) 무시 - 이 건은 처리하지 않습니다                            │
│                                                                 │
│  [처리] [취소]                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. 에러 처리 프로시저

### 5.1 스키마 매핑 에러 처리

```sql
PROCEDURE LOG_MAPPING_ERROR(
    p_error_code        VARCHAR2,
    p_mapping_id        NUMBER,
    p_source_table      VARCHAR2,
    p_target_table      VARCHAR2,
    p_pk_value          VARCHAR2,
    p_error_message     VARCHAR2,
    p_original_data     CLOB
) AS
    v_alert_required CHAR(1);
BEGIN
    INSERT INTO SYNC_ERROR_LOG (
        ERROR_LOG_ID, ERROR_CODE, MAPPING_ID,
        SOURCE_TABLE, TARGET_TABLE, PK_VALUE,
        ERROR_MESSAGE, CHANGE_DATA_ORIGINAL, STATUS, CREATED_AT
    ) VALUES (
        SYNC_ERROR_LOG_SEQ.NEXTVAL, p_error_code, p_mapping_id,
        p_source_table, p_target_table, p_pk_value,
        p_error_message, p_original_data, 'PENDING', SYSTIMESTAMP
    );

    -- 알림 여부 확인
    SELECT ALERT_REQUIRED INTO v_alert_required
    FROM SYNC_ERROR_CODE WHERE ERROR_CODE = p_error_code;

    IF v_alert_required = 'Y' THEN
        SEND_ERROR_ALERT(p_error_code, p_source_table, p_pk_value, p_error_message);
    END IF;
END;
```

### 5.2 코드 매핑 추가 후 재처리

```sql
PROCEDURE RESOLVE_CODE_MAPPING_ERROR(
    p_error_log_id      NUMBER,
    p_map_group         VARCHAR2,
    p_source_value      VARCHAR2,
    p_target_value      VARCHAR2,
    p_resolved_by       VARCHAR2
) AS
    v_error SYNC_ERROR_LOG%ROWTYPE;
BEGIN
    SELECT * INTO v_error
    FROM SYNC_ERROR_LOG WHERE ERROR_LOG_ID = p_error_log_id;

    -- 코드 매핑 추가 (양방향)
    INSERT INTO SYNC_CODE_MAPPING VALUES (p_map_group, 'ASIS', p_source_value, 'TOBE', p_target_value, '사용자 추가');
    INSERT INTO SYNC_CODE_MAPPING VALUES (p_map_group, 'TOBE', p_target_value, 'ASIS', p_source_value, '사용자 추가');

    -- 원본 데이터로 재처리
    REPROCESS_SYNC_DATA(
        v_error.MAPPING_ID,
        v_error.PK_VALUE,
        v_error.OPERATION_TYPE,
        v_error.CHANGE_DATA_ORIGINAL
    );

    -- 해결 완료 기록
    UPDATE SYNC_ERROR_LOG
    SET STATUS = 'RESOLVED',
        RESOLVED_BY = p_resolved_by,
        RESOLVED_AT = SYSTIMESTAMP,
        RESOLUTION_NOTE = '코드 매핑 추가: ' || p_source_value || ' → ' || p_target_value
    WHERE ERROR_LOG_ID = p_error_log_id;
END;
```

---

## 6. 관련 문서

| 순서 | 문서명 | 내용 |
|:----:|--------|------|
| 01 | `01_CDC_동기화_설계_정리.md` | 전체 설계, 스키마 매핑 |
| 02 | `02_CDC_무한루프_방지_대안.md` | 무한루프 방지 |
| 03 | `03_CDC_동기화_케이스_분류.md` | 케이스별 분류 |
| 04 | `04_CDC_충돌_정책.md` | 충돌 해결 정책 |
| **05** | 본 문서 | 에러 코드 체계 |
