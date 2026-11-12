@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all-local.ps1" %*
exit /b %errorlevel%
