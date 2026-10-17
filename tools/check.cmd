@echo off
rem GIme regression checks: page logic + bank structure + version + bank store + download states.
rem Machine-specific toolchain paths come from build.local.cmd (gitignored).
chcp 65001 >nul
setlocal
if exist "%~dp0..\build.local.cmd" call "%~dp0..\build.local.cmd"
if not defined JAVA_HOME goto nojava
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "OUT=%~dp0..\build\test-classes"
echo === page logic / bank structure ===
node "%~dp0check.mjs"
if errorlevel 1 goto fail
echo.
echo === version compare ===
if not exist "%OUT%" mkdir "%OUT%"
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\Ver.java" "%~dp0VerTest.java"
if errorlevel 1 goto fail
java -cp "%OUT%" VerTest
if errorlevel 1 goto fail
echo.
echo === bank store (save/rollback/crash recovery) ===
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\BankStore.java" "%~dp0BankStoreTest.java"
if errorlevel 1 goto fail
java -cp "%OUT%" com.fan.shuatiku.BankStoreTest
if errorlevel 1 goto fail
echo.
echo === download outcome (update download states) ===
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\DownloadOutcome.java" "%~dp0UpdaterTest.java"
if errorlevel 1 goto fail
java -cp "%OUT%" com.fan.shuatiku.UpdaterTest
if errorlevel 1 goto fail
echo.
echo === download sources (multi-CDN fallback) ===
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\DownloadSources.java" "%~dp0DownloadSourcesTest.java"
if errorlevel 1 goto fail
java -cp "%OUT%" com.fan.shuatiku.DownloadSourcesTest
if errorlevel 1 goto fail
echo.
echo ALL CHECKS PASSED
endlocal
exit /b 0

:nojava
echo JAVA_HOME is not set. Create build.local.cmd or export JAVA_HOME first.
exit /b 1

:fail
echo.
echo CHECKS FAILED
endlocal
exit /b 1
