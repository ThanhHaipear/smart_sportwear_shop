@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-recommendation-service.ps1"
exit /b %errorlevel%
