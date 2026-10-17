@echo off
setlocal
rem Pin JDK 21 for this project only - the system default java is 1.8 (eNSP needs it)
set "JAVA_HOME=C:\Users\fan\.jdks\jdk-21.0.12.1+1"
set "ANDROID_HOME=C:\dev\android-sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "C:\dev\toolchain\gradle-9.7.1\bin\gradle.bat" -p "%~dp0." assembleDebug assembleRelease %*
echo.
echo debug   : %~dp0app\build\outputs\apk\debug\app-debug.apk
echo release : %~dp0app\build\outputs\apk\release\app-release.apk
endlocal
