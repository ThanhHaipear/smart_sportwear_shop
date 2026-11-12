@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-catalog-service.ps1"
exit /b %errorlevel%
