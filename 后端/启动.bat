@echo off
chcp 936 >nul
rem ============================================
rem  本地启动（开发用）
rem  默认端口 8765，前端 dist 由 jar 直接托管
rem ============================================
set "JAVA_HOME=D:\soft\Java17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"

if not exist "target\invoice-server.jar" (
  echo  还没有打包，先跑 打包.bat
  pause
  exit /b 1
)

echo.
echo  启动中… 浏览器访问 http://127.0.0.1:8765
echo  按 Ctrl+C 停止
echo.
"%JAVA_HOME%\bin\java.exe" -jar target\invoice-server.jar --app.base-dir=..
pause