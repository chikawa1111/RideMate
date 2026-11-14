@echo off
rem 에뮬레이터를 켤 때마다 한 번 실행: 버추얼 콘솔(8088) 연결
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" wait-for-device
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" forward tcp:8088 tcp:8088
echo.
echo Virtual Console: http://127.0.0.1:8088/
timeout /t 3 >nul
