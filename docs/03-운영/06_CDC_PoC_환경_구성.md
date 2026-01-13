# CDC PoC 환경 구성

> 최종 수정일: 2025-01-13
> 문서 번호: 06
> 이전 문서: `05_CDC_에러코드_체계.md`

---

## 1. 개요

### 1.1 PoC란?

**PoC = Proof of Concept (개념 증명)**

```
┌─────────────────────────────────────────────────────────────────┐
│  본격적인 개발 전에 핵심 아이디어가 실제로 동작하는지            │
│  작은 규모로 구현하여 검증하는 단계                             │
│                                                                 │
│  실제 프로젝트: 171개 테이블, Oracle 11g, CDC 솔루션 미정       │
│        ↓                                                        │
│  PoC 환경: 4개 테이블, Oracle XE, Debezium                      │
│        ↓                                                        │
│  검증 완료 → 문제점 파악 → 설계 수정 → 본 개발 착수             │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 목적
- CDC 동기화 로직을 실제와 유사한 환경에서 검증
- 무한루프 방지, 충돌 해결, 스키마 변환 로직 테스트
- 기술적 가능 여부 및 문제점 사전 파악

### 1.3 검증 항목

| 항목 | 검증 내용 |
|------|----------|
| CDC 동작 | Debezium이 Oracle 변경을 정상 감지하는지 |
| 스키마 변환 | ASIS↔TOBE 매핑이 정상 동작하는지 |
| 무한루프 방지 | 해시/버전 기반 차단이 동작하는지 |
| 충돌 해결 | LWW 등 정책이 정상 적용되는지 |
| 네트워크 | 2개 DB 간 통신이 정상인지 |

### 1.4 기술 스택

| 구성요소 | 선정 | 버전 | 비고 |
|---------|------|------|------|
| DBMS | Oracle XE | 21c | 무료, 프로덕션 Oracle과 호환 |
| CDC 솔루션 | Debezium | 2.x | 오픈소스, Oracle 지원 |
| 메시지 브로커 | Apache Kafka | 3.x | Debezium 필수 의존성 |
| 컨테이너 | Docker Compose | - | 전체 환경 통합 관리 |

---

## 2. 아키텍처

### 2.1 원래 설계 vs PoC 설계

**원래 설계 (기초 도식)**
```
┌───────────────────────┐                         ┌───────────────────────┐
│       ASIS DB         │                         │       TOBE DB         │
│                       │                         │                       │
│  ┌─────────────────┐  │                         │  ┌─────────────────┐  │
│  │   원본 테이블    │  │                         │  │   원본 테이블    │  │
│  └────────┬────────┘  │                         │  └────────┬────────┘  │
│           │           │                         │           │           │
│           ▼           │                         │           ▼           │
│  ┌─────────────────┐  │    직접 전송 (Network)  │  ┌─────────────────┐  │
│  │  CDC Agent      │──┼────────────────────────►│  │  CDC_TOBE_      │  │
│  │  (ASIS 전용)    │  │                         │  │  TABLES         │  │
│  └─────────────────┘  │                         │  └─────────────────┘  │
│                       │                         │                       │
│  ┌─────────────────┐  │                         │  ┌─────────────────┐  │
│  │  CDC_ASIS_      │◄─┼────────────────────────┤│  │  CDC Agent      │  │
│  │  TABLES         │  │    직접 전송 (Network)  │  │  (TOBE 전용)    │  │
│  └─────────────────┘  │                         │  └─────────────────┘  │
│                       │                         │                       │
└───────────────────────┘                         └───────────────────────┘

  ※ 각 DB에 독립된 CDC Agent 존재
  ※ Agent가 직접 상대 DB에 INSERT
```

**PoC 설계 (Debezium 사용)**
```
┌───────────────────────┐                         ┌───────────────────────┐
│       ASIS DB         │                         │       TOBE DB         │
│                       │                         │                       │
│  ┌─────────────────┐  │                         │  ┌─────────────────┐  │
│  │   원본 테이블    │  │                         │  │   원본 테이블    │  │
│  └────────┬────────┘  │                         │  └────────┬────────┘  │
│           │           │                         │           │           │
│           │ LogMiner  │                         │  LogMiner │           │
│           ▼           │                         │           ▼           │
└───────────┼───────────┘                         └───────────┼───────────┘
            │                                                 │
            ▼                                                 ▼
┌───────────────────────┐                         ┌───────────────────────┐
│  Debezium Connector   │                         │  Debezium Connector   │
│  (ASIS 전용 Agent)    │                         │  (TOBE 전용 Agent)    │
│                       │                         │                       │
│  - ASIS DB만 감시     │                         │  - TOBE DB만 감시     │
│  - asis.* topic 발행  │                         │  - tobe.* topic 발행  │
└───────────┬───────────┘                         └───────────┬───────────┘
            │                                                 │
            ▼                                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                              Kafka                                      │
│  ┌──────────────────────────┐     ┌──────────────────────────┐          │
│  │     asis.* topics        │     │     tobe.* topics        │          │
│  │  (ASIS 변경 이벤트)       │     │  (TOBE 변경 이벤트)       │          │
│  └────────────┬─────────────┘     └─────────────┬────────────┘          │
└───────────────┼─────────────────────────────────┼────────────────────────┘
                │                                 │
                ▼                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         CDC Sync Service                                │
│  ┌────────────────────────────┐   ┌────────────────────────────┐        │
│  │  ASIS Consumer             │   │  TOBE Consumer             │        │
│  │  - asis.* topic 구독       │   │  - tobe.* topic 구독       │        │
│  │  - TOBE DB에 전송 ─────────┼──►│  - ASIS DB에 전송 ─────────┼──►     │
│  └────────────────────────────┘   └────────────────────────────┘        │
└───────────────┼─────────────────────────────────┼────────────────────────┘
                │                                 │
                ▼                                 ▼
┌───────────────────────┐                         ┌───────────────────────┐
│       TOBE DB         │                         │       ASIS DB         │
│  ┌─────────────────┐  │                         │  ┌─────────────────┐  │
│  │  CDC_TOBE_      │  │                         │  │  CDC_ASIS_      │  │
│  │  TABLES         │  │                         │  │  TABLES         │  │
│  └────────┬────────┘  │                         │  └────────┬────────┘  │
│           │           │                         │           │           │
│           ▼           │                         │           ▼           │
│  ┌─────────────────┐  │                         │  ┌─────────────────┐  │
│  │  STAGING_TOBE   │  │                         │  │  STAGING_ASIS   │  │
│  └────────┬────────┘  │                         │  └────────┬────────┘  │
│           │           │                         │           │           │
│           ▼           │                         │           ▼           │
│  ┌─────────────────┐  │                         │  ┌─────────────────┐  │
│  │  WORKER 프로시저 │  │                         │  │  WORKER 프로시저 │  │
│  └────────┬────────┘  │                         │  └────────┬────────┘  │
│           │           │                         │           │           │
│           ▼           │                         │           ▼           │
│  ┌─────────────────┐  │                         │  ┌─────────────────┐  │
│  │   원본 테이블    │  │                         │  │   원본 테이블    │  │
│  └─────────────────┘  │                         │  └─────────────────┘  │
└───────────────────────┘                         └───────────────────────┘

  ※ CDC Agent(Debezium Connector)는 2개로 독립 동작
  ※ 중간에 Kafka가 있어 "공유"처럼 보이나 논리적으로 분리됨
  ※ Sync Service가 상대 DB로의 전송 역할 담당
```

### 2.2 원래 설계 → PoC 컴포넌트 매핑

원래 설계의 각 컴포넌트가 PoC에서 어떻게 구현되는지 설명합니다.

#### 2.2.1 컴포넌트 매핑표

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     원래 설계 → PoC 컴포넌트 매핑                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  원래 설계                          PoC (Debezium)                          │
│  ──────────                         ──────────────                          │
│                                                                             │
│  ┌─────────────────┐                ┌─────────────────────────────────┐     │
│  │  CDC Agent      │                │  Debezium Connector             │     │
│  │  (ASIS 전용)    │  ═══════════►  │  (kafka-connect 컨테이너 내부)   │     │
│  └─────────────────┘                │  - Oracle LogMiner로 변경 감지   │     │
│                                     └─────────────────────────────────┘     │
│                                                                             │
│  ┌─────────────────┐                ┌─────────────────────────────────┐     │
│  │  네트워크 전송   │                │  Kafka + Sync Service           │     │
│  │  (직접 INSERT)  │  ═══════════►  │  - Kafka: 메시지 전달            │     │
│  └─────────────────┘                │  - Sync Service: DB INSERT 실행  │     │
│                                     └─────────────────────────────────┘     │
│                                                                             │
│  ┌─────────────────┐                ┌─────────────────────────────────┐     │
│  │  CDC_TABLES     │                │  CDC_TABLES (동일)              │     │
│  │  STAGING        │  ═══════════►  │  STAGING (동일)                 │     │
│  │  WORKER 프로시저 │                │  WORKER 프로시저 (동일)          │     │
│  └─────────────────┘                └─────────────────────────────────┘     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 2.2.2 상세 매핑 설명

| 원래 설계 | PoC 구현 | 역할 | 왜 이렇게? |
|----------|---------|------|-----------|
| **CDC Agent (ASIS)** | Debezium ASIS Connector | ASIS DB 변경 감지 | Debezium이 무료 CDC 중 가장 성숙함 |
| **CDC Agent (TOBE)** | Debezium TOBE Connector | TOBE DB 변경 감지 | 각각 독립 Connector로 분리 |
| **네트워크 전송** | Kafka + Sync Service | 상대 DB로 데이터 전달 | Debezium이 Kafka 기반이라 필수 |
| **CDC_TABLES** | CDC_TABLES (동일) | 수신 데이터 저장 | 그대로 유지 |
| **가공 프로시저** | 가공 프로시저 (동일) | 스키마 변환 | 그대로 유지 |
| **STAGING** | STAGING (동일) | 변환 데이터 대기 | 그대로 유지 |
| **WORKER** | WORKER (동일) | 원본 테이블 반영 | 그대로 유지 |

#### 2.2.3 왜 Kafka가 중간에 있는가?

```
원래 설계:
  CDC Agent ──────────────────────────────► 상대 DB
             직접 네트워크 연결 (INSERT)


PoC (Debezium):
  CDC Agent ───► Kafka ───► Sync Service ───► 상대 DB
             (1)        (2)              (3)

  (1) Debezium은 변경사항을 Kafka로 발행하도록 설계됨 (아키텍처 특성)
  (2) Kafka가 메시지를 안정적으로 보관
  (3) Sync Service가 Kafka에서 읽어서 DB에 INSERT
```

**Kafka가 필요한 이유:**
- Debezium은 Kafka Connect 기반으로 설계된 CDC 솔루션
- 직접 DB INSERT 기능이 없음 → Kafka로 발행 후 Consumer가 처리
- 장점: 메시지 유실 방지, 재처리 가능, 모니터링 용이

**실제 프로덕션에서는:**
- 선정된 CDC 솔루션에 따라 Kafka 없이 직접 전송 가능할 수 있음
- PoC는 Debezium을 사용하므로 Kafka 경유

#### 2.2.4 데이터 흐름 비교

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  [원래 설계] ASIS → TOBE 흐름                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ASIS DB                                            TOBE DB               │
│  ┌─────────┐                                       ┌─────────┐             │
│  │원본 테이블│                                       │CDC_TOBE_│             │
│  └────┬────┘                                       │TABLES   │             │
│       │ 변경 발생                                   └────┬────┘             │
│       ▼                                                 │                  │
│  ┌─────────┐        네트워크 전송 (직접)            ┌────▼────┐             │
│  │CDC Agent│ ─────────────────────────────────────►│  INSERT │             │
│  └─────────┘                                       └─────────┘             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  [PoC] ASIS → TOBE 흐름                                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ASIS DB                                            TOBE DB               │
│  ┌─────────┐                                       ┌─────────┐             │
│  │원본 테이블│                                       │CDC_TOBE_│             │
│  └────┬────┘                                       │TABLES   │             │
│       │ 변경 발생                                   └────┬────┘             │
│       ▼                                                 ▲                  │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐     ┌─────┴─────┐            │
│  │Debezium │────►│  Kafka  │────►│  Sync   │────►│  INSERT   │            │
│  │Connector│     │  Topic  │     │ Service │     │           │            │
│  └─────────┘     └─────────┘     └─────────┘     └───────────┘            │
│       │                │               │                                   │
│       │                │               │                                   │
│     ┌─┴────────────────┴───────────────┴─┐                                │
│     │      네트워크 전송 역할 (3단계)       │                                │
│     └────────────────────────────────────┘                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 2.2.5 핵심 정리

| 질문 | 답변 |
|------|------|
| CDC Agent는 몇 개? | **2개** (ASIS용, TOBE용 각각 독립) |
| 원래 설계와 동일한가? | 역할은 동일, 구현 방식만 다름 |
| Kafka는 왜 필요? | Debezium 아키텍처 특성 (Kafka 기반) |
| DB 내부 로직은 바뀌나? | **동일** (CDC_TABLES, STAGING, WORKER 그대로) |

> **결론**: PoC에서 Kafka + Sync Service가 "네트워크 전송" 역할을 대신합니다.
> DB 내부의 처리 로직(가공 프로시저, STAGING, WORKER)은 원래 설계와 완전히 동일합니다.

### 2.3 Docker 컨테이너 구성

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Docker Network (cdc-net)                            │
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │asis-oracle  │  │tobe-oracle  │  │  zookeeper  │  │       kafka         │ │
│  │ (Port 1521) │  │ (Port 1522) │  │ (Port 2181) │  │    (Port 9092)      │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│                                                                             │
│  ┌───────────────────────────────┐  ┌─────────────────────────────────────┐ │
│  │       kafka-connect           │  │          sync-service               │ │
│  │       (Port 8083)             │  │          (Port 8080)                │ │
│  │                               │  │                                     │ │
│  │  - Debezium ASIS Connector    │  │  - Kafka Consumer                   │ │
│  │  - Debezium TOBE Connector    │  │  - DB INSERT 수행                   │ │
│  └───────────────────────────────┘  └─────────────────────────────────────┘ │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         kafka-ui (Port 8081)                            ││
│  │                         - 모니터링 대시보드                              ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘

총 7개 컨테이너:
1. asis-oracle    - ASIS DB (Oracle XE)
2. tobe-oracle    - TOBE DB (Oracle XE)
3. zookeeper      - Kafka 의존성
4. kafka          - 메시지 브로커
5. kafka-connect  - Debezium Connector 호스트
6. sync-service   - Kafka→DB 전송 서비스
7. kafka-ui       - 모니터링 UI
```

### 2.4 데이터 흐름

```
[ASIS → TOBE 흐름]
1. ASIS 원본 테이블 변경
2. Oracle LogMiner가 변경 감지
3. Debezium ASIS Connector가 캡처
4. Kafka asis.* topic으로 발행
5. CDC Sync Service가 consume
6. TOBE DB의 CDC_TOBE_TABLES에 INSERT
7. DB 트리거/프로시저가 스키마 변환
8. STAGING_TOBE에 적재
9. WORKER 프로시저가 TOBE 원본 반영

[TOBE → ASIS 흐름]
1. TOBE 원본 테이블 변경
2. Oracle LogMiner가 변경 감지
3. Debezium TOBE Connector가 캡처
4. Kafka tobe.* topic으로 발행
5. CDC Sync Service가 consume
6. ASIS DB의 CDC_ASIS_TABLES에 INSERT
7. DB 트리거/프로시저가 스키마 변환
8. STAGING_ASIS에 적재
9. WORKER 프로시저가 ASIS 원본 반영
```

---

## 3. 샘플 테이블 설계

### 3.1 테이블 분류

| 분류 | 테이블명 | 설명 | 동기화 방향 |
|------|---------|------|-------------|
| 단방향 (ASIS→TOBE) | LEGACY_CODE | 레거시 코드 마스터 | ASIS에서만 관리 |
| 단방향 (TOBE→ASIS) | NEW_SERVICE | 신규 서비스 데이터 | TOBE에서만 관리 |
| 양방향 | BOOK | 도서 정보 | 양쪽에서 수정 가능 |
| 양방향 | MEMBER | 회원 정보 | 양쪽에서 수정 가능 |

### 3.2 ASIS 스키마 (구 시스템)

```sql
-- 레거시 코드 마스터 (ASIS→TOBE 단방향)
CREATE TABLE LEGACY_CODE (
    CODE_ID     VARCHAR2(10) PRIMARY KEY,
    CODE_NAME   VARCHAR2(100),
    USE_YN      CHAR(1) DEFAULT 'Y',
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 도서 정보 (양방향)
CREATE TABLE BOOK_INFO (
    BOOK_ID     NUMBER PRIMARY KEY,
    BOOK_TITLE  VARCHAR2(200) NOT NULL,
    AUTHOR      VARCHAR2(100),
    CATEGORY    VARCHAR2(2),          -- '01', '02', '03'
    STATUS      CHAR(1) DEFAULT 'Y',  -- 'Y', 'N'
    REG_DATE    DATE DEFAULT SYSDATE,
    MOD_DATE    DATE
);

-- 회원 정보 (양방향)
CREATE TABLE MEMBER_INFO (
    MEMBER_ID   NUMBER PRIMARY KEY,
    MEMBER_NAME VARCHAR2(50) NOT NULL,
    EMAIL       VARCHAR2(100),
    MEMBER_TYPE CHAR(1),              -- 'A', 'B', 'C'
    STATUS      CHAR(1) DEFAULT 'Y',
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 신규 서비스 수신용 (TOBE→ASIS 단방향)
CREATE TABLE NEW_SERVICE_RECV (
    SERVICE_ID  NUMBER PRIMARY KEY,
    SERVICE_NM  VARCHAR2(100),
    SVC_TYPE    VARCHAR2(10),
    USE_YN      CHAR(1),
    REG_DATE    DATE
);
```

### 3.3 TOBE 스키마 (신 시스템 - 표준화)

```sql
-- 레거시 코드 수신용 (ASIS→TOBE 단방향)
CREATE TABLE TB_LEGACY_CODE (
    CODE_ID         VARCHAR2(10) PRIMARY KEY,
    CODE_NAME       VARCHAR2(200),
    IS_ACTIVE       NUMBER(1) DEFAULT 1,
    CREATED_AT      TIMESTAMP DEFAULT SYSTIMESTAMP,
    CREATED_BY      VARCHAR2(50) DEFAULT 'SYSTEM'
);

-- 도서 정보 (양방향)
CREATE TABLE TB_BOOK (
    BOOK_ID         NUMBER PRIMARY KEY,
    TITLE           VARCHAR2(500) NOT NULL,
    AUTHOR_NAME     VARCHAR2(200),
    CATEGORY_CD     VARCHAR2(10),         -- 'LIT', 'SCI', 'HIS'
    IS_ACTIVE       NUMBER(1) DEFAULT 1,  -- 1, 0
    CREATED_AT      TIMESTAMP DEFAULT SYSTIMESTAMP,
    UPDATED_AT      TIMESTAMP,
    CREATED_BY      VARCHAR2(50) DEFAULT 'SYSTEM',
    UPDATED_BY      VARCHAR2(50)
);

-- 회원 정보 (양방향)
CREATE TABLE TB_MEMBER (
    MEMBER_ID       NUMBER PRIMARY KEY,
    MEMBER_NAME     VARCHAR2(100) NOT NULL,
    EMAIL_ADDR      VARCHAR2(200),
    MEMBER_TYPE_CD  VARCHAR2(10),         -- 'ADMIN', 'USER', 'GUEST'
    IS_ACTIVE       NUMBER(1) DEFAULT 1,
    CREATED_AT      TIMESTAMP DEFAULT SYSTIMESTAMP,
    UPDATED_AT      TIMESTAMP
);

-- 신규 서비스 (TOBE→ASIS 단방향)
CREATE TABLE TB_NEW_SERVICE (
    SERVICE_ID      NUMBER PRIMARY KEY,
    SERVICE_NAME    VARCHAR2(200) NOT NULL,
    SERVICE_TYPE_CD VARCHAR2(20),
    IS_ACTIVE       NUMBER(1) DEFAULT 1,
    CREATED_AT      TIMESTAMP DEFAULT SYSTIMESTAMP
);
```

### 3.4 스키마 매핑 정의

```sql
-- ASIS.BOOK_INFO ↔ TOBE.TB_BOOK (양방향)
INSERT INTO SYNC_TABLE_MAPPING VALUES (
    1, 'ASIS', 'BOOK_INFO', 'TOBE', 'TB_BOOK', 'BIDIRECTIONAL', 'PKG_CDC_TRANSFORM.BOOK_A2T', 'Y', '도서'
);

-- 컬럼 매핑
-- ASIS Column      TOBE Column       변환
-- BOOK_ID         BOOK_ID           DIRECT
-- BOOK_TITLE      TITLE             DIRECT (컬럼명만 다름)
-- AUTHOR          AUTHOR_NAME       DIRECT
-- CATEGORY        CATEGORY_CD       CODE_MAP (01→LIT, 02→SCI, 03→HIS)
-- STATUS          IS_ACTIVE         CODE_MAP (Y→1, N→0)
-- REG_DATE        CREATED_AT        DATE→TIMESTAMP
-- MOD_DATE        UPDATED_AT        DATE→TIMESTAMP
-- (없음)          CREATED_BY        DEFAULT 'SYSTEM'
-- (없음)          UPDATED_BY        DEFAULT NULL

-- ASIS.MEMBER_INFO ↔ TOBE.TB_MEMBER (양방향)
INSERT INTO SYNC_TABLE_MAPPING VALUES (
    2, 'ASIS', 'MEMBER_INFO', 'TOBE', 'TB_MEMBER', 'BIDIRECTIONAL', 'PKG_CDC_TRANSFORM.MEMBER_A2T', 'Y', '회원'
);

-- ASIS.LEGACY_CODE → TOBE.TB_LEGACY_CODE (단방향)
INSERT INTO SYNC_TABLE_MAPPING VALUES (
    3, 'ASIS', 'LEGACY_CODE', 'TOBE', 'TB_LEGACY_CODE', 'UNI_TO_TOBE', 'PKG_CDC_TRANSFORM.LEGACY_A2T', 'Y', '레거시코드'
);

-- TOBE.TB_NEW_SERVICE → ASIS.NEW_SERVICE_RECV (단방향)
INSERT INTO SYNC_TABLE_MAPPING VALUES (
    4, 'TOBE', 'TB_NEW_SERVICE', 'ASIS', 'NEW_SERVICE_RECV', 'UNI_TO_ASIS', 'PKG_CDC_TRANSFORM.SERVICE_T2A', 'Y', '신규서비스'
);

-- 코드 매핑
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '01', 'TOBE', 'LIT', '문학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '02', 'TOBE', 'SCI', '과학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'ASIS', '03', 'TOBE', 'HIS', '역사');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'TOBE', 'LIT', 'ASIS', '01', '문학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'TOBE', 'SCI', 'ASIS', '02', '과학');
INSERT INTO SYNC_CODE_MAPPING VALUES ('CATEGORY_MAP', 'TOBE', 'HIS', 'ASIS', '03', '역사');

INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'ASIS', 'Y', 'TOBE', '1', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'ASIS', 'N', 'TOBE', '0', '비활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'TOBE', '1', 'ASIS', 'Y', '활성');
INSERT INTO SYNC_CODE_MAPPING VALUES ('STATUS_MAP', 'TOBE', '0', 'ASIS', 'N', '비활성');

INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'ASIS', 'A', 'TOBE', 'ADMIN', '관리자');
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'ASIS', 'B', 'TOBE', 'USER', '일반');
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'ASIS', 'C', 'TOBE', 'GUEST', '게스트');
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'TOBE', 'ADMIN', 'ASIS', 'A', '관리자');
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'TOBE', 'USER', 'ASIS', 'B', '일반');
INSERT INTO SYNC_CODE_MAPPING VALUES ('MEMBER_TYPE_MAP', 'TOBE', 'GUEST', 'ASIS', 'C', '게스트');
```

---

## 4. Docker Compose 구성

### 4.1 디렉토리 구조

```
poc/
├── docker-compose.yml
├── .env
├── asis-oracle/
│   ├── init/
│   │   ├── 01_create_user.sql
│   │   ├── 02_create_tables.sql
│   │   ├── 03_create_cdc_tables.sql
│   │   └── 04_create_procedures.sql
│   └── Dockerfile (필요시)
├── tobe-oracle/
│   ├── init/
│   │   ├── 01_create_user.sql
│   │   ├── 02_create_tables.sql
│   │   ├── 03_create_cdc_tables.sql
│   │   └── 04_create_procedures.sql
│   └── Dockerfile (필요시)
├── kafka/
│   └── connect/
│       └── connectors/
├── sync-service/
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
└── scripts/
    ├── setup.sh
    ├── test-scenario-1.sh   # 단방향 ASIS→TOBE
    ├── test-scenario-2.sh   # 단방향 TOBE→ASIS
    ├── test-scenario-3.sh   # 양방향 정상
    ├── test-scenario-4.sh   # 양방향 충돌
    └── test-scenario-5.sh   # 무한루프 방지
```

### 4.2 docker-compose.yml

```yaml
version: '3.8'

services:
  # ===== ASIS Oracle XE =====
  asis-oracle:
    image: gvenzl/oracle-xe:21-slim
    container_name: asis-oracle
    environment:
      ORACLE_PASSWORD: asis1234
      ORACLE_DATABASE: ASISDB
      APP_USER: asis_user
      APP_USER_PASSWORD: asis1234
    ports:
      - "1521:1521"
    volumes:
      - asis-oracle-data:/opt/oracle/oradata
      - ./asis-oracle/init:/container-entrypoint-initdb.d
    networks:
      - cdc-net
    healthcheck:
      test: ["CMD", "healthcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 10

  # ===== TOBE Oracle XE =====
  tobe-oracle:
    image: gvenzl/oracle-xe:21-slim
    container_name: tobe-oracle
    environment:
      ORACLE_PASSWORD: tobe1234
      ORACLE_DATABASE: TOBEDB
      APP_USER: tobe_user
      APP_USER_PASSWORD: tobe1234
    ports:
      - "1522:1521"
    volumes:
      - tobe-oracle-data:/opt/oracle/oradata
      - ./tobe-oracle/init:/container-entrypoint-initdb.d
    networks:
      - cdc-net
    healthcheck:
      test: ["CMD", "healthcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 10

  # ===== Zookeeper =====
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    networks:
      - cdc-net

  # ===== Kafka =====
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    networks:
      - cdc-net

  # ===== Kafka Connect (Debezium) =====
  kafka-connect:
    image: debezium/connect:2.4
    container_name: kafka-connect
    depends_on:
      - kafka
      - asis-oracle
      - tobe-oracle
    ports:
      - "8083:8083"
    environment:
      BOOTSTRAP_SERVERS: kafka:29092
      GROUP_ID: cdc-connect-cluster
      CONFIG_STORAGE_TOPIC: cdc_connect_configs
      OFFSET_STORAGE_TOPIC: cdc_connect_offsets
      STATUS_STORAGE_TOPIC: cdc_connect_statuses
      KEY_CONVERTER: org.apache.kafka.connect.json.JsonConverter
      VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
    volumes:
      - ./kafka/connect/connectors:/kafka/connect/connectors
    networks:
      - cdc-net

  # ===== CDC Sync Service =====
  sync-service:
    build: ./sync-service
    container_name: sync-service
    depends_on:
      - kafka
      - asis-oracle
      - tobe-oracle
    ports:
      - "8080:8080"
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      ASIS_DB_URL: jdbc:oracle:thin:@asis-oracle:1521/XEPDB1
      ASIS_DB_USER: asis_user
      ASIS_DB_PASSWORD: asis1234
      TOBE_DB_URL: jdbc:oracle:thin:@tobe-oracle:1521/XEPDB1
      TOBE_DB_USER: tobe_user
      TOBE_DB_PASSWORD: tobe1234
    networks:
      - cdc-net

  # ===== Kafka UI (모니터링) =====
  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    container_name: kafka-ui
    depends_on:
      - kafka
    ports:
      - "8081:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: cdc-cluster
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
      KAFKA_CLUSTERS_0_KAFKACONNECT_0_NAME: debezium
      KAFKA_CLUSTERS_0_KAFKACONNECT_0_ADDRESS: http://kafka-connect:8083
    networks:
      - cdc-net

volumes:
  asis-oracle-data:
  tobe-oracle-data:

networks:
  cdc-net:
    driver: bridge
```

---

## 5. 테스트 시나리오

### 5.1 시나리오 목록

| 번호 | 시나리오 | 검증 항목 |
|------|---------|----------|
| S-01 | 단방향 ASIS→TOBE | LEGACY_CODE INSERT → TB_LEGACY_CODE 동기화 |
| S-02 | 단방향 TOBE→ASIS | TB_NEW_SERVICE INSERT → NEW_SERVICE_RECV 동기화 |
| S-03 | 양방향 정상 (ASIS 선행) | BOOK_INFO UPDATE → TB_BOOK 동기화, 충돌 없음 |
| S-04 | 양방향 정상 (TOBE 선행) | TB_BOOK UPDATE → BOOK_INFO 동기화, 충돌 없음 |
| S-05 | 양방향 충돌 (동시 수정) | 양쪽 동시 UPDATE → 충돌 정책 적용 검증 |
| S-06 | 무한루프 방지 | 동기화 후 재전파 차단 확인 |
| S-07 | 스키마 변환 검증 | 코드값 변환 정확성 확인 |
| S-08 | 에러 처리 | 매핑 실패 시 에러 로깅 확인 |

### 5.2 시나리오 S-05: 양방향 충돌 테스트

```sql
-- 1. 초기 데이터 (양쪽 동일)
-- ASIS: BOOK_INFO (BOOK_ID=1, BOOK_TITLE='테스트도서', STATUS='Y')
-- TOBE: TB_BOOK (BOOK_ID=1, TITLE='테스트도서', IS_ACTIVE=1)

-- 2. 거의 동시에 양쪽에서 수정 (5초 이내)
-- ASIS (10:00:00.000)
UPDATE BOOK_INFO SET BOOK_TITLE = '수정제목-ASIS', MOD_DATE = SYSDATE WHERE BOOK_ID = 1;

-- TOBE (10:00:00.500)
UPDATE TB_BOOK SET TITLE = '수정제목-TOBE', UPDATED_AT = SYSTIMESTAMP WHERE BOOK_ID = 1;

-- 3. 예상 결과 (LWW 정책인 경우)
-- TOBE가 0.5초 늦게 수정 → TOBE 값 우선
-- 최종: 양쪽 모두 '수정제목-TOBE'
```

### 5.3 시나리오 S-06: 무한루프 방지 테스트

```sql
-- 1. ASIS에서 UPDATE
UPDATE BOOK_INFO SET BOOK_TITLE = '루프테스트' WHERE BOOK_ID = 1;

-- 2. CDC 감지 → TOBE로 전파 → TB_BOOK 반영

-- 3. TOBE 반영 시 생성된 변경이 다시 ASIS로 전파되는지 확인
-- 예상: 해시/버전 기반으로 차단되어야 함

-- 4. 검증 방법
SELECT * FROM CDC_SYNC_LOG WHERE STATUS = 'LOOP_BLOCKED';
```

---

## 6. 실행 방법

### 6.1 환경 시작

```bash
# 1. 프로젝트 디렉토리로 이동
cd poc

# 2. Docker Compose 실행
docker-compose up -d

# 3. 서비스 상태 확인 (모두 healthy가 될 때까지 대기)
docker-compose ps

# 4. Oracle 준비 완료 확인 (약 2-3분 소요)
docker logs asis-oracle 2>&1 | grep "DATABASE IS READY"
docker logs tobe-oracle 2>&1 | grep "DATABASE IS READY"
```

### 6.2 Debezium Connector 등록

```bash
# ASIS Connector
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d '{
  "name": "asis-connector",
  "config": {
    "connector.class": "io.debezium.connector.oracle.OracleConnector",
    "database.hostname": "asis-oracle",
    "database.port": "1521",
    "database.user": "c##dbzuser",
    "database.password": "dbz",
    "database.dbname": "XEPDB1",
    "database.server.name": "asis",
    "table.include.list": "ASIS_USER.BOOK_INFO,ASIS_USER.MEMBER_INFO,ASIS_USER.LEGACY_CODE",
    "database.history.kafka.bootstrap.servers": "kafka:29092",
    "database.history.kafka.topic": "schema-changes.asis"
  }
}'

# TOBE Connector
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d '{
  "name": "tobe-connector",
  "config": {
    "connector.class": "io.debezium.connector.oracle.OracleConnector",
    "database.hostname": "tobe-oracle",
    "database.port": "1521",
    "database.user": "c##dbzuser",
    "database.password": "dbz",
    "database.dbname": "XEPDB1",
    "database.server.name": "tobe",
    "table.include.list": "TOBE_USER.TB_BOOK,TOBE_USER.TB_MEMBER,TOBE_USER.TB_NEW_SERVICE",
    "database.history.kafka.bootstrap.servers": "kafka:29092",
    "database.history.kafka.topic": "schema-changes.tobe"
  }
}'
```

### 6.3 테스트 실행

```bash
# 시나리오별 테스트 스크립트 실행
./scripts/test-scenario-1.sh  # 단방향 ASIS→TOBE
./scripts/test-scenario-5.sh  # 충돌 테스트
./scripts/test-scenario-6.sh  # 무한루프 방지
```

### 6.4 모니터링

```
- Kafka UI: http://localhost:8081
- Kafka Connect API: http://localhost:8083
- Sync Service: http://localhost:8080
```

---

## 7. 주의사항

### 7.1 Oracle XE 제한사항
- CPU: 2 스레드
- RAM: 2GB
- 저장소: 12GB
- PoC 용도로는 충분

### 7.2 Debezium Oracle Connector 요구사항
- LogMiner 또는 XStream 필요
- Oracle XE는 LogMiner 지원
- Supplemental logging 활성화 필요

```sql
-- Oracle에서 실행 (SYSDBA)
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
```

### 7.3 리소스 요구사항
- 최소 RAM: 8GB (권장 16GB)
- 디스크: 20GB 이상
- Docker Desktop 메모리 설정 확인

---

## 8. 확장 가이드

### 8.1 테이블 추가 방법
1. ASIS/TOBE 스키마에 테이블 생성
2. SYNC_TABLE_MAPPING에 매핑 추가
3. SYNC_COLUMN_MAPPING에 컬럼 매핑 추가
4. 필요시 SYNC_CODE_MAPPING에 코드 매핑 추가
5. Debezium Connector 설정에 테이블 추가
6. CDC_TABLES, STAGING 테이블 추가

### 8.2 충돌 정책 변경
```sql
-- SYNC_CONFLICT_POLICY 테이블에서 정책 변경
UPDATE SYNC_CONFLICT_POLICY
SET DEFAULT_POLICY = 'ASIS_PRIORITY'
WHERE MAPPING_ID = 1;
```

---

## 9. 관련 문서

| 문서 | 참고 내용 |
|------|----------|
| 01_CDC_동기화_설계_정리.md | 전체 설계, 스키마 매핑 |
| 02_CDC_무한루프_방지_대안.md | 무한루프 방지 로직 |
| 03_CDC_동기화_케이스_분류.md | 동기화 케이스 |
| 04_CDC_충돌_정책.md | 충돌 해결 정책 |
| 05_CDC_에러코드_체계.md | 에러 처리 |
