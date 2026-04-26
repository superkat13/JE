@echo off
setlocal
set ROOT_DIR=%~dp0
set GRADLE_VERSION=9.3.1
set GRADLE_DIR=%ROOT_DIR%\.gradle-local\gradle-%GRADLE_VERSION%
set GRADLE_ZIP=%ROOT_DIR%\.gradle-local\gradle-%GRADLE_VERSION%-bin.zip
set GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip

if not exist "%ROOT_DIR%\.gradle-local" mkdir "%ROOT_DIR%\.gradle-local"

if not exist "%GRADLE_DIR%\bin\gradle.bat" (
  powershell -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%GRADLE_URL%' -OutFile '%GRADLE_ZIP%'"
  powershell -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%GRADLE_ZIP%' '%ROOT_DIR%\.gradle-local'"
)

"%GRADLE_DIR%\bin\gradle.bat" %*
