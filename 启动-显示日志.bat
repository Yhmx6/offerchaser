@echo off
chcp 65001 >nul
rem 启动.bat --console 的快捷入口：保留控制台窗口显示实时启动日志（排障用）。
rem 等价于在命令行运行：启动.bat --console
if not exist "%~dp0启动.bat" (
  echo [ERROR] 启动.bat not found in this folder.
  pause
  exit /b 1
)
call "%~dp0启动.bat" --console
