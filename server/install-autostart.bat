@echo off
REM Put CourtAI into Windows Startup folder (no admin needed).
setlocal
set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "SERVER_DIR=%~dp0"
set "VBS=%SERVER_DIR%autostart-courtai.vbs"
set "PY=D:\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"

> "%VBS%" echo Set WshShell = CreateObject("WScript.Shell")
>> "%VBS%" echo WshShell.CurrentDirectory = "%SERVER_DIR%"
>> "%VBS%" echo WshShell.Run """%PY%"" -m uvicorn main:app --host 0.0.0.0 --port 8080", 0, False

powershell -NoProfile -Command ^
  "$s = New-Object -ComObject WScript.Shell; $l = $s.CreateShortcut('%STARTUP%\CourtAI Server.lnk'); $l.TargetPath='wscript.exe'; $l.Arguments='\"%VBS%\"'; $l.WorkingDirectory='%SERVER_DIR%'; $l.WindowStyle=7; $l.Description='CourtAI Dashboard autostart'; $l.Save()"

echo.
echo Autostart AKTIF.
echo Setelah restart + login Windows, server jalan di http://127.0.0.1:8080
echo Shortcut: %STARTUP%\CourtAI Server.lnk
echo.
echo Nonaktifkan: uninstall-autostart.bat
echo.
pause
