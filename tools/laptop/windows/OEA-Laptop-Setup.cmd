@echo off
setlocal
rem Double-click entry point. Everything real happens in the PowerShell file
rem next to this one; this wrapper only keeps the window open so the result is
rem readable.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0OEA-Laptop-Setup.ps1" %*
set "EXITCODE=%ERRORLEVEL%"
echo.
if not "%EXITCODE%"=="0" echo Setup failed with exit code %EXITCODE%.
pause
exit /b %EXITCODE%
