@echo off
set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
del /f /q "%STARTUP%\CourtAI Server.lnk" 2>nul
echo Autostart dihapus dari Startup folder.
echo Server yang sedang jalan TIDAK di-stop. Pakai stop.bat kalau perlu.
pause
