#!/bin/bash
# ============================================
# CDC PoC 환경 통합 시작 스크립트
#
# 실행 순서:
# 1. docker-compose up (컨테이너 시작)
# 2. Oracle healthy 대기
# 3. Debezium Oracle 설정 (ARCHIVELOG, c##dbzuser)
# 4. Debezium 커넥터 등록
#
# 사용법: ./start-all.sh
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "============================================"
echo "  CDC PoC 환경 통합 시작"
echo "============================================"
echo ""
echo "프로젝트 경로: $PROJECT_DIR"
echo ""

# 1. Docker Compose 시작
echo "=== 1단계: Docker Compose 시작 ==="
docker-compose up -d
echo ""

# 2. Oracle healthy 대기
echo "=== 2단계: Oracle healthy 대기 ==="
echo ""

wait_for_healthy() {
    local container=$1
    local max_wait=180  # 3분
    local waited=0

    echo "  $container 대기 중..."
    while [ $waited -lt $max_wait ]; do
        status=$(docker inspect --format='{{.State.Health.Status}}' $container 2>/dev/null || echo "starting")
        if [ "$status" = "healthy" ]; then
            echo "  $container: healthy (${waited}초 소요)"
            return 0
        fi
        sleep 10
        waited=$((waited + 10))
        echo "    상태: $status (${waited}/${max_wait}초)"
    done

    echo "ERROR: $container 타임아웃"
    return 1
}

wait_for_healthy "asis-oracle"
wait_for_healthy "tobe-oracle"
echo ""

# 3. Debezium Oracle 설정
echo "=== 3단계: Debezium Oracle 설정 ==="
bash "$SCRIPT_DIR/setup-debezium-oracle.sh"
echo ""

# 4. Debezium 커넥터 등록
echo "=== 4단계: Debezium 커넥터 등록 ==="
bash "$SCRIPT_DIR/register-connectors.sh"
echo ""

echo "============================================"
echo "  CDC PoC 환경 시작 완료!"
echo "============================================"
echo ""
echo "접속 URL:"
echo "  - Kafka UI:     http://localhost:8081"
echo "  - CDC 시뮬레이터: http://localhost:8082/simulator"
echo "  - Kafka Connect: http://localhost:8083/connectors"
echo ""
echo "ASIS Oracle: localhost:15210 (asis_user/asis123)"
echo "TOBE Oracle: localhost:15220 (tobe_user/tobe123)"
echo ""
