@echo off
rem ---------------------------------------------------------------------------
rem  Builds the Windows installer (SuperBistro-1.0.0.exe) and a portable zip.
rem  Run on a Windows PC (or the GitHub Actions workflow does it for you).
rem
rem  Needs: JDK 21 (for jpackage) and, for the .exe only, WiX Toolset 3.14
rem         (choco install wixtoolset --version=3.14.1). The portable zip needs no WiX.
rem
rem  On the client PC just run the installer: Java and a built-in PostgreSQL are inside
rem  (the PostgreSQL program is added to the jar by the "windows" Maven profile when built on Windows).
rem  The first start opens a setup page: "Built-in database" (recommended) or your own PostgreSQL 15.
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0\..\.."

if not exist target\superbistro.jar (
  echo Building the app first...
  call mvnw.cmd -B -DskipTests package || exit /b 1
)

set VERSION=1.0.1
rmdir /s /q dist 2>nul
mkdir dist\input
copy /y target\superbistro.jar dist\input\ >nul

set COMMON=--input dist\input --main-jar superbistro.jar --name SuperBistro --app-version %VERSION% ^
  --vendor "Super Bistro" --description "Restaurant order desk" ^
  --java-options "-Dsuperbistro.open-browser=true" ^
  --java-options "-Xmx512m" ^
  --win-console ^
  --add-launcher Reset-Super-Bistro-Password=packaging\windows\reset-password.properties

echo.
echo [1/2] Portable app folder (no installer needed)...
jpackage --type app-image --dest dist\portable %COMMON% || exit /b 1
powershell -NoProfile -Command "Compress-Archive -Force -Path dist\portable\SuperBistro -DestinationPath dist\SuperBistro-%VERSION%-portable.zip" || exit /b 1

echo.
echo [2/2] Installer .exe...
where candle >nul 2>nul
if errorlevel 1 (
  echo WiX Toolset not found: skipping the .exe. The portable zip is ready in dist\.
  exit /b 0
)
jpackage --type exe --dest dist %COMMON% ^
  --win-menu --win-shortcut --win-dir-chooser --win-per-user-install ^
  --win-menu-group "Super Bistro" ^
  --win-upgrade-uuid 6f1e7c2a-5b3d-4c8e-9a41-2d7b8e0f3c55 || exit /b 1

echo.
echo Done. Files in dist\:
rem Same file names for every version, so the "latest release" download links never change.
copy /y dist\SuperBistro-%VERSION%.exe dist\SuperBistro-Setup.exe >nul
copy /y dist\SuperBistro-%VERSION%-portable.zip dist\SuperBistro-portable.zip >nul
dir /b dist\*.exe dist\*.zip
