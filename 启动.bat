@echo off
title OfferChaser Launcher
chcp 65001 >nul
set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
cd /d "%~dp0"

rem ---- Already running? just open the admin UI ----
netstat -ano | findstr ":9527" | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 goto :already_running

rem ---- Check bundled JDK 21 ----
set "JAVA_HOME=%~dp0.jdk\jdk-21.0.13+9"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] Bundled JDK not found:
    echo         %JAVA_HOME%
    echo Please check the .jdk folder under this directory.
    echo.
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- Prevent external env vars from overriding server.port ----
set "SERVER__PORT="
set "SERVER_PORT="

echo ==========================================
echo   OfferChaser starting...
echo   Admin UI : http://localhost:6866
echo   Backend  : http://localhost:9527
echo   First start takes 10-30s, keep this window open
echo   Close this window to stop the service.
echo ==========================================
echo.

call gradlew.bat bootRun --console=plain --args=--server.port=9527

echo.
echo ==========================================
echo   OfferChaser has stopped.
echo ==========================================
pause
exit /b 0

:already_running
echo ==========================================
echo   OfferChaser is already running.
echo   Opening admin UI: http://localhost:6866
echo ==========================================
start "" http://localhost:6866
ping -n 3 127.0.0.1 >nul
exit /b 0
