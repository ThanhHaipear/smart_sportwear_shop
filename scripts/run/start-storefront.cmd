@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-storefront.ps1"
exit /b %errorlevel%
