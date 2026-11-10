@echo off
setlocal
set "ROOT=%~dp0..\.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\run\start-search-service.ps1" %*
exit /b %ERRORLEVEL%
