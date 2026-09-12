@echo off
cd /d "%~dp0"
set "PY=D:\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"

REM If already listening, exit quietly
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if %ERRORLEVEL%==0 (
  echo CourtAI already running on http://127.0.0.1:8080
  exit /b 0
)

echo Starting CourtAI in background...
start "CourtAI Server" /MIN "%PY%" -m uvicorn main:app --host 0.0.0.0 --port 8080
timeout /t 3 /nobreak >nul
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if %ERRORLEVEL%==0 (
  echo OK - open http://127.0.0.1:8080
) else (
  echo FAILED to start. Check Python / see server.err.log
)
exit /b 0
