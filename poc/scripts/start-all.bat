@echo off
REM ============================================
REM CDC PoC 환경 통합 시작 스크립트 (Windows)
REM
REM 실행 순서:
REM 1. docker-compose up (컨테이너 시작)
REM 2. Oracle healthy 대기
REM 3. Debezium Oracle 설정
REM 4. Debezium 커넥터 등록
REM
REM 사용법: start-all.bat
REM ============================================

setlocal EnableDelayedExpansion

echo ============================================
echo   CDC PoC 환경 통합 시작
echo ============================================
echo.

cd /d "%~dp0.."
echo 프로젝트 경로: %CD%
echo.

REM 1. Docker Compose 시작
echo === 1단계: Docker Compose 시작 ===
docker-compose up -d
echo.

REM 2. Oracle healthy 대기
echo === 2단계: Oracle healthy 대기 ===
echo.

echo asis-oracle 대기 중...
:wait_asis
for /f "tokens=*" %%i in ('docker inspect --format="{{.State.Health.Status}}" asis-oracle 2^>nul') do set status=%%i
if "%status%"=="healthy" (
    echo   asis-oracle: healthy
    goto wait_tobe
)
echo   상태: %status%
timeout /t 10 /nobreak > nul
goto wait_asis

:wait_tobe
echo tobe-oracle 대기 중...
:wait_tobe_loop
for /f "tokens=*" %%i in ('docker inspect --format="{{.State.Health.Status}}" tobe-oracle 2^>nul') do set status=%%i
if "%status%"=="healthy" (
    echo   tobe-oracle: healthy
    goto setup_debezium
)
echo   상태: %status%
timeout /t 10 /nobreak > nul
goto wait_tobe_loop

:setup_debezium
echo.

REM 3. Debezium Oracle 설정 (Git Bash 사용)
echo === 3단계: Debezium Oracle 설정 ===
echo (Git Bash 또는 WSL 필요)
echo.

REM Git Bash가 있으면 사용
where bash >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    bash "%~dp0setup-debezium-oracle.sh"
) else (
    echo [WARNING] bash를 찾을 수 없습니다.
    echo Git Bash 또는 WSL을 설치하거나, 수동으로 실행하세요:
    echo   bash scripts/setup-debezium-oracle.sh
)
echo.

REM 4. Debezium 커넥터 등록
echo === 4단계: Debezium 커넥터 등록 ===
where bash >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    bash "%~dp0register-connectors.sh"
) else (
    echo [WARNING] bash를 찾을 수 없습니다.
    echo 수동으로 실행하세요:
    echo   bash scripts/register-connectors.sh
)
echo.

echo ============================================
echo   CDC PoC 환경 시작 완료!
echo ============================================
echo.
echo 접속 URL:
echo   - Kafka UI:       http://localhost:8081
echo   - CDC 시뮬레이터: http://localhost:8082/simulator
echo   - Kafka Connect:  http://localhost:8083/connectors
echo.
echo ASIS Oracle: localhost:15210 (asis_user/asis123)
echo TOBE Oracle: localhost:15220 (tobe_user/tobe123)
echo.

pause
