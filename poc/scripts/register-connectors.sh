#!/bin/bash
# ============================================
# Debezium 커넥터 등록 스크립트
# 사용법: ./register-connectors.sh
# ============================================

CONNECT_URL="http://localhost:8083"

echo "=== Debezium 커넥터 등록 ==="
echo ""

# 기존 커넥터 삭제 (있으면)
echo "1. 기존 커넥터 정리..."
curl -s -X DELETE ${CONNECT_URL}/connectors/asis-connector 2>/dev/null
curl -s -X DELETE ${CONNECT_URL}/connectors/tobe-connector 2>/dev/null
sleep 2

# ASIS 커넥터 등록
echo "2. ASIS 커넥터 등록..."
curl -s -X POST ${CONNECT_URL}/connectors \
  -H "Content-Type: application/json" \
  -d '{
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
    "log.mining.strategy": "online_catalog"
  }
}'
echo ""

# TOBE 커넥터 등록
echo "3. TOBE 커넥터 등록..."
curl -s -X POST ${CONNECT_URL}/connectors \
  -H "Content-Type: application/json" \
  -d '{
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
    "log.mining.strategy": "online_catalog"
  }
}'
echo ""

# 상태 확인
echo "4. 커넥터 상태 확인 (10초 대기)..."
sleep 10

echo ""
echo "=== ASIS Connector ==="
curl -s ${CONNECT_URL}/connectors/asis-connector/status | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print(f\"Connector: {d['connector']['state']}\")
    if d['tasks']:
        print(f\"Task: {d['tasks'][0]['state']}\")
        if d['tasks'][0]['state'] == 'FAILED':
            print(f\"Error: {d['tasks'][0].get('trace','')[:200]}...\")
except: print('Failed to parse status')
" 2>/dev/null

echo ""
echo "=== TOBE Connector ==="
curl -s ${CONNECT_URL}/connectors/tobe-connector/status | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print(f\"Connector: {d['connector']['state']}\")
    if d['tasks']:
        print(f\"Task: {d['tasks'][0]['state']}\")
        if d['tasks'][0]['state'] == 'FAILED':
            print(f\"Error: {d['tasks'][0].get('trace','')[:200]}...\")
except: print('Failed to parse status')
" 2>/dev/null

echo ""
echo "=== 완료 ==="
