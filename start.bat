@echo off
rem ---------------------------------------------------------------------------
rem  Super Bistro on Windows without Docker and without the installer.
rem  Needs Java 17 or newer. Java 8 alone will NOT work, but Java 17/21 can be
rem  installed alongside it: set JAVA_HOME below to the newer one.
rem
rem  First start: a setup page opens in the browser. Enter the PostgreSQL 15
rem  "postgres" password there; Super Bistro creates its own database and saves
rem  the settings in C:\ProgramData\SuperBistro\superbistro.properties.
rem ---------------------------------------------------------------------------
setlocal

rem set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21
set OPEN_BROWSER=true
set PORT=8080
set TIME_ZONE=Asia/Kolkata

set JAVA=java
if defined JAVA_HOME set JAVA="%JAVA_HOME%\bin\java"

%JAVA% -version 2>&1 | findstr /r /c:"version \"1\.[0-8]\." >nul
if %errorlevel%==0 (
  echo.
  echo This PC's default Java is too old. Super Bistro needs Java 17 or newer.
  echo Install Java 21 from https://adoptium.net and set JAVA_HOME at the top of this file.
  pause
  exit /b 1
)

echo Starting Super Bistro on http://localhost:%PORT%  (close this window to stop)
%JAVA% -Xmx512m -jar "%~dp0superbistro.jar"
pause
