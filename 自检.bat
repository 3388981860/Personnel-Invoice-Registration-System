@echo off
chcp 936 >nul
title 人数发票登记台账 - 自检
cd /d "%~dp0"

set "ROOT=%~dp0"
set "RPT=%ROOT%自检报告.txt"

if not exist "%ROOT%自检.py" (
    echo.
    echo   [错误] 找不到 自检.py
    echo   请确认「自检.bat」和「自检.py」都放在项目文件夹里
    echo   ^(和 启动.bat 同一层^)。
    echo.
    pause
    exit /b 1
)

rem ---- 找 Python ----
set "PY="
if exist "%ROOT%runtime\python.exe" set "PY=%ROOT%runtime\python.exe"
if not defined PY if exist "D:\soft\python\python.exe" set "PY=D:\soft\python\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python314\python.exe" set "PY=%LocalAppData%\Programs\Python\Python314\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python313\python.exe" set "PY=%LocalAppData%\Programs\Python\Python313\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python312\python.exe" set "PY=%LocalAppData%\Programs\Python\Python312\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python311\python.exe" set "PY=%LocalAppData%\Programs\Python\Python311\python.exe"
if not defined PY for /f "delims=" %%F in ('where python.exe 2^>nul') do if not defined PY set "PY=%%F"
if not defined PY for /f "delims=" %%F in ('where py.exe 2^>nul') do if not defined PY set "PY=%%F"

if not defined PY goto NO_PY

echo.
echo   正在检查，请稍等几秒……
echo.

"%PY%" "%ROOT%自检.py"
set "RC=%ERRORLEVEL%"

echo.
if exist "%RPT%" (
    echo   ============================================================
    echo   检查完成。报告已生成：
    echo       %RPT%
    echo.
    echo   请把「自检报告.txt」这个文件发给管理员。
    echo   ============================================================
    echo.
    choice /c YN /m "   现在打开报告看一下吗"
    if errorlevel 2 goto END
    start "" notepad "%RPT%"
) else (
    echo   [出错] 没有生成报告，上面应该有报错信息。
    echo   请把本窗口截图发给管理员。
)
goto END

:NO_PY
echo.
echo   [错误] 没有找到 Python，无法自检。
echo.
echo   请先安装 Python 3.10 以上版本，安装时务必勾选
echo   "Add python.exe to PATH"。
echo.
echo   下载地址： https://www.python.org/downloads/windows/
echo.
echo   请把本窗口截图发给管理员。
pause
exit /b 1

:END
echo.
pause
exit /b 0
