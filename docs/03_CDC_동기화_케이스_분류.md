# CDC 동기화 케이스 분류

> 최종 수정일: 2025-01-13
> 문서 번호: 03
> 이전 문서: `02_CDC_무한루프_방지_대안.md`
> 다음 문서: `04_CDC_충돌_정책.md`

---

## 1. 동기화 방향 개요

| 방향 | 테이블 수 | 특성 | 스키마 변환 | 충돌 가능성 |
|------|-----------|------|:-----------:|:-----------:|
| ASIS → TOBE (구→신) | 47개 단방향 | ASIS가 마스터 | ASIS→TOBE | 낮음 |
| TOBE → ASIS (신→구) | 55개 단방향 | TOBE가 마스터 | TOBE→ASIS | 낮음 |
| ASIS ↔ TOBE (양방향) | 69개 (22+47) | 양쪽 변경 가능 | 양방향 | **높음** |

---

## 2. 스키마 변환 시 발생하는 추가 케이스

기존 케이스에 더해, 스키마가 다르기 때문에 발생하는 케이스들:

| 케이스 | 설명 | 발생 상황 |
|--------|------|-----------|
| T-01 | 테이블명 매핑 오류 | 매핑 정의 누락 |
| T-02 | 컬럼 매핑 오류 | 매핑 정의 누락 |
| T-03 | 데이터 타입 변환 실패 | 변환 규칙 오류 |
| T-04 | 코드값 매핑 실패 | 코드 매핑 누락 |
| T-05 | 대상 전용 컬럼 기본값 오류 | 기본값 설정 오류 |
| T-06 | 테이블 분리/통합 오류 | 복잡한 매핑 오류 |
| T-07 | 역변환 불가 | 단방향 변환만 정의 |

---

## 3. 케이스 발생 매트릭스

| 케이스 | 구→신 | 신→구 | 양방향 | 설명 |
|--------|:-----:|:-----:|:------:|------|
| **기본 케이스** |
| C-01: INSERT 전파 | O | O | O | 신규 데이터 동기화 |
| C-02: UPDATE 전파 | O | O | O | 변경 데이터 동기화 |
| C-03: DELETE 전파 | O | O | O | 삭제 동기화 |
| C-04: FK 순서 위반 | O | O | O | 부모 없이 자식 먼저 도착 |
| **스키마 변환 케이스** |
| T-01: 테이블 매핑 오류 | O | O | O | 매핑 정의 누락/오류 |
| T-02: 컬럼 매핑 오류 | O | O | O | 매핑 정의 누락/오류 |
| T-03: 타입 변환 실패 | O | O | O | 변환 규칙 오류 |
| T-04: 코드 매핑 실패 | O | O | O | 코드 매핑 누락 |
| T-05: 기본값 오류 | O | O | O | 필수 컬럼 기본값 없음 |
| T-06: 테이블 분리/통합 오류 | O | O | O | 복잡한 매핑 실패 |
| T-07: 역변환 불가 | X | X | **O** | 양방향 매핑 미정의 |
| **양방향 전용 케이스** |
| C-08: PK 충돌 (INSERT) | X | X | **O** | 양쪽 동시 INSERT |
| C-09: 동시 UPDATE (같은컬럼) | X | X | **O** | 양쪽 동시 수정 |
| C-10: 동시 UPDATE (다른컬럼) | X | X | **O** | 병합 가능 |
| C-11: UPDATE vs DELETE | X | X | **O** | 수정 vs 삭제 충돌 |
| C-12: DELETE vs DELETE | X | X | **O** | 동시 삭제 |
| C-13: 무한루프 | X | X | **O** | 동기화 재전파 |

---

# Part 1: 단방향 케이스 (구→신, 신→구 공통)

---

## C-01: INSERT 전파

### 설명
원본 시스템에서 신규 레코드 INSERT 시 대상 시스템으로 전파

### 정상 흐름 (스키마 변환 포함)
```
[ASIS → TOBE 예시]

1. ASIS: INSERT INTO BOOK_INFO (BOOK_ID, BOOK_TITLE, STATUS)
         VALUES (1, '한국문학', 'Y')

2. CDC Agent 캡처 → CDC_TOBE_TABLES 적재 (ASIS 스키마)

3. 가공 프로시저 (스키마 변환):
   - 테이블 매핑: BOOK_INFO → TB_BOOK
   - 컬럼 매핑: BOOK_ID→BOOK_ID, BOOK_TITLE→TITLE, STATUS→IS_ACTIVE
   - 타입 변환: STATUS 'Y' → IS_ACTIVE 1
   - 기본값 설정: CREATED_BY='SYSTEM', CREATED_AT=SYSTIMESTAMP

4. STAGING_TOBES 적재:
   INSERT INTO TB_BOOK (BOOK_ID, TITLE, IS_ACTIVE, CREATED_BY, CREATED_AT)
   VALUES (1, '한국문학', 1, 'SYSTEM', SYSTIMESTAMP)

5. WORKER: TOBE.TB_BOOK 테이블에 INSERT
6. 완료
```

### 발생 가능한 에러

| 에러 | 원인 | 해결방법 |
|------|------|----------|
| PK 중복 | 대상에 이미 동일 PK 존재 | UPDATE로 변환 또는 스킵 |
| FK 위반 | 참조 대상 미존재 | 부모 테이블 먼저 처리 (재시도) |
| 테이블 매핑 오류 | 매핑 정의 누락 | 매핑 추가 |
| 컬럼 매핑 오류 | 필수 컬럼 매핑 누락 | 매핑 추가 |
| 기본값 오류 | 대상 필수 컬럼 기본값 없음 | 기본값 설정 |

---

## C-02: UPDATE 전파

### 정상 흐름 (스키마 변환 포함)
```
[ASIS → TOBE 예시]

1. ASIS: UPDATE BOOK_INFO SET BOOK_TITLE = '한국문학개론', STATUS = 'N'
         WHERE BOOK_ID = 1

2. CDC Agent 캡처 (변경된 컬럼만 또는 전체 row)

3. 가공 프로시저 (스키마 변환):
   - 컬럼 매핑: BOOK_TITLE → TITLE, STATUS → IS_ACTIVE
   - 타입 변환: STATUS 'N' → IS_ACTIVE 0

4. STAGING_TOBES 적재:
   UPDATE TB_BOOK SET TITLE = '한국문학개론', IS_ACTIVE = 0
   WHERE BOOK_ID = 1

5. WORKER: TOBE.TB_BOOK 테이블에 UPDATE
```

### 발생 가능한 에러

| 에러 | 원인 | 해결방법 |
|------|------|----------|
| 대상 없음 | 해당 PK 레코드 미존재 | INSERT로 변환 또는 스킵 |
| 타입 변환 실패 | 변환 규칙 오류 | 변환 규칙 수정 |
| 코드 매핑 실패 | 매핑되지 않은 코드값 | 코드 매핑 추가 |

---

## C-03: DELETE 전파

### 정상 흐름 (스키마 변환 포함)
```
[ASIS → TOBE 예시]

1. ASIS: DELETE FROM BOOK_INFO WHERE BOOK_ID = 1

2. CDC Agent 캡처 (삭제된 PK 정보)

3. 가공 프로시저:
   - 테이블 매핑: BOOK_INFO → TB_BOOK
   - PK 매핑: BOOK_ID → BOOK_ID

4. STAGING_TOBES 적재 (OPERATION = 'D')

5. WORKER: DELETE FROM TB_BOOK WHERE BOOK_ID = 1
```

### 발생 가능한 에러

| 에러 | 원인 | 해결방법 |
|------|------|----------|
| 대상 없음 | 이미 삭제됨 | 정상 처리 (스킵) |
| FK 위반 | 자식 레코드 존재 | 자식 먼저 삭제 또는 수동 처리 |

---

## T-01 ~ T-07: 스키마 변환 케이스

### T-01: 테이블 매핑 오류

**시나리오**
```
ASIS에서 NEW_TABLE이라는 테이블이 신규 생성됨
→ SYNC_TABLE_MAPPING에 매핑 정의 없음
→ 가공 프로시저에서 처리 불가
```

**해결**
```sql
-- 매핑 정의 추가
INSERT INTO SYNC_TABLE_MAPPING
VALUES (100, 'ASIS', 'NEW_TABLE', 'TOBE', 'TB_NEW_TABLE', 'BIDIRECTIONAL', ...);
```

---

### T-02: 컬럼 매핑 오류

**시나리오**
```
ASIS.BOOK_INFO에 NEW_COLUMN 추가됨
→ SYNC_COLUMN_MAPPING에 매핑 정의 없음
→ 해당 컬럼 데이터 유실
```

**해결**
```sql
-- 컬럼 매핑 추가
INSERT INTO SYNC_COLUMN_MAPPING
VALUES (1, 'NEW_COLUMN', 'NEW_COL', 'VARCHAR2(100)', 'VARCHAR2(100)', 'DIRECT', NULL, ...);
```

---

### T-03: 데이터 타입 변환 실패

**시나리오**
```
ASIS: PRICE VARCHAR2(20) = '10,000원'
TOBE: PRICE NUMBER

변환 시도: TO_NUMBER('10,000원') → 실패
```

**해결**
```sql
-- 변환 규칙 수정
UPDATE SYNC_COLUMN_MAPPING
SET TRANSFORM_RULE = 'TO_NUMBER(REPLACE(REPLACE(:SOURCE_VALUE, '','', ''''), ''원'', ''''))'
WHERE SOURCE_COLUMN = 'PRICE';
```

---

### T-04: 코드값 매핑 실패

**시나리오**
```
ASIS: CATEGORY = '99' (신규 추가된 코드)
TOBE: CATEGORY_CD 매핑 없음

→ 코드 변환 실패
```

**해결**
```sql
-- 코드 매핑 추가
INSERT INTO SYNC_CODE_MAPPING
VALUES ('CATEGORY_CODE_MAP', 'ASIS', '99', 'TOBE', 'ETC', '기타');
INSERT INTO SYNC_CODE_MAPPING
VALUES ('CATEGORY_CODE_MAP', 'TOBE', 'ETC', 'ASIS', '99', '기타');
```

---

### T-05: 대상 전용 컬럼 기본값 오류

**시나리오**
```
TOBE.TB_BOOK에 CREATED_BY VARCHAR2(50) NOT NULL 추가됨
ASIS에는 해당 컬럼 없음
→ INSERT 시 NOT NULL 에러
```

**해결**
```sql
-- 기본값 설정
INSERT INTO SYNC_COLUMN_MAPPING
VALUES (1, NULL, 'CREATED_BY', NULL, 'VARCHAR2(50)', 'DEFAULT', NULL, 'N', 'N', 'SYSTEM');
```

---

### T-06: 테이블 분리/통합 오류

**시나리오 (테이블 분리)**
```
ASIS: BOOK_INFO (모든 정보 포함)
TOBE: TB_BOOK (기본정보) + TB_BOOK_DETAIL (상세정보)

→ 1:N 매핑 필요
```

**해결**
```sql
-- 다중 매핑 정의
INSERT INTO SYNC_TABLE_MAPPING VALUES (1, 'ASIS', 'BOOK_INFO', 'TOBE', 'TB_BOOK', ...);
INSERT INTO SYNC_TABLE_MAPPING VALUES (2, 'ASIS', 'BOOK_INFO', 'TOBE', 'TB_BOOK_DETAIL', ...);

-- 가공 프로시저에서 순차 처리
PROCEDURE TRANSFORM_BOOK_INFO AS
BEGIN
    -- TB_BOOK 먼저 처리
    PROCESS_MAPPING(1);
    -- TB_BOOK_DETAIL 처리
    PROCESS_MAPPING(2);
END;
```

---

### T-07: 역변환 불가 (양방향 전용)

**시나리오**
```
ASIS→TOBE: STATUS 'Y'/'N' → IS_ACTIVE 1/0 (정의됨)
TOBE→ASIS: IS_ACTIVE 1/0 → STATUS 'Y'/'N' (미정의)

→ TOBE→ASIS 동기화 시 변환 실패
```

**해결**
```sql
-- 역방향 코드 매핑 추가 (반드시 양방향 모두 정의)
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_YN_MAP', 'TOBE', '1', 'ASIS', 'Y', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_YN_MAP', 'TOBE', '0', 'ASIS', 'N', '비활성');
```

---

# Part 2: 양방향 전용 케이스

---

## C-08: PK 충돌 (INSERT vs INSERT)

### 설명
양쪽 시스템에서 동시에 같은 PK로 INSERT 시도
**스키마 변환 후에도 PK가 동일한 경우 발생**

### 시나리오
```
시간 T1: ASIS에서 INSERT INTO BOOK_INFO (BOOK_ID, BOOK_TITLE) VALUES (100, '책A')
시간 T2: TOBE에서 INSERT INTO TB_BOOK (BOOK_ID, TITLE) VALUES (100, '책B')

스키마 변환 후:
- ASIS→TOBE: TB_BOOK에 BOOK_ID=100 INSERT 시도 → 이미 존재
- TOBE→ASIS: BOOK_INFO에 BOOK_ID=100 INSERT 시도 → 이미 존재
```

### 근본적 해결: PK 생성 전략 분리

```sql
-- ASIS 시퀀스: 1 ~ 999,999,999
CREATE SEQUENCE ASIS_BOOK_SEQ START WITH 1 MAXVALUE 999999999;

-- TOBE 시퀀스: 1,000,000,000 ~
CREATE SEQUENCE TOBE_BOOK_SEQ START WITH 1000000000;
```

---

## C-09: 동시 UPDATE 충돌 (같은 컬럼)

### 설명
양쪽에서 동일 레코드의 같은 컬럼을 동시에 수정
**스키마가 다르므로 매핑된 컬럼 기준으로 비교해야 함**

### 시나리오
```
시간 T1: ASIS에서 UPDATE BOOK_INFO SET BOOK_TITLE = '제목A' WHERE BOOK_ID = 1
시간 T2: TOBE에서 UPDATE TB_BOOK SET TITLE = '제목B' WHERE BOOK_ID = 1

스키마 변환 후:
- BOOK_TITLE과 TITLE은 같은 컬럼 (매핑됨)
- 충돌 발생!
```

### 충돌 감지 (매핑 정보 활용)
```sql
FUNCTION IS_SAME_COLUMN(
    p_mapping_id    NUMBER,
    p_source_column VARCHAR2,
    p_target_column VARCHAR2
) RETURN BOOLEAN AS
    v_mapped_column VARCHAR2(100);
BEGIN
    SELECT TARGET_COLUMN INTO v_mapped_column
    FROM SYNC_COLUMN_MAPPING
    WHERE MAPPING_ID = p_mapping_id
      AND SOURCE_COLUMN = p_source_column;

    RETURN v_mapped_column = p_target_column;
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        RETURN FALSE;
END;
```

---

## C-10: 동시 UPDATE 충돌 (다른 컬럼)

### 설명
양쪽에서 동일 레코드의 다른 컬럼을 동시에 수정 (병합 가능)

### 시나리오
```
시간 T1: ASIS에서 UPDATE BOOK_INFO SET BOOK_TITLE = '제목A' WHERE BOOK_ID = 1
시간 T2: TOBE에서 UPDATE TB_BOOK SET IS_ACTIVE = 0 WHERE BOOK_ID = 1

스키마 변환 후:
- BOOK_TITLE → TITLE (제목)
- IS_ACTIVE → STATUS (상태)
- 다른 컬럼이므로 병합 가능

병합 결과:
- TB_BOOK: TITLE = '제목A', IS_ACTIVE = 0
- BOOK_INFO: BOOK_TITLE = '제목A', STATUS = 'N'
```

### 자동 병합 로직
```sql
PROCEDURE AUTO_MERGE_WITH_MAPPING(
    p_mapping_id    NUMBER,
    p_pk_value      VARCHAR2,
    p_change_a      CLOB,   -- ASIS 변경
    p_change_b      CLOB    -- TOBE 변경
) AS
    v_cols_a_mapped     COLUMN_LIST_TYPE;
    v_cols_b            COLUMN_LIST_TYPE;
    v_overlap           COLUMN_LIST_TYPE;
BEGIN
    -- ASIS 변경 컬럼을 TOBE 컬럼으로 매핑
    v_cols_a_mapped := MAP_COLUMNS_TO_TARGET(p_mapping_id, EXTRACT_COLUMNS(p_change_a));
    v_cols_b := EXTRACT_COLUMNS(p_change_b);

    -- 중복 체크 (매핑된 컬럼 기준)
    v_overlap := GET_OVERLAPPING(v_cols_a_mapped, v_cols_b);

    IF v_overlap.COUNT > 0 THEN
        -- 동일 컬럼 충돌 → C-09 케이스
        RAISE_CONFLICT('UPD_UPD_SAME', ...);
    ELSE
        -- 다른 컬럼 → 자동 병합
        MERGE_AND_APPLY_BOTH(...);
    END IF;
END;
```

---

## C-11: UPDATE vs DELETE 충돌

### 시나리오 (스키마 변환 포함)
```
시간 T1: ASIS에서 UPDATE BOOK_INFO SET BOOK_TITLE = '수정' WHERE BOOK_ID = 1
시간 T2: TOBE에서 DELETE FROM TB_BOOK WHERE BOOK_ID = 1

스키마 변환 후:
- ASIS→TOBE: UPDATE TB_BOOK SET TITLE = '수정' → 대상 없음 (삭제됨)
- TOBE→ASIS: DELETE FROM BOOK_INFO WHERE BOOK_ID = 1 → 수정된 데이터 삭제

충돌!
```

---

## C-13: 무한루프 (스키마 변환 시)

### 스키마 변환 시 무한루프 특징
```
1. ASIS에서 BOOK_INFO.BOOK_TITLE = 'A' 변경
2. 변환 후 TB_BOOK.TITLE = 'A' 로 TOBE 반영
3. TOBE CDC 캡처: TB_BOOK.TITLE = 'A'
4. 역변환 후 BOOK_INFO.BOOK_TITLE = 'A' 로 ASIS 전송
5. 데이터가 같지만 컬럼명이 다르므로 단순 비교 불가!
```

### 해결: 02 문서의 해시 기반 + 버전 기반 방어 적용

---

# Part 3: 케이스별 처리 요약

## 처리 방식 매트릭스

| 케이스 | 자동처리 | 재시도 | 수동처리 | 에러코드 |
|--------|:--------:|:------:|:--------:|----------|
| **기본 케이스** |
| C-01 INSERT | O | △ | △ | SYNC_E_001~003 |
| C-02 UPDATE | O | △ | △ | SYNC_E_001~003 |
| C-03 DELETE | O | X | △ | SYNC_E_004 |
| C-04 FK 위반 | △ | **O** | △ | SYNC_E_003 |
| **스키마 변환 케이스** |
| T-01 테이블매핑오류 | X | X | **O** | MAP_E_001 |
| T-02 컬럼매핑오류 | X | X | **O** | MAP_E_002 |
| T-03 타입변환실패 | X | X | **O** | DATA_E_001 |
| T-04 코드매핑실패 | X | X | **O** | MAP_E_003 |
| T-05 기본값오류 | X | X | **O** | DATA_E_002 |
| T-06 분리/통합오류 | X | X | **O** | MAP_E_004 |
| T-07 역변환불가 | X | X | **O** | MAP_E_005 |
| **양방향 케이스** |
| C-08 PK충돌 | X | X | **O** | CONF_E_004 |
| C-09 동시UPDATE(같은컬럼) | △ | X | **O** | CONF_E_001 |
| C-10 동시UPDATE(다른컬럼) | **O** | X | X | CONF_E_002 |
| C-11 UPDATE vs DELETE | X | X | **O** | CONF_E_003 |
| C-12 DELETE vs DELETE | **O** | X | X | - |
| C-13 무한루프 | **O** | X | X | - |

---

## 관련 문서

| 순서 | 문서명 | 내용 |
|:----:|--------|------|
| 01 | `01_CDC_동기화_설계_정리.md` | 전체 설계, 스키마 매핑 |
| 02 | `02_CDC_무한루프_방지_대안.md` | 무한루프 방지 |
| **03** | 본 문서 | 케이스별 분류 |
| 04 | `04_CDC_충돌_정책.md` | 충돌 해결 정책 |
| 05 | `05_CDC_에러코드_체계.md` | 에러 코드 |
