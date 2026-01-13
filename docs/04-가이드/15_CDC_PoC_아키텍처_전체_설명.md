# CDC PoC 아키텍처 전체 설명

> 이 문서는 CDC(Change Data Capture) PoC 프로젝트의 전체 아키텍처와 데이터 흐름을 설명합니다.

---

## 1. 프로젝트 배경

```
┌─────────────────────────────────────────────────────────────────┐
│                    법원도서관 시스템 전환                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   기존 시스템 (ASIS)              새 시스템 (TOBE)                │
│   ┌─────────────┐                ┌─────────────┐               │
│   │   CLIMS     │   ──전환──▶    │ 미래통합    │               │
│   │ (레거시)    │                │ 관리시스템   │               │
│   └─────────────┘                └─────────────┘               │
│                                                                 │
│   문제: 1차에서 일부만 전환 → 양쪽 시스템이 동시에 운영됨          │
│   해결: CDC로 실시간 양방향 데이터 동기화 필요                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.1 핵심 문제: 스키마 불일치

ASIS(레거시)와 TOBE(신규) 시스템은 **스키마(테이블 구조)가 다릅니다**.

| ASIS (레거시) | TOBE (신규) | 변환 내용 |
|--------------|-------------|----------|
| `BOOK_TITLE` | `TITLE` | 컬럼명 변경 |
| `AUTHOR` | `AUTHOR_NAME` | 컬럼명 변경 |
| `STATUS` ('Y'/'N') | `IS_ACTIVE` (1/0) | 데이터 타입 변경 |
| `CATEGORY` ('01') | `CATEGORY_CD` ('LIT') | 코드값 변환 |

```
ASIS 스키마                      TOBE 스키마
─────────────                    ──────────
BOOK_TITLE   ──────────────▶    TITLE
AUTHOR       ──────────────▶    AUTHOR_NAME
STATUS ('Y'/'N') ──────────▶    IS_ACTIVE (1/0)
CATEGORY ('01') ───────────▶    CATEGORY_CD ('LIT')
```

### 1.2 테이블 분류

| 동기화 방향 | 테이블 수 | 설명 |
|------------|----------|------|
| ASIS → TOBE (단방향) | 47개 | ASIS에서만 관리, TOBE는 읽기만 |
| TOBE → ASIS (단방향) | 55개 | TOBE에서만 관리, ASIS는 읽기만 |
| 양방향 | 69개 | 양쪽 모두 수정 가능, 충돌 위험 |
| **총계** | **171개** | |

---

## 2. PoC 아키텍처

### 2.1 전체 시스템 구성도

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Docker Compose 환경                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────┐                                      ┌──────────────┐    │
│  │ ASIS Oracle  │                                      │ TOBE Oracle  │    │
│  │   (15210)    │                                      │   (15220)    │    │
│  ├──────────────┤                                      ├──────────────┤    │
│  │ • BOOK_INFO  │                                      │ • TB_BOOK    │    │
│  │ • MEMBER_INFO│                                      │ • TB_MEMBER  │    │
│  │ • LEGACY_CODE│                                      │ • TB_NEW_SVC │    │
│  │              │                                      │              │    │
│  │ [CDC 테이블] │                                      │ [CDC 테이블] │    │
│  │ • CDC_ASIS_* │◀─┐                              ┌──▶│ • CDC_TOBE_* │    │
│  │ • STAGING_*  │  │                              │   │ • STAGING_*  │    │
│  │              │  │                              │   │              │    │
│  │ [WORKER]     │  │                              │   │ [WORKER]     │    │
│  │ 5초마다 실행  │  │                              │   │ 5초마다 실행  │    │
│  └──────┬───────┘  │                              │   └──────┬───────┘    │
│         │          │                              │          │            │
│         ▼          │                              │          ▼            │
│  ┌──────────────┐  │   ┌──────────────────┐      │   ┌──────────────┐    │
│  │  Debezium    │  │   │                  │      │   │  Debezium    │    │
│  │  Connector   │──┼──▶│      Kafka       │◀─────┼───│  Connector   │    │
│  │  (ASIS)      │  │   │                  │      │   │  (TOBE)      │    │
│  └──────────────┘  │   └────────┬─────────┘      │   └──────────────┘    │
│                    │            │                │                       │
│                    │            ▼                │                       │
│                    │   ┌──────────────────┐      │                       │
│                    │   │   Sync Service   │      │                       │
│                    │   │  (Java/Spring)   │      │                       │
│                    │   │                  │      │                       │
│                    │   │ Kafka Consumer   │      │                       │
│                    └───│ → CDC 테이블 저장 │──────┘                       │
│                        └──────────────────┘                              │
│                                                                          │
│  ┌──────────────┐      ┌──────────────────┐                              │
│  │  Zookeeper   │      │    Kafka UI      │  ← 모니터링 (8081)           │
│  └──────────────┘      └──────────────────┘                              │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.2 컴포넌트 설명

| 컴포넌트 | 기술 스택 | 역할 | 포트 |
|---------|----------|------|------|
| **ASIS Oracle** | Oracle XE 21c | 기존 시스템 DB | 15210 |
| **TOBE Oracle** | Oracle XE 21c | 신규 시스템 DB | 15220 |
| **Debezium** | Debezium 2.4 | DB 변경 감지 (CDC) | - |
| **Kafka** | Confluent 7.5 | 메시지 브로커 | 9092 |
| **Zookeeper** | Confluent 7.5 | Kafka 클러스터 관리 | 2181 |
| **Kafka Connect** | Debezium Connect | Debezium 커넥터 호스팅 | 8083 |
| **Sync Service** | Java 17 + Spring Boot | Kafka → CDC 테이블 저장 | 8082 |
| **Kafka UI** | Provectus Kafka UI | 모니터링 대시보드 | 8081 |

---

## 3. 데이터 흐름 상세

### 3.1 ASIS → TOBE 동기화 흐름

```
[1단계] ASIS에서 데이터 변경
┌─────────────────────────────────────────────────────────────┐
│  INSERT INTO BOOK_INFO (BOOK_ID, BOOK_TITLE, ...)           │
│  VALUES (100, '테스트도서', ...);                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
[2단계] Debezium이 변경 감지 (LogMiner 사용)
┌─────────────────────────────────────────────────────────────┐
│  Debezium Connector가 Oracle Redo Log를 읽어서              │
│  변경 사항을 Kafka 토픽으로 전송                              │
│  → 토픽: asis.ASIS_USER.BOOK_INFO                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
[3단계] Sync Service가 Kafka 메시지 소비
┌─────────────────────────────────────────────────────────────┐
│  @KafkaListener가 메시지 수신                                │
│  → TOBE DB의 CDC_TOBE_BOOK 테이블에 INSERT                  │
│                                                             │
│  CDC_TOBE_BOOK:                                             │
│  ┌────────┬──────────┬─────────┬────────────┬──────────┐   │
│  │CDC_SEQ │OPERATION │ BOOK_ID │ BOOK_TITLE │PROCESSED │   │
│  ├────────┼──────────┼─────────┼────────────┼──────────┤   │
│  │   40   │  INSERT  │   100   │ 테스트도서  │    N     │   │
│  └────────┴──────────┴─────────┴────────────┴──────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
[4단계] WORKER 프로시저가 처리 (5초마다 자동 실행)
┌─────────────────────────────────────────────────────────────┐
│  SP_WORKER_BOOK 프로시저:                                    │
│                                                             │
│  (1) CDC → STAGING (스키마 변환)                             │
│      BOOK_TITLE → TITLE                                     │
│      AUTHOR → AUTHOR_NAME                                   │
│      'Y' → 1, 'N' → 0                                       │
│      '01' → 'LIT' (카테고리 코드 변환)                        │
│                                                             │
│  (2) STAGING → TB_BOOK (원본 테이블에 반영)                   │
│      INSERT INTO TB_BOOK (TITLE, AUTHOR_NAME, IS_ACTIVE...) │
│                                                             │
│  (3) 해시 기록 (무한루프 방지)                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
[5단계] 동기화 완료
┌─────────────────────────────────────────────────────────────┐
│  TOBE DB의 TB_BOOK 테이블에 데이터 반영 완료!                 │
│                                                             │
│  TB_BOOK:                                                   │
│  ┌─────────┬────────────┬────────────┬───────────┐         │
│  │ BOOK_ID │   TITLE    │AUTHOR_NAME │ IS_ACTIVE │         │
│  ├─────────┼────────────┼────────────┼───────────┤         │
│  │   100   │ 테스트도서  │  테스트저자  │     1     │         │
│  └─────────┴────────────┴────────────┴───────────┘         │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 TOBE → ASIS 동기화 흐름

TOBE → ASIS도 동일한 흐름으로 진행되며, **역변환**이 적용됩니다:

| TOBE 값 | → | ASIS 값 |
|---------|---|---------|
| `TITLE` | → | `BOOK_TITLE` |
| `AUTHOR_NAME` | → | `AUTHOR` |
| `IS_ACTIVE` (1) | → | `STATUS` ('Y') |
| `CATEGORY_CD` ('LIT') | → | `CATEGORY` ('01') |

### 3.3 양방향 동기화 흐름도

```
┌─────────────┐                                    ┌─────────────┐
│    ASIS     │                                    │    TOBE     │
│   Oracle    │                                    │   Oracle    │
├─────────────┤                                    ├─────────────┤
│             │                                    │             │
│ BOOK_INFO   │───▶ Debezium ───▶ Kafka ───▶ Sync │ CDC_TOBE_*  │
│             │                   Service          │      │      │
│             │                                    │      ▼      │
│             │                                    │  STAGING_*  │
│             │                                    │      │      │
│             │                                    │   WORKER    │
│             │                                    │      │      │
│             │                                    │      ▼      │
│             │                                    │  TB_BOOK    │
│             │                                    │             │
├─────────────┤                                    ├─────────────┤
│             │                                    │             │
│ CDC_ASIS_*  │◀─── Sync ◀─── Kafka ◀─── Debezium─│  TB_BOOK    │
│      │      │     Service                        │             │
│      ▼      │                                    │             │
│  STAGING_*  │                                    │             │
│      │      │                                    │             │
│   WORKER    │                                    │             │
│      │      │                                    │             │
│      ▼      │                                    │             │
│ BOOK_INFO   │                                    │             │
│             │                                    │             │
└─────────────┘                                    └─────────────┘
```

---

## 4. 테이블 구조

### 4.1 원본 테이블 (예: BOOK)

**ASIS: BOOK_INFO**
```sql
CREATE TABLE BOOK_INFO (
    BOOK_ID     NUMBER PRIMARY KEY,
    BOOK_TITLE  VARCHAR2(200),
    AUTHOR      VARCHAR2(100),
    CATEGORY    VARCHAR2(2),      -- '01', '02', '03'
    STATUS      CHAR(1),          -- 'Y', 'N'
    REG_DATE    DATE,
    MOD_DATE    DATE
);
```

**TOBE: TB_BOOK**
```sql
CREATE TABLE TB_BOOK (
    BOOK_ID      NUMBER PRIMARY KEY,
    TITLE        VARCHAR2(500),
    AUTHOR_NAME  VARCHAR2(200),
    CATEGORY_CD  VARCHAR2(10),    -- 'LIT', 'SCI', 'HIS'
    IS_ACTIVE    NUMBER(1),       -- 1, 0
    CREATED_AT   TIMESTAMP,
    UPDATED_AT   TIMESTAMP
);
```

### 4.2 CDC 테이블 구조

CDC 테이블은 Debezium이 감지한 변경 데이터를 저장합니다.

```sql
CREATE TABLE CDC_TOBE_BOOK (
    CDC_SEQ          NUMBER PRIMARY KEY,     -- 순번
    OPERATION        VARCHAR2(10),           -- INSERT, UPDATE, DELETE
    BOOK_ID          NUMBER,                 -- PK 값
    BOOK_TITLE       VARCHAR2(200),          -- 원본 데이터
    AUTHOR           VARCHAR2(100),
    CATEGORY         VARCHAR2(2),
    STATUS           CHAR(1),
    SOURCE_TIMESTAMP TIMESTAMP,              -- 원본 변경 시간
    RECEIVED_AT      TIMESTAMP,              -- 수신 시간
    PROCESSED_YN     CHAR(1) DEFAULT 'N',    -- 처리 여부
    PROCESSED_AT     TIMESTAMP,              -- 처리 시간
    ERROR_MSG        VARCHAR2(1000),         -- 에러 메시지
    CHANGE_HASH      VARCHAR2(64)            -- 무한루프 방지용 해시
);
```

### 4.3 STAGING 테이블 구조

STAGING 테이블은 스키마 변환 후 원본 반영 전 대기 데이터를 저장합니다.

```sql
CREATE TABLE STAGING_TOBE_BOOK (
    STAGING_SEQ  NUMBER PRIMARY KEY,
    CDC_SEQ      NUMBER,
    OPERATION    VARCHAR2(10),
    -- 변환된 스키마로 저장
    BOOK_ID      NUMBER,
    TITLE        VARCHAR2(500),      -- BOOK_TITLE → TITLE
    AUTHOR_NAME  VARCHAR2(200),      -- AUTHOR → AUTHOR_NAME
    CATEGORY_CD  VARCHAR2(10),       -- '01' → 'LIT'
    IS_ACTIVE    NUMBER(1),          -- 'Y' → 1
    PROCESSED_YN CHAR(1) DEFAULT 'N'
);
```

---

## 5. 무한루프 방지 메커니즘

양방향 동기화에서 **무한루프**가 발생할 수 있습니다:

```
ASIS INSERT → TOBE 동기화 → TOBE 변경 감지 → ASIS 동기화 → ASIS 변경 감지 → ...
```

### 5.1 해시 기반 방지

```
┌─────────────────────────────────────────────────────────────┐
│                    무한루프 방지 로직                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [1] 데이터 반영 시 해시 생성 & 저장                          │
│      HASH = SHA256(테이블명 + PK + 연산 + 데이터)            │
│      → CDC_PROCESSED_HASH 테이블에 저장                     │
│                                                             │
│  [2] CDC 데이터 처리 전 해시 체크                             │
│      IF 동일 해시가 5분 이내 존재 THEN                        │
│          → SKIP (무한루프로 판단)                            │
│          → PROCESSED_YN = 'S' (Skipped)                     │
│      ELSE                                                   │
│          → 정상 처리                                         │
│                                                             │
│  [3] 오래된 해시 정리 (10분 경과 시 삭제)                      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. WORKER 프로시저 처리 흐름

```
┌─────────────────────────────────────────────────────────────┐
│              SP_WORKER_BOOK 처리 흐름                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Oracle Scheduler Job (5초마다 실행)                         │
│           │                                                 │
│           ▼                                                 │
│  ┌─────────────────┐                                        │
│  │ SP_RUN_ALL_WORKERS                                       │
│  │    │                                                     │
│  │    ├─▶ SP_CLEANUP_HASH (오래된 해시 정리)                 │
│  │    ├─▶ SP_WORKER_BOOK                                    │
│  │    ├─▶ SP_WORKER_MEMBER                                  │
│  │    └─▶ SP_WORKER_LEGACY_CODE                             │
│  └─────────────────┘                                        │
│                                                             │
│  SP_WORKER_BOOK 상세:                                        │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [1단계] CDC → STAGING                                │    │
│  │     FOR rec IN (SELECT * FROM CDC_TOBE_BOOK         │    │
│  │                 WHERE PROCESSED_YN = 'N')           │    │
│  │         IF 무한루프 체크 THEN SKIP                   │    │
│  │         ELSE                                        │    │
│  │             스키마 변환 후 STAGING 테이블에 INSERT    │    │
│  │             CDC 테이블 PROCESSED_YN = 'Y'           │    │
│  └─────────────────────────────────────────────────────┘    │
│                       │                                     │
│                       ▼                                     │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [2단계] STAGING → 원본 테이블                        │    │
│  │     FOR rec IN (SELECT * FROM STAGING_TOBE_BOOK     │    │
│  │                 WHERE PROCESSED_YN = 'N')           │    │
│  │         CASE OPERATION                              │    │
│  │             WHEN 'INSERT' → INSERT INTO TB_BOOK     │    │
│  │             WHEN 'UPDATE' → UPDATE TB_BOOK          │    │
│  │             WHEN 'DELETE' → DELETE FROM TB_BOOK     │    │
│  │         END                                         │    │
│  │         해시 기록 (무한루프 방지)                     │    │
│  │         동기화 로그 기록                             │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 7. PoC 구축 완료 항목

```
┌─────────────────────────────────────────────────────────────────┐
│                      PoC 구축 완료 항목                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  [인프라 구축]                                                   │
│  ✅ Docker Compose로 전체 환경 구성                              │
│     - Oracle XE x 2 (ASIS, TOBE)                                │
│     - Kafka + Zookeeper                                         │
│     - Debezium Kafka Connect                                    │
│     - Sync Service (Java Spring Boot)                           │
│     - Kafka UI (모니터링)                                        │
│                                                                 │
│  [DB 스키마 구축]                                                │
│  ✅ ASIS: BOOK_INFO, MEMBER_INFO, LEGACY_CODE 테이블            │
│  ✅ TOBE: TB_BOOK, TB_MEMBER, TB_NEW_SERVICE 테이블             │
│  ✅ CDC 테이블: CDC_ASIS_*, CDC_TOBE_* (변경 데이터 수신용)      │
│  ✅ STAGING 테이블: 스키마 변환 중간 단계                         │
│  ✅ 코드 매핑 테이블: SYNC_CODE_MAPPING                          │
│                                                                 │
│  [WORKER 프로시저]                                               │
│  ✅ SP_WORKER_BOOK, SP_WORKER_MEMBER 등                         │
│  ✅ 스키마 변환 로직 (컬럼명, 타입, 코드값 변환)                   │
│  ✅ 무한루프 방지 (해시 기반)                                     │
│  ✅ Oracle Scheduler Job (5초마다 자동 실행)                     │
│                                                                 │
│  [Debezium 설정]                                                 │
│  ✅ ARCHIVELOG 모드 활성화                                       │
│  ✅ Supplemental Logging 설정                                    │
│  ✅ c##dbzuser 생성 및 권한 부여                                 │
│  ✅ ASIS/TOBE 커넥터 등록                                        │
│                                                                 │
│  [테스트 완료]                                                   │
│  ✅ ASIS → TOBE 동기화 성공                                      │
│  ✅ TOBE → ASIS 동기화 성공                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. 향후 작업

```
┌─────────────────────────────────────────────────────────────────┐
│                        향후 작업                                 │
├─────────────────────────────────────────────────────────────────┤
│  ⬜ 충돌 해결 정책 구현 (양방향 동시 수정 시)                      │
│  ⬜ 에러 처리 및 재시도 로직 강화                                 │
│  ⬜ 모니터링/알림 시스템                                         │
│  ⬜ 171개 실제 테이블에 대한 매핑 정의                            │
│  ⬜ 성능 테스트 및 튜닝                                          │
│  ⬜ 프로덕션 CDC 솔루션 선정                                     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. 핵심 포인트 요약

| 구분 | 설명 |
|------|------|
| **Debezium** | Oracle의 변경 사항을 실시간으로 감지해서 Kafka로 전송 |
| **Kafka** | 메시지 브로커 역할, 변경 이벤트를 안정적으로 전달 |
| **Sync Service** | Kafka 메시지를 받아서 상대 DB의 CDC 테이블에 저장 |
| **WORKER** | CDC 테이블 → 스키마 변환 → 원본 테이블 반영 (DB 프로시저) |
| **무한루프 방지** | 해시 기반으로 동기화된 데이터가 다시 돌아오는 것을 차단 |

---

## 10. 관련 문서

- [01_CDC_동기화_설계_정리.md](01_CDC_동기화_설계_정리.md) - 전체 설계
- [02_CDC_무한루프_방지_대안.md](02_CDC_무한루프_방지_대안.md) - 무한루프 방지
- [06_CDC_PoC_환경_구성.md](06_CDC_PoC_환경_구성.md) - Docker 환경 구성
- [10_테스트_및_확인_방법.md](10_테스트_및_확인_방법.md) - 테스트 가이드
- [14_개발_진행_기록.md](14_개발_진행_기록.md) - 개발 진행 로그
