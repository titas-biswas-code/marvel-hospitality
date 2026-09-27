@echo off
rem Windows entry point: .\marvel <command>   (.\marvel help lists them)
rem Runs scripts\windows\marvel.ps1 with a per-process execution policy, so nothing on the machine is changed.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\marvel.ps1" %*
exit /b %ERRORLEVEL%
