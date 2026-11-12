@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-identity-service.ps1"
exit /b %errorlevel%
