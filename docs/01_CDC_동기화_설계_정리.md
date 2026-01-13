# CDC 동기화 설계 정리

> 최종 수정일: 2025-01-13
> 문서 번호: 01 (가장 먼저 읽어주세요)
> 다음 문서: `02_CDC_무한루프_방지_대안.md`

---

## 1. 프로젝트 배경

### 1.1 현황
- **기존(ASIS)**: 법원도서관 통합관리시스템(CLIMS)
  - 여러 시스템(법원도서관 홈페이지, 법원전자도서관 등)이 CLIMS DB에 직접 접속하여 CRUD 수행

- **목표(TOBE)**: 미래통합관리시스템
  - 표준화된 API를 통한 데이터 교환
  - **스키마 표준화**: 구시스템 데이터 구조를 표준화하여 재설계
  - 시스템 간 직접 DB 접속 제거

### 1.2 핵심 특징: 스키마 변경이 발생함

```
┌─────────────────────────────────────────────────────────────────┐
│  ASIS (구시스템)              TOBE (신시스템)                    │
│  ─────────────────           ─────────────────                  │
│  - 레거시 테이블 구조         - 표준화된 테이블 구조              │
│  - 기존 컬럼명/타입           - 새로운 컬럼명/타입                │
│  - 기존 코드 체계             - 표준 코드 체계                    │
│                                                                 │
│  ※ 단순 복제가 아닌 변환(Transform)이 필수!                      │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 스키마 차이 예시

| 구분 | ASIS (구) | TOBE (신) | 변환 필요 |
|------|-----------|-----------|:---------:|
| 테이블명 | BOOK_INFO | TB_BOOK | O |
| 컬럼명 | REG_DATE | CREATED_AT | O |
| 데이터 타입 | STATUS CHAR(1) | IS_ACTIVE NUMBER(1) | O |
| 코드값 | CATEGORY='01' | CATEGORY_CD='LIT' | O |
| 테이블 구조 | 1개 테이블 | 2개 테이블로 분리 | O |
| 컬럼 추가 | - | CREATED_BY (신규) | O |
| 컬럼 삭제 | OLD_COLUMN | - (삭제됨) | O |

### 1.4 1차 프로젝트 범위
- 전체 시스템 일괄 전환이 아닌 **일부 시스템 시범 적용**
- ASIS/TOBE 공존 기간 동안 **CDC를 통한 양방향 동기화 필요**
- **양방향 동기화 시 스키마 변환이 양쪽에서 발생**

---

## 2. 제약조건

| 구분 | 내용 |
|------|------|
| 기존 테이블 수정 | 컬럼 추가 **불가**, 소스 수정 **불가** |
| 동기화 테이블 | 신규 생성 **가능** |
| 동기화 작업 | CUD **가능** |
| CDC 솔루션 | **미정** |
| 동기화 지연 허용 범위 | **미정** |

---

## 3. 아키텍처

### 3.1 전체 구조

```
┌─────────────────────────────────────────┐       ┌─────────────────────────────────────────┐
│              ASIS (Oracle 11g)          │       │              TOBE (Oracle 11g)          │
│              [구 스키마]                 │       │              [신 스키마 - 표준화]        │
│                                         │       │                                         │
│  ┌───────────────────────────────────┐  │       │  ┌───────────────────────────────────┐  │
│  │          원본 테이블들             │  │       │  │          원본 테이블들             │  │
│  │       (레거시 구조)               │  │       │  │       (표준화된 구조)              │  │
│  └─────────────────┬─────────────────┘  │       │  └─────────────────┬─────────────────┘  │
│                    │                    │       │                    │                    │
│             ┌──────▼──────┐             │       │             ┌──────▼──────┐             │
│             │  Redo Log   │             │       │             │  Redo Log   │             │
│             └──────┬──────┘             │       │             └──────┬──────┘             │
│                    │                    │       │                    │                    │
│             ┌──────▼──────┐             │       │             ┌──────▼──────┐             │
│             │  CDC Agent  │─────────────┼───────┼─────────┐   │  CDC Agent  │             │
│             └─────────────┘             │       │         │   └──────┬──────┘             │
│                                         │       │         │          │                    │
│                    ┌────────────────────┼───────┼─────────┼──────────┘                    │
│                    │                    │       │         │                               │
│                    ▼                    │       │         ▼                               │
│  ┌───────────────────────────────────┐  │       │  ┌───────────────────────────────────┐  │
│  │        CDC_ASIS_TABLES            │  │       │  │        CDC_TOBE_TABLES            │  │
│  │     (TOBE 스키마 데이터 수신)      │  │       │  │     (ASIS 스키마 데이터 수신)      │  │
│  │  ┌─────────────────────────────┐  │  │       │  │  ┌─────────────────────────────┐  │  │
│  │  │ 55개 (단방향 TOBE → ASIS)   │  │  │       │  │  │ 47개 (단방향 ASIS → TOBE)   │  │  │
│  │  └─────────────────────────────┘  │  │       │  │  └─────────────────────────────┘  │  │
│  │  ┌─────────────────────────────┐  │  │       │  │  ┌─────────────────────────────┐  │  │
│  │  │ 22개 (양방향 TOBE ↔ ASIS)   │  │  │       │  │  │ 47개 (양방향 ASIS ↔ TOBE)   │  │  │
│  │  └─────────────────────────────┘  │  │       │  │  └─────────────────────────────┘  │  │
│  └─────────────────┬─────────────────┘  │       │  └─────────────────┬─────────────────┘  │
│                    │                    │       │                    │                    │
│                    ▼ (가공 프로시저)     │       │                    ▼ (가공 프로시저)     │
│  ┌───────────────────────────────────┐  │       │  ┌───────────────────────────────────┐  │
│  │          STAGING_ASIS             │  │       │  │          STAGING_TOBES            │  │
│  │   ┌─────────────────────────┐     │  │       │  │   ┌─────────────────────────┐     │  │
│  │   │ 스키마 변환 수행         │     │  │       │  │   │ 스키마 변환 수행         │     │  │
│  │   │ TOBE→ASIS 매핑 적용     │     │  │       │  │   │ ASIS→TOBE 매핑 적용     │     │  │
│  │   └─────────────────────────┘     │  │       │  │   └─────────────────────────┘     │  │
│  └─────────────────┬─────────────────┘  │       │  └─────────────────┬─────────────────┘  │
│                    │                    │       │                    │                    │
│                    ▼ (WORKER 프로시저)   │       │                    ▼ (WORKER 프로시저)   │
│             ┌─────────────┐             │       │             ┌─────────────┐             │
│             │   WORKER    │             │       │             │   WORKER    │             │
│             └──────┬──────┘             │       │             └──────┬──────┘             │
│                    │                    │       │                    │                    │
│                    ▼                    │       │                    ▼                    │
│           원본 테이블 반영              │       │           원본 테이블 반영              │
│           (ASIS 스키마)                │       │           (TOBE 스키마)                │
│                                         │       │                                         │
└─────────────────────────────────────────┘       └─────────────────────────────────────────┘
```

### 3.2 크로스 전송 구조 (스키마 변환 포함)

```
ASIS CDC Agent ────[ASIS 스키마 데이터]────► CDC_TOBE_TABLES (TOBE DB)
      │                                              │
      │                                              ▼
      │                                     [가공 프로시저]
      │                                     ASIS→TOBE 스키마 변환
      │                                              │
      │                                              ▼
      │                                     STAGING_TOBES (TOBE 스키마)
      │                                              │
      │                                              ▼
      │                                     WORKER → TOBE 원본 반영


TOBE CDC Agent ────[TOBE 스키마 데이터]────► CDC_ASIS_TABLES (ASIS DB)
                                                     │
                                                     ▼
                                            [가공 프로시저]
                                            TOBE→ASIS 스키마 변환
                                                     │
                                                     ▼
                                            STAGING_ASIS (ASIS 스키마)
                                                     │
                                                     ▼
                                            WORKER → ASIS 원본 반영
```

### 3.3 컴포넌트 역할

| 컴포넌트 | 위치 | 역할 |
|----------|------|------|
| CDC Agent | 각 DB | Redo Log 캡처 → **원본 스키마 형태**로 상대방 CDC_TABLES에 전송 |
| CDC_XXX_TABLES | 각 DB | 상대방에서 받은 **원본 스키마** 변경 데이터 임시 저장 |
| 가공 프로시저 | 각 DB | **스키마 변환 수행** (테이블/컬럼/타입/코드 매핑) |
| STAGING_XXX | 각 DB | **변환 완료된** 데이터 대기열 |
| WORKER | 각 DB (프로시저) | STAGING → 원본 테이블 반영 |

---

## 4. 스키마 매핑 설계

### 4.1 매핑 테이블 구조

```sql
-- 테이블 매핑
CREATE TABLE SYNC_TABLE_MAPPING (
    MAPPING_ID      NUMBER PRIMARY KEY,
    SOURCE_SYSTEM   VARCHAR2(10),       -- ASIS or TOBE
    SOURCE_TABLE    VARCHAR2(100),      -- 원본 테이블명
    TARGET_SYSTEM   VARCHAR2(10),       -- ASIS or TOBE
    TARGET_TABLE    VARCHAR2(100),      -- 대상 테이블명
    SYNC_DIRECTION  VARCHAR2(20),       -- UNI_TO_TOBE, UNI_TO_ASIS, BIDIRECTIONAL
    TRANSFORM_PROC  VARCHAR2(100),      -- 가공 프로시저명
    IS_ACTIVE       CHAR(1) DEFAULT 'Y',
    DESCRIPTION     VARCHAR2(500)
);

-- 컬럼 매핑
CREATE TABLE SYNC_COLUMN_MAPPING (
    MAPPING_ID      NUMBER,
    SOURCE_COLUMN   VARCHAR2(100),
    TARGET_COLUMN   VARCHAR2(100),
    DATA_TYPE_SRC   VARCHAR2(50),       -- 원본 데이터 타입
    DATA_TYPE_TGT   VARCHAR2(50),       -- 대상 데이터 타입
    TRANSFORM_TYPE  VARCHAR2(20),       -- DIRECT, EXPRESSION, CODE_MAP, DEFAULT
    TRANSFORM_RULE  VARCHAR2(1000),     -- 변환 규칙
    IS_PK           CHAR(1) DEFAULT 'N',
    IS_NULLABLE     CHAR(1) DEFAULT 'Y',
    DEFAULT_VALUE   VARCHAR2(500),      -- 대상에만 있는 컬럼의 기본값
    FOREIGN KEY (MAPPING_ID) REFERENCES SYNC_TABLE_MAPPING(MAPPING_ID)
);

-- 코드 매핑
CREATE TABLE SYNC_CODE_MAPPING (
    MAP_GROUP       VARCHAR2(50),       -- 매핑 그룹명
    SOURCE_SYSTEM   VARCHAR2(10),
    SOURCE_VALUE    VARCHAR2(100),
    TARGET_SYSTEM   VARCHAR2(10),
    TARGET_VALUE    VARCHAR2(100),
    DESCRIPTION     VARCHAR2(200),
    PRIMARY KEY (MAP_GROUP, SOURCE_SYSTEM, SOURCE_VALUE)
);
```

### 4.2 매핑 예시

```sql
-- 테이블 매핑: ASIS.BOOK_INFO → TOBE.TB_BOOK
INSERT INTO SYNC_TABLE_MAPPING VALUES (
    1, 'ASIS', 'BOOK_INFO', 'TOBE', 'TB_BOOK',
    'BIDIRECTIONAL', 'PROC_TRANSFORM_BOOK', 'Y', '도서 정보 테이블'
);

-- 컬럼 매핑
INSERT INTO SYNC_COLUMN_MAPPING VALUES (1, 'BOOK_ID', 'BOOK_ID', 'NUMBER', 'NUMBER', 'DIRECT', NULL, 'Y', 'N', NULL);
INSERT INTO SYNC_COLUMN_MAPPING VALUES (1, 'BOOK_TITLE', 'TITLE', 'VARCHAR2(200)', 'VARCHAR2(500)', 'DIRECT', NULL, 'N', 'N', NULL);
INSERT INTO SYNC_COLUMN_MAPPING VALUES (1, 'REG_DATE', 'CREATED_AT', 'DATE', 'TIMESTAMP', 'EXPRESSION', 'CAST(:SOURCE_VALUE AS TIMESTAMP)', 'N', 'N', NULL);
INSERT INTO SYNC_COLUMN_MAPPING VALUES (1, 'STATUS', 'IS_ACTIVE', 'CHAR(1)', 'NUMBER(1)', 'CODE_MAP', 'STATUS_YN_MAP', 'N', 'N', NULL);
INSERT INTO SYNC_COLUMN_MAPPING VALUES (1, 'CATEGORY', 'CATEGORY_CD', 'VARCHAR2(2)', 'VARCHAR2(10)', 'CODE_MAP', 'CATEGORY_CODE_MAP', 'N', 'N', NULL);
INSERT INTO SYNC_COLUMN_MAPPING VALUES (1, NULL, 'CREATED_BY', NULL, 'VARCHAR2(50)', 'DEFAULT', NULL, 'N', 'N', 'SYSTEM');
INSERT INTO SYNC_COLUMN_MAPPING VALUES (1, NULL, 'UPDATED_AT', NULL, 'TIMESTAMP', 'DEFAULT', NULL, 'N', 'Y', 'SYSTIMESTAMP');

-- 코드 매핑: STATUS Y/N → 1/0
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_YN_MAP', 'ASIS', 'Y', 'TOBE', '1', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_YN_MAP', 'ASIS', 'N', 'TOBE', '0', '비활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_YN_MAP', 'TOBE', '1', 'ASIS', 'Y', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_YN_MAP', 'TOBE', '0', 'ASIS', 'N', '비활성');

-- 코드 매핑: CATEGORY
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_CODE_MAP', 'ASIS', '01', 'TOBE', 'LIT', '문학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_CODE_MAP', 'ASIS', '02', 'TOBE', 'SCI', '과학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_CODE_MAP', 'TOBE', 'LIT', 'ASIS', '01', '문학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_CODE_MAP', 'TOBE', 'SCI', 'ASIS', '02', '과학');
```

### 4.3 스키마 차이 유형별 처리

| 차이 유형 | ASIS 예시 | TOBE 예시 | 처리 방법 |
|-----------|-----------|-----------|-----------|
| 컬럼명 변경 | BOOK_TITLE | TITLE | 컬럼 매핑 |
| 타입 변경 | DATE | TIMESTAMP | EXPRESSION 변환 |
| 코드값 변경 | 'Y'/'N' | 1/0 | CODE_MAP |
| 신규 컬럼 | (없음) | CREATED_BY | DEFAULT 값 설정 |
| 삭제 컬럼 | OLD_COL | (없음) | 무시 (매핑 안함) |
| 테이블 분리 | 1개 | 2개 | 별도 매핑 정의 |
| 테이블 통합 | 2개 | 1개 | JOIN 처리 |

---

## 5. 테이블 동기화 현황

### 5.1 테이블 분류 기준

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        테이블 동기화 방향 분류                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  단방향 (ASIS → TOBE)                                               │   │
│  │  ─────────────────────                                              │   │
│  │  • ASIS에서만 생성/수정, TOBE는 읽기만                               │   │
│  │  • 예: 레거시 마스터 데이터, 기존 시스템 전용 데이터                  │   │
│  │  • TOBE에서 수정 시 동기화 안됨 (무시)                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  단방향 (TOBE → ASIS)                                               │   │
│  │  ─────────────────────                                              │   │
│  │  • TOBE에서만 생성/수정, ASIS는 읽기만                               │   │
│  │  • 예: 신규 기능 데이터, 표준화 후 신규 추가 항목                     │   │
│  │  • ASIS에서 수정 시 동기화 안됨 (무시)                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  양방향 (ASIS ↔ TOBE)                                               │   │
│  │  ─────────────────────                                              │   │
│  │  • 양쪽 시스템 모두에서 생성/수정 가능                               │   │
│  │  • 예: 도서 정보, 대출 정보, 회원 정보                               │   │
│  │  • 충돌 위험 있음 → 충돌 정책 필수                                   │   │
│  │  • 무한루프 위험 있음 → 루프 방지 필수                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 현황 요약

| 구분 | 테이블 수 | 동기화 방향 | 스키마 변환 | 충돌 위험 |
|------|-----------|-------------|:-----------:|:---------:|
| CDC_ASIS_TABLES | 55개 | 단방향 (TOBE → ASIS) | TOBE→ASIS 변환 | X |
| CDC_ASIS_TABLES | 22개 | 양방향 (TOBE ↔ ASIS) | 양방향 변환 | **O** |
| CDC_TOBE_TABLES | 47개 | 단방향 (ASIS → TOBE) | ASIS→TOBE 변환 | X |
| CDC_TOBE_TABLES | 47개 | 양방향 (ASIS ↔ TOBE) | 양방향 변환 | **O** |

**총 171개 테이블**, 이 중 **69개가 양방향** (충돌 위험)

### 5.3 방향별 특성

| 동기화 방향 | 테이블 수 | CDC 감지 | 특이사항 |
|------------|----------|---------|---------|
| ASIS → TOBE (단방향) | 47개 | ASIS만 | TOBE 변경 무시 |
| TOBE → ASIS (단방향) | 55개 | TOBE만 | ASIS 변경 무시 |
| 양방향 | 69개 | 양쪽 모두 | 충돌 정책 + 루프 방지 필수 |

> **참고**: 테이블 상세 정보는 미정 상태, 매핑 정의 필요
>
> **PoC 환경**: `06_CDC_PoC_환경_구성.md` 참고

---

## 6. 데이터 흐름 (스키마 변환 포함)

### 6.1 ASIS → TOBE (47개 단방향 + 47개 양방향 = 94개)

```
① ASIS 원본 테이블 변경 발생 (ASIS 스키마)
② ASIS Redo Log 기록
③ ASIS CDC Agent 캡처
④ ──네트워크──► CDC_TOBE_TABLES 적재 (ASIS 스키마 그대로)
⑤ 가공 프로시저 실행:
   - ASIS→TOBE 테이블 매핑
   - ASIS→TOBE 컬럼 매핑
   - ASIS→TOBE 타입 변환
   - ASIS→TOBE 코드 변환
   - 신규 컬럼 기본값 설정
⑥ STAGING_TOBES 적재 (TOBE 스키마)
⑦ WORKER(프로시저) 실행 → TOBE 원본 테이블 반영
```

### 6.2 TOBE → ASIS (55개 단방향 + 22개 양방향 = 77개)

```
① TOBE 원본 테이블 변경 발생 (TOBE 스키마)
② TOBE Redo Log 기록
③ TOBE CDC Agent 캡처
④ ──네트워크──► CDC_ASIS_TABLES 적재 (TOBE 스키마 그대로)
⑤ 가공 프로시저 실행:
   - TOBE→ASIS 테이블 매핑
   - TOBE→ASIS 컬럼 매핑
   - TOBE→ASIS 타입 변환 (역변환)
   - TOBE→ASIS 코드 변환 (역변환)
   - ASIS에 없는 컬럼 제거
⑥ STAGING_ASIS 적재 (ASIS 스키마)
⑦ WORKER(프로시저) 실행 → ASIS 원본 테이블 반영
```

---

## 7. 미정 사항 (확인 필요)

- [ ] CDC 솔루션 선정
- [ ] 테이블별 상세 매핑 정의
- [ ] 동기화 지연 허용 범위 (실시간/준실시간/배치)
- [ ] 충돌 해결 정책 테이블별 확정
- [ ] 에러 알림 방식 (이메일, 슬랙, 대시보드 등)

---

## 8. 관련 문서 (읽는 순서)

| 순서 | 문서명 | 내용 |
|:----:|--------|------|
| **01** | 본 문서 | 전체 설계 개요, 스키마 매핑 |
| 02 | `02_CDC_무한루프_방지_대안.md` | 무한루프 방지 메커니즘 |
| 03 | `03_CDC_동기화_케이스_분류.md` | 케이스별 상세 분류 |
| 04 | `04_CDC_충돌_정책.md` | 충돌 해결 정책 |
| 05 | `05_CDC_에러코드_체계.md` | 에러 코드 및 처리 |

---

## 9. 참고 자료

- 초기 설계 도식: `img/CDC 초기 설계.png`
- 충돌 케이스 분석: `docs/CDC 충돌케이스.xlsx`
- 제안요청서(RFP): `docs/제안요청서_2025년 법원도서관 미래통합관리시스템 구축 및 데이터 연계 활용성 강화(구매업무협의_최종).hwp`
