@echo off
rem GIme regression checks: page logic + bank structure + version compare.
rem Requires node and the JDK 21 toolchain used by build.cmd.
chcp 65001 >nul
setlocal
set "JAVA_HOME=C:\Users\fan\.jdks\jdk-21.0.12.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo === page logic / bank structure ===
node "%~dp0check.mjs"
if errorlevel 1 goto fail
echo.
echo === version compare ===
if not exist "%~dp0..\build\test-classes" mkdir "%~dp0..\build\test-classes"
javac -encoding UTF-8 -d "%~dp0..\build\test-classes" "%~dp0..\app\src\main\java\com\fan\shuatiku\Ver.java" "%~dp0VerTest.java"
if errorlevel 1 goto fail
java -cp "%~dp0..\build\test-classes" VerTest
if errorlevel 1 goto fail
echo.
echo ALL CHECKS PASSED
endlocal
exit /b 0

:fail
echo.
echo CHECKS FAILED
endlocal
exit /b 1
