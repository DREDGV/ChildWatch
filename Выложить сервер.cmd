@echo off
rem Deploys the server code that changed since the last deploy, with a backup and
rem an automatic check that the service and its data are still fine.
rem Use this when an agent has changed server files but could not send them.
title Deploy the server code
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy-server.ps1"
echo.
echo ---------------------------------------------------------------
echo The window is kept open so you can read the result above.
pause
