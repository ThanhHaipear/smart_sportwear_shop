@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-search-service.ps1" -Mode worker
exit /b %errorlevel%
