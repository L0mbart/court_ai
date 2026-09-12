@echo off
echo Stopping CourtAI on ports 8080 and 8443...
for %%P in (8080 8443) do (
  for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%%P" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
    echo Stopped PID %%a on %%P
  )
)
echo Done.
