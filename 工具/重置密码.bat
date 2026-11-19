@echo off
chcp 936 >nul
setlocal
title 重置账号密码
cd /d "%~dp0"

set "ROOT=%~dp0.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"

rem ---- 找 Python ----
set "PY="
if exist "%ROOT%runtime\python.exe" set "PY=%ROOT%runtime\python.exe"
if not defined PY if exist "%ROOT%运行库\python.exe" set "PY=%ROOT%运行库\python.exe"
if not defined PY if exist "D:\soft\python\python.exe" set "PY=D:\soft\python\python.exe"
if not defined PY for /f "delims=" %%F in ('where python.exe 2^>nul') do if not defined PY set "PY=%%F"
if not defined PY (
  echo   [错误] 没找到 Python，无法重置。
  pause
  exit /b 1
)

"%PY%" "%~dp0重置密码.py" %*
echo.
pause
