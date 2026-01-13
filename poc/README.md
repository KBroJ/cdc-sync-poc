# CDC PoC 환경

## 개요

CDC(Change Data Capture) 동기화 검증을 위한 PoC 환경입니다.

## 구성

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ ASIS Oracle │     │   Kafka +   │     │ TOBE Oracle │
│  (1521)     │◄───►│  Debezium   │◄───►│  (1522)     │
└─────────────┘     └─────────────┘     └─────────────┘
```

### 컨테이너 목록

| 컨테이너 | 포트 | 역할 |
|---------|:----:|------|
| asis-oracle | 1521 | ASIS DB (구 스키마) |
| tobe-oracle | 1522 | TOBE DB (신 스키마) |
| zookeeper | 2181 | Kafka 의존성 |
| kafka | 9092 | 메시지 브로커 |
| kafka-connect | 8083 | Debezium 호스트 |
| sync-service | 8082 | CDC 전송 서비스 (Java/Spring Boot) |
| kafka-ui | 8081 | Kafka 모니터링 UI |

## 빠른 시작

### 1. 환경 시작
```bash
scripts\01_start_env.bat
```

### 2. 대시보드 접속
http://localhost:8082/dashboard

### 3. 테스트 실행
대시보드에서 **[ASIS 테스트]** 버튼 클릭 → 결과 확인

## 접속 정보

| 서비스 | URL/접속정보 |
|--------|-------------|
| **CDC 모니터링 대시보드** | **http://localhost:8082/dashboard** |
| ASIS Oracle | localhost:1521 / asis_user / asis123 |
| TOBE Oracle | localhost:1522 / tobe_user / tobe123 |
| Kafka UI | http://localhost:8081 |
| Kafka Connect API | http://localhost:8083 |

## 참고 문서

### 설계 문서 (분석/설계)
| 문서 | 내용 |
|------|------|
| 01_CDC_동기화_설계_정리 | 전체 설계, 스키마 매핑 |
| 02_CDC_무한루프_방지_대안 | 무한루프 방지 메커니즘 |
| 03_CDC_동기화_케이스_분류 | 동기화 케이스 상세 |
| 04_CDC_충돌_정책 | 충돌 해결 정책 |
| 05_CDC_에러코드_체계 | 에러 코드 및 처리 |

### PoC 문서 (구현/테스트)
| 문서 | 내용 |
|------|------|
| 06_CDC_PoC_환경_구성 | Docker 환경 상세 구성 |
| 07_CDC_PoC_구축_로그 | 구축 과정 기록 |
| 08_트러블슈팅 | 문제 해결 가이드 |
| 09_소스레벨_상세_설명서 | 소스코드 상세 설명 |
| 10_테스트_및_확인_방법 | 테스트 방법 |
| 11_모니터링_대시보드 | 대시보드 사용법 |
| **12_실전_사용_가이드_초보자용** | **초보자용 실전 가이드** |
| 13_소스코드_구현_이유_설명 | 왜 이렇게 구현했는지 |

> 모든 문서는 docs/ 폴더에 있습니다.

## 문제 해결

자세한 내용: docs/08_트러블슈팅.md

```bash
# 컨테이너 상태 확인
docker ps

# 로그 확인
docker logs sync-service --tail 50
```
