@echo off
setlocal
python "%~dp0scripts\nodal.py" %*
exit /b %ERRORLEVEL%
