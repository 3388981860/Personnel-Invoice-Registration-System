@echo off
chcp 936 >nul
rem ============================================
rem  发票查验小服务（Playwright + 验证码 OCR）
rem  默认端口 8798，关掉窗口就停止
rem ============================================
set "PY=D:\soft\python\python.exe"
if not exist "%PY%" set "PY=python"
cd /d "%~dp0.."
echo.
echo  启动查验服务  http://127.0.0.1:8798
echo  按 Ctrl+C 停止
echo.
"%PY%" "工具\查验服务.py" --port 8798
pause