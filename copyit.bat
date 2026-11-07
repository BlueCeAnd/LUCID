@echo off
rem Copies the built jar into the "My MOD - FABRIC - 1.21.1" instance and makes config\restyler.
set "JAR=%~dp0build\libs\restyler-4.0.0.jar"
set "MC=%APPDATA%\.minecraft\versions"

rem Never replace the jar while Minecraft runs: Java reads classes from it lazily, a swapped jar crashes the game
rem later ("ZipFile invalid LOC header", e.g. when the menu is opened for the first time).
tasklist /FI "IMAGENAME eq javaw.exe" 2>nul | find /I "javaw.exe" >nul && (
    echo Minecraft ^(javaw.exe^) is running - close it first, then run this again.
    choice /C YN /M "Copy anyway (only if javaw.exe is not Minecraft)"
    if errorlevel 2 exit /b 1
)

if not exist "%JAR%" (
    echo Build first:  gradlew build
    pause
    exit /b 1
)
for /d %%D in ("%MC%\My MOD * FABRIC - 1.21.1") do (
    if not exist "%%D\mods_off" mkdir "%%D\mods_off"
    for %%J in ("%%D\mods\restyler-*.jar") do if /I not "%%~nxJ"=="restyler-4.0.0.jar" (
        move /Y "%%J" "%%D\mods_off\" >nul
        echo Parked old jar in mods_off: %%~nxJ
    )
    copy /Y "%JAR%" "%%D\mods\restyler-4.0.0.jar"
    if not exist "%%D\config\restyler" mkdir "%%D\config\restyler"
    echo.
    echo Copied to: %%D\mods\
    echo Settings live in: %%D\config\restyler\  ^(CLIP installs on first /vibe use^)
    if exist "%%D\mods\snowfilter-0.1.0.jar" echo WARNING: remove %%D\mods\snowfilter-0.1.0.jar - both mods restyle the same frame.
    goto :done
)
echo ERROR: Minecraft version folder not found.
pause
exit /b 1

:done
pause
