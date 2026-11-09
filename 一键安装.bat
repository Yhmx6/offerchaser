@echo off
setlocal
cd /d "%~dp0"
title OfferChaser One-Click Install

if not exist "%~dp0install.ps1" (
  echo.
  echo [ERROR] install.ps1 is missing.
  echo         Extract the full package and run this file again.
  echo.
  pause
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo [ERROR] Installation did not finish.
  echo         Screenshot this window and report at:
  echo         https://github.com/Yhmx6/offerchaser/issues
  echo.
)

exit /b %RC%
