@echo off
set "SERVICE=%~1"
if "%SERVICE%"=="" set "SERVICE=active"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0show-local-logs.ps1" -Service "%SERVICE%"
exit /b %errorlevel%
