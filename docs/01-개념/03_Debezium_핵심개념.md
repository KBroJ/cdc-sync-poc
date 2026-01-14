# Debezium 핵심 개념

> **목표**: Debezium이 Oracle 변경사항을 감지하기 위해 필요한 설정들을 이해합니다.

---

## 1. 전체 흐름

### 1.1 데이터 흐름도

```
┌─────────────────────────────────────────────────────────────────┐
│                        Oracle Database                          │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐ │
│  │ 원본 테이블  │───▶│  Redo Log   │───▶│  Archive Log        │ │
│  │ (TB_BOOK)   │    │ (변경 기록)  │    │ (영구 보관)          │ │
│  └─────────────┘    └─────────────┘    └─────────────────────┘ │
│                                                │                │
│                                         ┌──────▼──────┐        │
│                                         │  LogMiner   │        │
│                                         │ (로그 분석)  │        │
│                                         └──────┬──────┘        │
└────────────────────────────────────────────────┼───────────────┘
                                                 │
                                          ┌──────▼──────┐
                                          │  Debezium   │
                                          │ (CDC Agent) │
                                          └──────┬──────┘
                                                 │
                                          ┌──────▼──────┐
                                          │   Kafka     │
                                          │ (메시지 전송)│
                                          └─────────────┘
```

### 1.2 필요한 4가지 설정

| 순서 | 설정 | 역할 | 비유 |
|:----:|------|------|------|
| 1 | ARCHIVELOG 모드 | 변경 기록을 영구 보관 | 자동 백업 켜기 |
| 2 | c##dbzuser | Debezium 전용 접속 계정 | 출입증 발급 |
| 3 | Supplemental Logging | 변경 전/후 값 기록 | 상세 기록 모드 |
| 4 | Debezium Connector | 감시 대상 테이블 지정 | CCTV 설치 |

---

## 2. ARCHIVELOG 모드

### 2.1 이게 뭔가요?

Oracle이 **변경 내역(Redo Log)을 영구 보관**하는 모드입니다.

### 2.2 비유로 이해하기

```
┌─────────────────────────────────────────────────────────────┐
│  일반 모드 (NOARCHIVELOG):                                    │
│  ────────────────────────                                    │
│  메모장에 기록 → 다 차면 지우고 다시 씀                         │
│  → 과거 기록이 사라짐                                          │
│                                                             │
│  아카이브 모드 (ARCHIVELOG):                                   │
│  ─────────────────────────                                   │
│  메모장에 기록 → 다 차면 복사해서 보관 → 새 메모장 시작          │
│  → 과거 기록이 영구 보존됨                                     │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 왜 CDC에 필요한가요?

Debezium은 **Oracle의 과거 변경 기록(Archive Log)**을 읽어서 "어떤 데이터가 변경됐는지" 알아냅니다.

```
ARCHIVELOG 모드가 아니면:
- Redo Log가 다 차면 덮어쓰여짐
- 과거 기록이 없으니 CDC가 불가능!
```

### 2.4 설정 확인 방법

```sql
-- 현재 모드 확인
SELECT LOG_MODE FROM V$DATABASE;

-- 결과:
-- LOG_MODE
-- --------
-- ARCHIVELOG    ← 이거면 OK!
-- NOARCHIVELOG  ← CDC 불가능
```

### 2.5 설정 방법

```sql
-- SYSDBA로 접속
-- 1. DB 종료
SHUTDOWN IMMEDIATE;

-- 2. 마운트 모드로 시작 (완전 시작 아님)
STARTUP MOUNT;

-- 3. ARCHIVELOG 모드 활성화
ALTER DATABASE ARCHIVELOG;

-- 4. 완전히 열기
ALTER DATABASE OPEN;
```

---

## 3. c##dbzuser (Debezium 전용 사용자)

### 3.1 이게 뭔가요?

Debezium이 Oracle에 접속할 때 사용하는 **전용 계정**입니다.

### 3.2 왜 `c##`이 붙나요?

Oracle 12c부터 **Common User** 개념이 생겼습니다:

```
┌─────────────────────────────────────────┐
│              CDB (Container)            │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐ │
│  │  PDB1   │  │  PDB2   │  │  PDB3   │ │
│  │(XEPDB1) │  │         │  │         │ │
│  └─────────┘  └─────────┘  └─────────┘ │
│                                         │
│  c##dbzuser는 CDB + 모든 PDB에 접근 가능  │
└─────────────────────────────────────────┘
```

| 사용자 타입 | 이름 규칙 | 접근 범위 |
|------------|----------|----------|
| **Common User** | `c##`으로 시작 | CDB + 모든 PDB |
| Local User | 아무 이름 | 해당 PDB만 |

Debezium은 CDB(컨테이너)와 PDB 모두에 접근해야 해서 **Common User**가 필요합니다.

### 3.3 필요한 권한들

```sql
-- 각 권한의 용도
GRANT CREATE SESSION TO c##dbzuser;        -- DB 접속
GRANT SELECT ON V_$DATABASE TO c##dbzuser; -- DB 정보 조회
GRANT FLASHBACK ANY TABLE TO c##dbzuser;   -- 과거 데이터 조회
GRANT SELECT ANY TABLE TO c##dbzuser;      -- 테이블 읽기
GRANT SELECT_CATALOG_ROLE TO c##dbzuser;   -- 시스템 카탈로그 조회
GRANT EXECUTE_CATALOG_ROLE TO c##dbzuser;  -- LogMiner 실행
GRANT SELECT ANY TRANSACTION TO c##dbzuser;-- 트랜잭션 정보 조회
GRANT LOGMINING TO c##dbzuser;             -- LogMiner 사용 (핵심!)
```

### 3.4 설정 확인 방법

```sql
-- 사용자 존재 확인
SELECT username FROM dba_users WHERE username = 'C##DBZUSER';

-- 권한 확인
SELECT * FROM dba_sys_privs WHERE grantee = 'C##DBZUSER';
```

---

## 4. Supplemental Logging (보충 로깅)

### 4.1 이게 뭔가요?

Oracle이 변경 로그에 **추가 정보를 기록**하도록 하는 설정입니다.

### 4.2 비유로 이해하기

```
┌─────────────────────────────────────────────────────────────┐
│  일반 로깅:                                                   │
│  ──────────                                                  │
│  "BOOK_ID=1의 TITLE이 변경됨"                                 │
│  → 뭘로 바뀌었는지 모름                                        │
│                                                             │
│  보충 로깅 (Supplemental Logging):                            │
│  ────────────────────────────────                            │
│  "BOOK_ID=1의 TITLE이 '옛날제목'에서 '새제목'으로 변경됨"       │
│  + 변경 전 값 (BEFORE)                                        │
│  + 변경 후 값 (AFTER)                                         │
│  + 어떤 컬럼들이 변경됐는지                                    │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 왜 CDC에 필요한가요?

Debezium이 Kafka에 메시지를 보낼 때 **변경 전/후 값**이 모두 필요합니다:

```json
{
  "before": {"BOOK_ID": 1, "TITLE": "옛날제목"},
  "after":  {"BOOK_ID": 1, "TITLE": "새제목"},
  "op": "u"
}
```

변경 전 값이 없으면:
- UPDATE 시 "어떤 값이 어떻게 바뀌었는지" 알 수 없음
- DELETE 시 "어떤 데이터가 삭제됐는지" 알 수 없음

### 4.4 설정 방법

```sql
-- 데이터베이스 레벨 보충 로깅 활성화
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;

-- 모든 컬럼에 대해 보충 로깅 (ALL COLUMNS)
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
```

### 4.5 설정 확인 방법

```sql
SELECT SUPPLEMENTAL_LOG_DATA_MIN, SUPPLEMENTAL_LOG_DATA_ALL
FROM V$DATABASE;

-- 결과:
-- SUPPLEME SUPPLEME
-- -------- --------
-- YES      YES       ← 둘 다 YES여야 함
```

---

## 5. Debezium Connector (커넥터)

### 5.1 이게 뭔가요?

Debezium에게 "어떤 DB의 어떤 테이블을 감시해라"라고 알려주는 **설정 파일**입니다.

### 5.2 비유로 이해하기

```
┌─────────────────────────────────────────────────────────────┐
│  커넥터 = 감시 지시서                                          │
│                                                             │
│  "ASIS Oracle DB에 접속해서                                   │
│   ASIS_USER 스키마의                                          │
│   BOOK_INFO, MEMBER_INFO, LEGACY_CODE 테이블을                │
│   감시하고                                                    │
│   변경이 있으면 Kafka의 특정 토픽에 보내라"                      │
└─────────────────────────────────────────────────────────────┘
```

### 5.3 커넥터 설정 예시

```json
{
  "name": "asis-connector",
  "config": {
    // 어떤 종류의 커넥터인가?
    "connector.class": "io.debezium.connector.oracle.OracleConnector",

    // 어디에 접속하나?
    "database.hostname": "asis-oracle",
    "database.port": "1521",
    "database.user": "c##dbzuser",
    "database.password": "dbz123",
    "database.dbname": "XE",

    // 어떤 테이블을 감시하나?
    "schema.include.list": "ASIS_USER",
    "table.include.list": "ASIS_USER.BOOK_INFO,ASIS_USER.MEMBER_INFO",

    // Kafka 토픽 이름 규칙
    "topic.prefix": "asis"
    // → asis.ASIS_USER.BOOK_INFO 토픽에 메시지 발행
  }
}
```

### 5.4 커넥터 관리 방법

```bash
# 커넥터 등록
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @asis-connector.json

# 전체 커넥터 목록
curl http://localhost:8083/connectors

# 특정 커넥터 상태 확인
curl http://localhost:8083/connectors/asis-connector/status

# 커넥터 삭제
curl -X DELETE http://localhost:8083/connectors/asis-connector
```

### 5.5 정상 상태 확인

```json
{
  "name": "asis-connector",
  "connector": { "state": "RUNNING" },
  "tasks": [{ "state": "RUNNING" }]
}
```

| 상태 | 의미 |
|-----|------|
| RUNNING | 정상 동작 중 |
| PAUSED | 일시 정지 |
| FAILED | 오류 발생 |
| UNASSIGNED | 작업 미할당 |

---

## 6. 설정 순서 요약

```
1. ARCHIVELOG 모드 활성화
   → Oracle이 변경 기록을 영구 보관하도록 설정

2. c##dbzuser 생성 + 권한 부여
   → Debezium이 Oracle에 접속할 계정 준비

3. Supplemental Logging 활성화
   → 변경 전/후 값이 모두 기록되도록 설정

4. Debezium Connector 등록
   → "이 테이블들을 감시해라" 지시서 등록

5. CDC 시작!
   → 테이블 변경 시 자동으로 Kafka에 메시지 발행
```

---

## 7. 자동화 스크립트

PoC 환경에서는 위 설정들을 자동화한 스크립트를 제공합니다:

```bash
# 전체 환경 자동 시작 (권장)
cd poc
./scripts/start-all.sh

# 또는 개별 실행
./scripts/setup-debezium-oracle.sh   # ARCHIVELOG, c##dbzuser, Supplemental Logging
./scripts/register-connectors.sh     # 커넥터 등록
```

### 7.1 스크립트가 하는 일

**setup-debezium-oracle.sh:**
1. ARCHIVELOG 모드가 아니면 활성화
2. c##dbzuser가 없으면 생성 + 권한 부여
3. Supplemental Logging 활성화

**register-connectors.sh:**
1. 기존 커넥터가 있으면 삭제
2. ASIS/TOBE 커넥터 등록
3. 상태 확인

### 7.2 주의사항

```
┌─────────────────────────────────────────────────────────────┐
│  Docker 볼륨을 삭제(docker-compose down -v)한 경우에만         │
│  재설정이 필요합니다.                                          │
│                                                             │
│  일반 재시작(docker-compose restart)에서는 설정이 유지됩니다.   │
└─────────────────────────────────────────────────────────────┘
```

---

## 8. 트러블슈팅

### 8.1 커넥터가 FAILED 상태일 때

```bash
# 상세 에러 확인
curl http://localhost:8083/connectors/asis-connector/status | jq

# 일반적인 원인:
# - ARCHIVELOG 모드 미활성화
# - c##dbzuser 권한 부족
# - Supplemental Logging 미설정
# - 네트워크 연결 문제
```

### 8.2 해결 순서

```
1. Oracle 설정 확인
   ./scripts/setup-debezium-oracle.sh

2. 커넥터 재등록
   ./scripts/register-connectors.sh

3. 여전히 안 되면 로그 확인
   docker logs kafka-connect
```

---

## 9. 다음 단계

1. **설계 상세** → [../02-설계/01_동기화_설계.md](../02-설계/01_동기화_설계.md)
2. **환경 시작하기** → [../00-시작하기/02_환경_시작_가이드.md](../00-시작하기/02_환경_시작_가이드.md)
3. **문제 해결** → [../04-운영/04_트러블슈팅.md](../04-운영/04_트러블슈팅.md)

---

*작성일: 2026-01-14*
