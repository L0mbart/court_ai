@echo off
cd /d "%~dp0"
set "PY=D:\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"
title CourtAI Server
echo.
echo  CourtAI Dashboard ^(HTTP^)
echo  Local : http://127.0.0.1:8080
echo  LAN   : http://^(IP PC^):8080
echo.
echo  Untuk kamera di HP/browser mobile, jalankan start-https.bat
echo  atau start-both.bat lalu buka https://IP:8443/app
echo.
"%PY%" -m uvicorn main:app --host 0.0.0.0 --port 8080
echo.
echo Server stopped.
pause
