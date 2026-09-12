@echo off
cd /d "%~dp0"
set "PY=D:\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"

REM Ensure certs
"%PY%" generate_certs.py >nul 2>&1

REM Stop existing listeners on 8080/8443
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do taskkill /PID %%a /F >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8443" ^| findstr "LISTENING"') do taskkill /PID %%a /F >nul 2>&1
timeout /t 1 /nobreak >nul

echo Starting HTTP :8080 and HTTPS :8443 ...
start "CourtAI HTTP" /MIN "%PY%" -m uvicorn main:app --host 0.0.0.0 --port 8080
start "CourtAI HTTPS" /MIN "%PY%" -m uvicorn main:app --host 0.0.0.0 --port 8443 --ssl-certfile "%~dp0certs\cert.pem" --ssl-keyfile "%~dp0certs\key.pem"
timeout /t 3 /nobreak >nul
echo.
echo OK
echo  PC dashboard : http://127.0.0.1:8080
echo  Mobile web   : https://172.16.3.51:8443/app
echo  ^(ganti IP sesuai ipconfig PC anda^)
echo.
exit /b 0
