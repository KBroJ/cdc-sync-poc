@echo off
REM ============================================
REM 01. PoC 환경 시작 (Windows)
REM ============================================

echo ==========================================
echo  CDC PoC 환경 시작
echo ==========================================

cd /d "%~dp0.."

echo [1/3] Docker Compose 시작...
docker compose up -d

echo.
echo [2/3] 컨테이너 상태 확인...
docker compose ps

echo.
echo [3/3] Oracle DB 준비 대기 중... (최대 5분 소요)
echo       별도 터미널에서 확인: docker logs -f asis-oracle

:wait_asis
docker exec asis-oracle healthcheck.sh >nul 2>&1
if errorlevel 1 (
    echo|set /p=.
    timeout /t 5 /nobreak >nul
    goto wait_asis
)
echo.
echo ASIS Oracle 준비 완료!

:wait_tobe
docker exec tobe-oracle healthcheck.sh >nul 2>&1
if errorlevel 1 (
    echo|set /p=.
    timeout /t 5 /nobreak >nul
    goto wait_tobe
)
echo.
echo TOBE Oracle 준비 완료!

echo.
echo ==========================================
echo  환경 시작 완료!
echo ==========================================
echo.
echo 접속 정보:
echo   - ASIS Oracle: localhost:1521 (asis_user/asis123)
echo   - TOBE Oracle: localhost:1522 (tobe_user/tobe123)
echo   - Kafka UI: http://localhost:8081
echo   - Kafka Connect: http://localhost:8083
echo.
pause
