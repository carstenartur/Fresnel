@echo off
rem Build a Windows jpackage installer (.msi by default; .exe and
rem app-image also supported via JPACKAGE_TYPE). See README.md.
setlocal ENABLEEXTENSIONS ENABLEDELAYEDEXPANSION

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "REPO_ROOT=%%~fI"
set "TARGET_DIR=%REPO_ROOT%\backend\target"

if "%APP_JAR%"=="" (
    for %%F in ("%TARGET_DIR%\backend-*.jar") do (
        echo %%~nxF | findstr /R "sources javadoc original" >nul
        if errorlevel 1 set "APP_JAR=%%~fF"
    )
)
if not exist "%APP_JAR%" (
    echo build-windows.cmd: cannot find backend jar; run 'mvn -B -ntp package' first 1>&2
    exit /b 1
)

rem Derive APP_VERSION from the jar filename if not provided.
rem Use a separate for-loop (outside any if-block) and delayed expansion
rem so the value is reliably available on the next line.
if "%APP_VERSION%"=="" (
    for %%F in ("%APP_JAR%") do set "JAR_NAME=%%~nF"
    set "APP_VERSION=!JAR_NAME:backend-=!"
)

rem jpackage rejects -SNAPSHOT and other qualifiers; keep only the
rem leading numeric "X.Y.Z" part. Use delayed expansion so we read the
rem value set above in the same script.
for /f "tokens=1 delims=-" %%v in ("!APP_VERSION!") do set "APP_VERSION_NUM=%%v"

if "%JPACKAGE_TYPE%"=="" set "JPACKAGE_TYPE=msi"
if "%OUTPUT_DIR%"=="" set "OUTPUT_DIR=%TARGET_DIR%\dist"
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

rem Stage the jar plus the external-config template into an input
rem directory; jpackage copies everything from --input into the app's
rem lib/ (which is also $APPDIR at runtime), so the bundled
rem application-standalone.properties ends up at
rem $APPDIR/config/application-standalone.properties and users can edit
rem it post-install.
set "STAGE_DIR=%TEMP%\fresnel-jpackage-%RANDOM%"
mkdir "%STAGE_DIR%"
mkdir "%STAGE_DIR%\config"
copy /Y "%APP_JAR%" "%STAGE_DIR%\fresnel.jar" >nul
copy /Y "%REPO_ROOT%\packaging\config\application-standalone.properties" "%STAGE_DIR%\config\" >nul

set "JPACKAGE_CMD=jpackage"
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\jpackage.exe" set "JPACKAGE_CMD=%JAVA_HOME%\bin\jpackage.exe"

echo build-windows.cmd: building %JPACKAGE_TYPE% for Fresnel !APP_VERSION_NUM!

"%JPACKAGE_CMD%" ^
  --type %JPACKAGE_TYPE% ^
  --name Fresnel ^
  --app-version !APP_VERSION_NUM! ^
  --vendor "Fresnel" ^
  --description "Fresnel diffractive-optics designer" ^
  --input "%STAGE_DIR%" ^
  --main-jar fresnel.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --java-options "-Dspring.profiles.active=standalone" ^
  --java-options "-Dspring.config.additional-location=optional:file:$APPDIR/config/" ^
  --dest "%OUTPUT_DIR%" ^
  --win-shortcut ^
  --win-menu ^
  --win-menu-group "Fresnel" ^
  --win-dir-chooser
set "RC=%ERRORLEVEL%"

rmdir /S /Q "%STAGE_DIR%"

if not "%RC%"=="0" (
    echo build-windows.cmd: jpackage failed with exit code %RC% 1>&2
    exit /b %RC%
)

echo build-windows.cmd: artifacts in %OUTPUT_DIR%
dir "%OUTPUT_DIR%"
endlocal
