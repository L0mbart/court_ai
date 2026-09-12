@echo off
:: Run as Administrator — disable virtual cameras that BSOD (TranScreenCamera.sys)
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Minta izin Administrator...
  powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

echo Disabling TranScreen / IdeaCamera / Wonder Camera...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ids=@('ROOT\IMAGE\0000','ROOT\CAMERA\0000','ROOT\CAMERA\0001'); foreach($id in $ids){ try { $d=Get-PnpDevice -InstanceId $id; Disable-PnpDevice -InstanceId $id -Confirm:$false; Write-Host ('DISABLED '+$d.FriendlyName) } catch { Write-Host ('SKIP '+$id) } }; Write-Host ''; Get-PnpDevice | Where-Object { $_.FriendlyName -match 'TranScreen|Wonder Camera|IdeaCamera|HP Wide' } | Format-Table Status,FriendlyName -AutoSize"

echo.
echo HP Wide Vision harus Status=OK. Yang lain Error/Disabled.
echo Kalau TranScreen aktif lagi setelah Windows Update, jalankan file ini lagi.
pause
