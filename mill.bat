@echo off
setlocal
set /p MILL_VERSION=<"%~dp0.mill-version"
set "CACHE_ROOT=%LOCALAPPDATA%\Nodal\Mill"
set "BOOTSTRAP=%CACHE_ROOT%\mill-%MILL_VERSION%.bat"
set "URL=https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/%MILL_VERSION%/mill-dist-%MILL_VERSION%-mill.bat"

if not exist "%BOOTSTRAP%" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  curl.exe --fail --location --proto "=https" --tlsv1.2 "%URL%" --output "%BOOTSTRAP%.tmp"
  if errorlevel 1 exit /b %errorlevel%
  move /y "%BOOTSTRAP%.tmp" "%BOOTSTRAP%" >nul
)

call "%BOOTSTRAP%" %*
exit /b %errorlevel%
