@echo off
cd /d "%~dp0"
set "PY=D:\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"

echo Generating / refreshing TLS certificates...
"%PY%" generate_certs.py
if not exist "certs\cert.pem" (
  echo FAILED to create certificates.
  pause
  exit /b 1
)

title CourtAI Server HTTPS
echo.
echo  CourtAI Dashboard + Web App ^(HTTPS untuk kamera HP^)
echo  PC     : https://127.0.0.1:8443
echo  Mobile : https://^(IP-PC^):8443/app
echo  Contoh : https://172.16.3.51:8443/app
echo.
echo  Di HP: buka link HTTPS, ketuk Advanced - Proceed ^(sertifikat lokal^).
echo  HTTP lama tetap bisa di port 8080 untuk PC ^(tanpa kamera HP^).
echo.
"%PY%" -m uvicorn main:app --host 0.0.0.0 --port 8443 --ssl-certfile certs/cert.pem --ssl-keyfile certs/key.pem
echo.
echo Server stopped.
pause
