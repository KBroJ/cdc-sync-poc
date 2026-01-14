#!/bin/bash
# ============================================
# Debezium Oracle 설정 스크립트
# Docker 컨테이너 시작 후 실행 필요
#
# 기능:
# - ARCHIVELOG 모드 활성화
# - Supplemental Logging 활성화
# - c##dbzuser 생성 및 권한 부여
#
# 특징:
# - 이미 설정된 경우 건너뜀 (멱등성 보장)
# - Docker 볼륨 삭제 후에만 재설정 필요
#
# 사용법: ./setup-debezium-oracle.sh
# ============================================

set -e

echo "=== Debezium Oracle 설정 시작 ==="
echo ""

# 컨테이너 healthy 상태 대기 함수
wait_for_oracle() {
    local container=$1
    local max_wait=120
    local waited=0

    echo "  $container healthy 대기 중..."
    while [ $waited -lt $max_wait ]; do
        status=$(docker inspect --format='{{.State.Health.Status}}' $container 2>/dev/null || echo "not_found")
        if [ "$status" = "healthy" ]; then
            echo "  $container: healthy"
            return 0
        fi
        sleep 5
        waited=$((waited + 5))
        echo "  대기 중... ($waited/$max_wait 초)"
    done

    echo "ERROR: $container 가 healthy 상태가 되지 않음"
    return 1
}

# ARCHIVELOG 모드 확인 함수
check_archivelog() {
    local container=$1
    result=$(docker exec $container bash -c 'export ORACLE_SID=XE && echo "SELECT LOG_MODE FROM V\$DATABASE;" | sqlplus -s / as sysdba' 2>/dev/null | grep -E "ARCHIVELOG|NOARCHIVELOG" | tr -d ' ')
    echo "$result"
}

# c##dbzuser 존재 확인 함수
check_dbzuser() {
    local container=$1
    result=$(docker exec $container bash -c 'export ORACLE_SID=XE && echo "SELECT COUNT(*) FROM dba_users WHERE username = '"'"'C##DBZUSER'"'"';" | sqlplus -s / as sysdba' 2>/dev/null | grep -E "^[0-9]" | tr -d ' ')
    echo "$result"
}

# Oracle 설정 함수 (ARCHIVELOG + Supplemental Logging + c##dbzuser)
setup_oracle() {
    local container=$1
    local log_mode=$(check_archivelog $container)
    local dbzuser_count=$(check_dbzuser $container)

    echo "[$container]"
    echo "  현재 상태: LOG_MODE=$log_mode, c##dbzuser=${dbzuser_count:-0}명"

    # ARCHIVELOG 모드 설정
    if [ "$log_mode" != "ARCHIVELOG" ]; then
        echo "  ARCHIVELOG 모드 활성화 중..."
        docker exec $container bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
SHUTDOWN IMMEDIATE;
STARTUP MOUNT;
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;
ALTER PLUGGABLE DATABASE ALL OPEN;
exit;
EOF' > /dev/null 2>&1
        echo "  ARCHIVELOG 모드: 완료"
    else
        echo "  ARCHIVELOG 모드: 이미 설정됨 (SKIP)"
    fi

    # Supplemental Logging 설정 (매번 실행해도 무해)
    echo "  Supplemental Logging 설정 중..."
    docker exec $container bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
exit;
EOF' > /dev/null 2>&1
    echo "  Supplemental Logging: 완료"

    # c##dbzuser 생성
    if [ "${dbzuser_count:-0}" = "0" ]; then
        echo "  c##dbzuser 생성 중..."
        docker exec $container bash -c 'export ORACLE_SID=XE && sqlplus -s / as sysdba << EOF
-- Debezium 사용자 생성
CREATE USER c##dbzuser IDENTIFIED BY dbz DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS CONTAINER=ALL;

-- 필수 권한 부여
GRANT CREATE SESSION TO c##dbzuser CONTAINER=ALL;
GRANT SET CONTAINER TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$DATABASE TO c##dbzuser CONTAINER=ALL;
GRANT FLASHBACK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY TRANSACTION TO c##dbzuser CONTAINER=ALL;
GRANT LOGMINING TO c##dbzuser CONTAINER=ALL;
GRANT CREATE TABLE TO c##dbzuser CONTAINER=ALL;
GRANT LOCK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT CREATE SEQUENCE TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR_D TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOG_HISTORY TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_LOGS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_CONTENTS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGMNR_PARAMETERS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$LOGFILE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$ARCHIVED_LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$ARCHIVE_DEST_STATUS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_\$TRANSACTION TO c##dbzuser CONTAINER=ALL;
exit;
EOF' > /dev/null 2>&1
        echo "  c##dbzuser: 생성 완료"
    else
        echo "  c##dbzuser: 이미 존재함 (SKIP)"
    fi

    echo ""
}

# 메인 실행
echo "1. Oracle 컨테이너 상태 확인"
wait_for_oracle "asis-oracle"
wait_for_oracle "tobe-oracle"
echo ""

echo "2. ASIS Oracle 설정"
setup_oracle "asis-oracle"

echo "3. TOBE Oracle 설정"
setup_oracle "tobe-oracle"

echo "=== Debezium Oracle 설정 완료 ==="
echo ""
echo "다음 단계: ./register-connectors.sh 실행하여 커넥터 등록"
