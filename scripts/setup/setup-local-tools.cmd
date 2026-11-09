@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-local-tools.ps1"
exit /b %errorlevel%
