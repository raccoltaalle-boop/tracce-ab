@echo off
title Tracce A-B - compilazione APK
echo.
echo  Compilo Tracce A-B...
echo  La prima volta scarica gli strumenti necessari (10-20 minuti).
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
echo.
pause
