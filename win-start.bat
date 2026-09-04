@echo off
rem Windows launcher for Clipboard Sync
rem Runs pythonw.exe headlessly without a command prompt window.

set SCRIPT_DIR=%~dp0
start "" pythonw.exe "%SCRIPT_DIR%daemon\app.py"
