#!/bin/bash
# ============================================
# 01. PoC 환경 시작
# ============================================

echo "=========================================="
echo " CDC PoC 환경 시작"
echo "=========================================="

cd "$(dirname "$0")/.."

# Docker Compose 실행
echo "[1/3] Docker Compose 시작..."
docker compose up -d

# 상태 확인
echo ""
echo "[2/3] 컨테이너 상태 확인..."
docker compose ps

# Oracle 준비 대기
echo ""
echo "[3/3] Oracle DB 준비 대기 중... (최대 5분 소요)"
echo "      진행상황: docker logs -f asis-oracle"

# ASIS Oracle 준비 대기
until docker exec asis-oracle healthcheck.sh &>/dev/null; do
    echo -n "."
    sleep 5
done
echo ""
echo "ASIS Oracle 준비 완료!"

# TOBE Oracle 준비 대기
until docker exec tobe-oracle healthcheck.sh &>/dev/null; do
    echo -n "."
    sleep 5
done
echo ""
echo "TOBE Oracle 준비 완료!"

echo ""
echo "=========================================="
echo " 환경 시작 완료!"
echo "=========================================="
echo ""
echo "접속 정보:"
echo "  - ASIS Oracle: localhost:1521 (asis_user/asis123)"
echo "  - TOBE Oracle: localhost:1522 (tobe_user/tobe123)"
echo "  - Kafka UI: http://localhost:8081"
echo "  - Kafka Connect: http://localhost:8083"
echo ""
