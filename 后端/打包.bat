@echo off
chcp 936 >nul
rem ============================================
rem  编译打包：产出 target\invoice-server.jar
rem ============================================
set "JAVA_HOME=D:\soft\Java17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"

echo.
echo  使用 JDK: %JAVA_HOME%
echo.
call mvnw.cmd -B -DskipTests clean package
if errorlevel 1 (
  echo.
  echo  [失败] 打包出错，看上面的提示
  pause
  exit /b 1
)
echo.
echo  [完成] target\invoice-server.jar
echo.
pause