@echo off
REM ============================================
REM 02. PoC 환경 중지 (Windows)
REM ============================================

echo ==========================================
echo  CDC PoC 환경 중지
echo ==========================================

cd /d "%~dp0.."

echo 컨테이너 중지 중...
docker compose down

echo.
echo 환경 중지 완료!
echo.
echo 볼륨까지 삭제하려면: docker compose down -v
pause
