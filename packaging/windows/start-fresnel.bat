@echo off
rem ----------------------------------------------------------------------
rem  Fresnel - local launcher (Windows)
rem
rem  Starts the bundled Spring Boot jar with the `standalone` profile, a
rem  file-based H2 database under %FRESNEL_DATA_DIR%, and an external
rem  config directory the user can edit.
rem
rem  Layout when running from an installer or the Windows ZIP fallback:
rem    <install>\
rem      bin\start-fresnel.bat        (this file)
rem      lib\fresnel.jar              (the Spring Boot fat jar)
rem      config\application-standalone.properties
rem ----------------------------------------------------------------------
setlocal ENABLEEXTENSIONS ENABLEDELAYEDEXPANSION

rem Resolve install root relative to this script.
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "APP_HOME=%%~fI"

rem Locate the jar (prefer the renamed lib\fresnel.jar, fall back to lib\backend-*.jar).
set "APP_JAR=%APP_HOME%\lib\fresnel.jar"
if not exist "%APP_JAR%" (
    for %%F in ("%APP_HOME%\lib\backend-*.jar") do (
        if exist "%%~fF" set "APP_JAR=%%~fF"
    )
)
if not exist "%APP_JAR%" (
    echo Fresnel: could not find application jar under "%APP_HOME%\lib" 1>&2
    exit /b 1
)

rem External config directory (editable by the user).
if "%FRESNEL_CONFIG_DIR%"=="" set "FRESNEL_CONFIG_DIR=%APP_HOME%\config"

rem Per-user mutable data directory. Prefer %APPDATA% (Roaming);
rem fall back to %LOCALAPPDATA% or the user profile.
if "%FRESNEL_DATA_DIR%"=="" (
    if not "%APPDATA%"=="" (
        set "FRESNEL_DATA_DIR=%APPDATA%\Fresnel"
    ) else if not "%LOCALAPPDATA%"=="" (
        set "FRESNEL_DATA_DIR=%LOCALAPPDATA%\Fresnel"
    ) else (
        set "FRESNEL_DATA_DIR=%USERPROFILE%\Fresnel"
    )
)
if not exist "%FRESNEL_DATA_DIR%\db" mkdir "%FRESNEL_DATA_DIR%\db"

rem Pick a Java runtime. Bundled JRE (jpackage / app-image) is preferred;
rem otherwise rely on %JAVA_HOME% or `java` on PATH.
set "JAVA_CMD="
if exist "%APP_HOME%\runtime\bin\java.exe" (
    set "JAVA_CMD=%APP_HOME%\runtime\bin\java.exe"
) else if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
if "%JAVA_CMD%"=="" (
    where java >nul 2>&1
    if errorlevel 1 (
        echo Fresnel: no Java runtime found. Install a JDK 21+ or set JAVA_HOME. 1>&2
        exit /b 1
    )
    set "JAVA_CMD=java"
)

if "%SERVER_PORT%"=="" (
    echo Fresnel: starting on http://localhost:8080
) else (
    echo Fresnel: starting on http://localhost:%SERVER_PORT%
)
echo Fresnel: data directory   = %FRESNEL_DATA_DIR%
echo Fresnel: config directory = %FRESNEL_CONFIG_DIR%

"%JAVA_CMD%" ^
    -Dspring.profiles.active=standalone ^
    -Dspring.config.additional-location="optional:file:%FRESNEL_CONFIG_DIR%\\" ^
    %JAVA_OPTS% ^
    -jar "%APP_JAR%" %*

endlocal
