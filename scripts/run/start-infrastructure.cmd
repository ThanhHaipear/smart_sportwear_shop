@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-infrastructure.ps1"
exit /b %errorlevel%
