#!/bin/bash
# ============================================
# Debezium 커넥터 등록 스크립트
#
# 기능:
# - Kafka Connect 준비 대기
# - ASIS/TOBE 커넥터 등록
# - 기존 커넥터가 있으면 삭제 후 재등록
#
# 사용법: ./register-connectors.sh
# ============================================

set -e

CONNECT_URL="http://localhost:8083"

echo "=== Debezium 커넥터 등록 ==="
echo ""

# Kafka Connect 준비 대기
wait_for_kafka_connect() {
    local max_wait=60
    local waited=0

    echo "1. Kafka Connect 준비 대기..."
    while [ $waited -lt $max_wait ]; do
        status=$(curl -s -o /dev/null -w "%{http_code}" ${CONNECT_URL}/ 2>/dev/null || echo "000")
        if [ "$status" = "200" ]; then
            echo "   Kafka Connect: 준비됨"
            return 0
        fi
        sleep 5
        waited=$((waited + 5))
        echo "   대기 중... ($waited/$max_wait 초)"
    done

    echo "ERROR: Kafka Connect가 준비되지 않음"
    return 1
}

# 커넥터 상태 확인
check_connector() {
    local name=$1
    status=$(curl -s ${CONNECT_URL}/connectors/${name}/status 2>/dev/null | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print(d['connector']['state'])
except: print('NOT_FOUND')
" 2>/dev/null)
    echo "$status"
}

# 커넥터 등록/재등록
register_connector() {
    local name=$1
    local config=$2

    existing=$(check_connector $name)
    if [ "$existing" != "NOT_FOUND" ]; then
        echo "   기존 커넥터 삭제: $name (상태: $existing)"
        curl -s -X DELETE ${CONNECT_URL}/connectors/${name} > /dev/null 2>&1
        sleep 3
    fi

    echo "   커넥터 등록: $name"
    result=$(curl -s -X POST ${CONNECT_URL}/connectors \
      -H "Content-Type: application/json" \
      -d "$config" 2>/dev/null)

    # 등록 결과 확인
    if echo "$result" | grep -q "error_code"; then
        echo "   ERROR: 등록 실패"
        echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('message','Unknown error'))" 2>/dev/null
        return 1
    fi

    echo "   등록 완료"
}

# Kafka Connect 대기
wait_for_kafka_connect
echo ""

# ASIS 커넥터 등록
echo "2. ASIS 커넥터 등록..."
register_connector "asis-connector" '{
  "name": "asis-connector",
  "config": {
    "connector.class": "io.debezium.connector.oracle.OracleConnector",
    "tasks.max": "1",
    "database.hostname": "asis-oracle",
    "database.port": "1521",
    "database.user": "c##dbzuser",
    "database.password": "dbz",
    "database.dbname": "XE",
    "database.pdb.name": "XEPDB1",
    "topic.prefix": "asis",
    "table.include.list": "ASIS_USER.BOOK_INFO,ASIS_USER.MEMBER_INFO,ASIS_USER.LEGACY_CODE",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:29092",
    "schema.history.internal.kafka.topic": "schema-changes.asis",
    "log.mining.strategy": "online_catalog",
    "decimal.handling.mode": "string",
    "snapshot.mode": "initial"
  }
}'
echo ""

# TOBE 커넥터 등록
echo "3. TOBE 커넥터 등록..."
register_connector "tobe-connector" '{
  "name": "tobe-connector",
  "config": {
    "connector.class": "io.debezium.connector.oracle.OracleConnector",
    "tasks.max": "1",
    "database.hostname": "tobe-oracle",
    "database.port": "1521",
    "database.user": "c##dbzuser",
    "database.password": "dbz",
    "database.dbname": "XE",
    "database.pdb.name": "XEPDB1",
    "topic.prefix": "tobe",
    "table.include.list": "TOBE_USER.TB_BOOK,TOBE_USER.TB_MEMBER,TOBE_USER.TB_NEW_SERVICE",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:29092",
    "schema.history.internal.kafka.topic": "schema-changes.tobe",
    "log.mining.strategy": "online_catalog",
    "decimal.handling.mode": "string",
    "snapshot.mode": "initial"
  }
}'
echo ""

# 상태 확인 (15초 대기)
echo "4. 커넥터 상태 확인 (15초 대기)..."
sleep 15

echo ""
echo "=== 커넥터 상태 ==="

for connector in asis-connector tobe-connector; do
    echo ""
    echo "[$connector]"
    curl -s ${CONNECT_URL}/connectors/${connector}/status | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print(f\"  Connector: {d['connector']['state']}\")
    if d['tasks']:
        task_state = d['tasks'][0]['state']
        print(f\"  Task: {task_state}\")
        if task_state == 'FAILED':
            trace = d['tasks'][0].get('trace','')
            # 핵심 에러 메시지만 추출
            if 'ORA-' in trace:
                import re
                ora_err = re.search(r'ORA-\d+[^\\n]*', trace)
                if ora_err:
                    print(f\"  Error: {ora_err.group()}\")
            else:
                print(f\"  Error: {trace[:150]}...\")
except Exception as e:
    print(f\"  Status: Failed to parse ({e})\")
" 2>/dev/null
done

echo ""
echo "=== 완료 ==="
echo ""
echo "CDC 상태 확인:"
echo "  - Kafka UI: http://localhost:8081"
echo "  - Connector API: curl http://localhost:8083/connectors"
echo "  - 시뮬레이터: http://localhost:8082/simulator"
