@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all-local.ps1" %*
exit /b %errorlevel%
