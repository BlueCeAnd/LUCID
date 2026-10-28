@echo off
rem Modrinth allows 500 MiB per file; the built jar is ~535 MiB (ONNX Runtime GPU for Windows AND Linux inside).
rem This makes build\libs\modrinth\restyler-3.0.0.jar: only the Linux/Mac GPU providers are removed (Windows is byte-identical,
rem Linux still runs on the CPU), then the whole file is re-read and checked. Upload THAT file to Modrinth.
setlocal
cd /d "%~dp0"
set "JAR=build\libs\restyler-3.0.0.jar"
if not exist "%JAR%" (
    echo Build first:  gradlew build
    pause
    exit /b 1
)
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" -Xmx3g tools\StripNatives.java "%JAR%"
if errorlevel 1 (
    echo.
    echo FAILED - nothing was written. Send me the text above.
    pause
    exit /b 1
)
echo.
echo Upload this file to Modrinth:  build\libs\modrinth\restyler-3.0.0.jar
pause
