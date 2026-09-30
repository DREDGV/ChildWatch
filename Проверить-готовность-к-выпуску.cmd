@echo off
rem Checks whether a release could be published right now: finds the tools, the
rem key and the server. Publishes nothing. Use it when the release button says
rem the server cannot be reached.
title Check the release prerequisites
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-updates.ps1" -PreflightOnly
echo.
pause
