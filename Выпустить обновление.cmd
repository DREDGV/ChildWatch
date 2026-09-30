@echo off
rem One-button release of both applications for over-the-air update.
rem Double-clicking this file builds, publishes and verifies. The window stays
rem open at the end so the result can be read.
title Release ChildWatch
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-updates.ps1"
echo.
echo ---------------------------------------------------------------
echo The window is kept open so you can read the result above.
pause
