@echo off
chcp 936 >nul
rem ============================================
rem  发票解析小服务（Java 后端通过 HTTP 调它）
rem  默认端口 8799，关掉窗口就停止
rem ============================================
set "PY=D:\soft\python\python.exe"
if not exist "%PY%" set "PY=python"
cd /d "%~dp0.."
echo.
echo  启动解析服务  http://127.0.0.1:8799
echo  按 Ctrl+C 停止
echo.
"%PY%" "工具\解析服务.py" --port 8799
pause