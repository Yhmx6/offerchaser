@echo off
setlocal
cd /d "%~dp0"
title OfferChaser One-Click Deploy

if not exist "%~dp0deploy.ps1" (
  echo.
  echo [ERROR] deploy.ps1 is missing.
  echo         Download the repository files again and retry.
  echo.
  pause
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo [ERROR] Deployment did not finish.
  echo         Screenshot this window and report at:
  echo         https://github.com/Yhmx6/offerchaser/issues
  echo.
)

exit /b %RC%
