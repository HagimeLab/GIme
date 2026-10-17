@echo off
setlocal
rem Build GIme APKs. Machine-specific toolchain paths live in build.local.cmd
rem (gitignored) so the source package never carries this machine's absolute paths.
if exist "%~dp0build.local.cmd" call "%~dp0build.local.cmd"
if not defined JAVA_HOME goto nojava
if not defined ANDROID_HOME goto nosdk
set "PATH=%JAVA_HOME%\bin;%PATH%"
call gradle -p "%~dp0." assembleDebug assembleRelease %*
if errorlevel 1 goto fail
echo.
echo debug   : %~dp0app\build\outputs\apk\debug\app-debug.apk
echo release : %~dp0app\build\outputs\apk\release\app-release.apk
endlocal
exit /b 0

:nojava
echo JAVA_HOME is not set. Create build.local.cmd with your JDK 21 path,
echo or export JAVA_HOME before running this script.
exit /b 1

:nosdk
echo ANDROID_HOME is not set. Create build.local.cmd with your Android SDK path.
exit /b 1

:fail
echo BUILD FAILED
endlocal
exit /b 1
