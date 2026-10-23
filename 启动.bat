@echo off
chcp 936 >nul
title 人数发票登记系统
cd /d "%~dp0"

set "ROOT=%~dp0"
set "SRV="

rem ---- 1. 定位程序文件：先按约定名字找，找不到就全盘搜（防止解压工具搞乱中文文件夹名）----
if exist "%ROOT%程序\server.py" set "SRV=%ROOT%程序\server.py"
if not defined SRV if exist "%ROOT%app\server.py" set "SRV=%ROOT%app\server.py"
if not defined SRV if exist "%ROOT%src\server.py" set "SRV=%ROOT%src\server.py"
if not defined SRV for /f "delims=" %%F in ('dir /b /s "%ROOT%server.py" 2^>nul') do if not defined SRV set "SRV=%%F"

if not defined SRV goto NO_APP

rem ---- 2. 定位 Python ----
set "PY="
if exist "%ROOT%runtime\python.exe" set "PY=%ROOT%runtime\python.exe"
if not defined PY if exist "D:\soft\python\python.exe" set "PY=D:\soft\python\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python314\python.exe" set "PY=%LocalAppData%\Programs\Python\Python314\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python313\python.exe" set "PY=%LocalAppData%\Programs\Python\Python313\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python312\python.exe" set "PY=%LocalAppData%\Programs\Python\Python312\python.exe"
if not defined PY if exist "%LocalAppData%\Programs\Python\Python311\python.exe" set "PY=%LocalAppData%\Programs\Python\Python311\python.exe"
if not defined PY if exist "C:\Python314\python.exe" set "PY=C:\Python314\python.exe"
if not defined PY if exist "C:\Python313\python.exe" set "PY=C:\Python313\python.exe"
if not defined PY if exist "C:\Python312\python.exe" set "PY=C:\Python312\python.exe"
if not defined PY for /f "delims=" %%F in ('where python.exe 2^>nul') do if not defined PY set "PY=%%F"
if not defined PY for /f "delims=" %%F in ('where py.exe 2^>nul') do if not defined PY set "PY=%%F"
if not defined PY goto NO_PYTHON

rem ---- 3. 检查 Python 版本（顺手挡掉微软商店的占位程序和过老版本）----
"%PY%" -c "import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)" >nul 2>&1
if errorlevel 1 goto BAD_PYTHON

echo.
echo   正在启动人数发票登记系统，浏览器稍后会自动打开……
echo   请不要关闭本窗口（关闭本窗口就是退出系统）。
echo.

rem ---- 4. 启动 ----
"%PY%" "%SRV%" %*
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" goto OK_EXIT
if "%RC%"=="3" goto ALREADY
goto APP_ERROR

:OK_EXIT
echo   系统已退出，数据已实时保存。
pause
exit /b 0

:ALREADY
echo   系统之前已经在运行，浏览器里已经打开了。
pause
exit /b 0

:APP_ERROR
echo   ============================================================
echo   [启动失败] 程序异常退出，错误代码 %RC%
echo   ============================================================
echo   上面那行英文或报错信息，就是失败的原因。最常见两种情况：
echo.
echo     1^) 压缩包没有完整解压
echo        症状： can't open file ... server.py
echo        原因： 直接在压缩包里双击了 启动.bat，只有这一个文件被
echo              临时解出来，旁边的文件夹都不在。
echo        解决： 右键压缩包 →「全部解压到当前文件夹」，
echo              然后进入解压出来的文件夹，再双击 启动.bat。
echo.
echo     2^) 文件夹被拆散了
echo        「程序」「网页」「运行库」必须和 启动.bat 放在同一层，
echo        不能只把 启动.bat 单独拖到桌面上运行。
echo.
echo   请把本窗口截图后发给管理员。
pause
exit /b %RC%

:NO_APP
echo.
echo   [启动失败] 找不到程序文件 server.py
echo.
echo   这个窗口所在的文件夹是：
echo       %ROOT%
echo.
echo   里面应该还有「程序」「网页」「运行库」三个文件夹，现在没找到。
echo.
echo   最常见的原因是：直接在压缩包里双击了 启动.bat。
echo   正确做法：
echo       1. 右键压缩包 →「全部解压到当前文件夹」
echo       2. 进入解压出来的文件夹
echo       3. 再双击里面的 启动.bat
echo.
echo   请把本窗口截图后发给管理员。
pause
exit /b 2

:NO_PYTHON
echo.
echo   [启动失败] 没有找到 Python，无法启动。
echo.
echo   请先安装 Python 3.10 以上版本，安装时务必勾选
echo   "Add python.exe to PATH"，装好后重新双击 启动.bat。
echo.
echo   下载地址： https://www.python.org/downloads/windows/
echo.
echo   请把本窗口截图后发给管理员。
pause
exit /b 1

:BAD_PYTHON
echo.
echo   [启动失败] 找到的 Python 不能用。
echo.
echo   程序路径： %PY%
echo.
echo   可能是版本太老（需要 3.10 以上），也可能是微软商店的
echo   占位程序（不是真正的 Python）。
echo.
echo   建议到官网重新安装 Python 3.12 或更高版本，安装时勾选
echo   "Add python.exe to PATH"：
echo       https://www.python.org/downloads/windows/
echo.
echo   请把本窗口截图后发给管理员。
pause
exit /b 1
