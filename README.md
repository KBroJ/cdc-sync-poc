# CDC Sync PoC

> **Oracle 양방향 CDC(Change Data Capture) 동기화 PoC**
>
> Debezium + Kafka를 활용한 실시간 데이터 동기화 환경

![Architecture](img/CDC%20초기%20설계.png)

## 개요

기존 시스템(ASIS)과 신규 시스템(TOBE) 간의 **양방향 실시간 데이터 동기화**를 검증하기 위한 PoC 프로젝트입니다.

### 주요 특징

- **양방향 동기화**: ASIS ↔ TOBE 간 실시간 데이터 동기화
- **스키마 변환 지원**: 서로 다른 스키마 간 매핑 처리
- **무한루프 방지**: 해시 기반 중복 이벤트 필터링
- **모니터링 대시보드**: 실시간 통계 및 테스트 기능

## 기술 스택

| 구성요소 | 기술 |
|---------|------|
| Database | Oracle XE 21c |
| CDC | Debezium 2.4 (LogMiner) |
| Message Broker | Apache Kafka |
| Sync Service | Java 17 + Spring Boot 3.2 |
| Container | Docker Compose |

## 아키텍처

```
┌─────────────┐                              ┌─────────────┐
│ ASIS Oracle │                              │ TOBE Oracle │
│   (1521)    │                              │   (1522)    │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       ▼                                            ▼
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  Debezium   │ ───► │    Kafka    │ ◄─── │  Debezium   │
│  Connector  │      │   (9092)    │      │  Connector  │
└─────────────┘      └──────┬──────┘      └─────────────┘
                            │
                            ▼
                    ┌─────────────┐
                    │Sync Service │
                    │   (8082)    │
                    └─────────────┘
```

## 빠른 시작

### 사전 요구사항

- Docker Desktop
- 8GB+ RAM 권장

### 1. 클론

```bash
git clone https://github.com/KBroJ/cdc-sync-poc.git
cd cdc-sync-poc
```

### 2. 환경 시작

```bash
# Windows
cd poc
scripts\01_start_env.bat

# Linux/Mac
cd poc
./scripts/01_start_env.sh
```

### 3. 대시보드 접속

http://localhost:8082/dashboard

## 서비스 접속 정보

| 서비스 | URL | 설명 |
|--------|-----|------|
| **CDC 대시보드** | http://localhost:8082/dashboard | 모니터링 및 테스트 |
| Kafka UI | http://localhost:8081 | Kafka 토픽/메시지 확인 |
| Kafka Connect | http://localhost:8083 | Debezium 커넥터 관리 |
| ASIS Oracle | localhost:1521 | asis_user / asis123 |
| TOBE Oracle | localhost:1522 | tobe_user / tobe123 |

## 프로젝트 구조

```
cdc-sync-poc/
├── docs/                    # 문서
│   ├── 01-05_설계문서       # 동기화 설계, 충돌 정책 등
│   └── 06-13_PoC문서        # 환경 구성, 가이드 등
├── poc/                     # PoC 환경
│   ├── docker-compose.yml   # Docker 구성
│   ├── asis-oracle/         # ASIS DB 초기화 SQL
│   ├── tobe-oracle/         # TOBE DB 초기화 SQL
│   └── sync-service-java/   # CDC Sync Service
└── img/                     # 이미지
```

## 문서

| 문서 | 설명 |
|------|------|
| [실전 사용 가이드](docs/12_실전_사용_가이드_초보자용.md) | 초보자용 단계별 가이드 |
| [소스 상세 설명](docs/09_소스레벨_상세_설명서.md) | 소스코드 상세 설명 |
| [트러블슈팅](docs/08_트러블슈팅.md) | 문제 해결 가이드 |
| [테스트 방법](docs/10_테스트_및_확인_방법.md) | CDC 테스트 및 확인 |

## 테스트

### 대시보드에서 테스트

1. http://localhost:8082/dashboard 접속
2. **[ASIS 테스트]** 버튼 클릭
3. 최근 이벤트에서 결과 확인

### SQL로 테스트

```sql
-- ASIS DB (localhost:1521)
UPDATE BOOK_INFO SET BOOK_TITLE = '테스트' WHERE BOOK_ID = 1;
COMMIT;

-- TOBE DB (localhost:1522) 에서 확인
SELECT * FROM CDC_TOBE_BOOK ORDER BY CDC_SEQ DESC;
```

## 환경 중지

```bash
# Windows
scripts\02_stop_env.bat

# Linux/Mac
docker compose down
```

## License

This project is for PoC purposes.
