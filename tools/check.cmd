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
echo === golden cases + dialect canary ===
node "%~dp0golden-check.mjs"
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
echo === pdf text tidy / reject rules ===
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\PdfTidy.java" "%~dp0PdfTidyTest.java"
if errorlevel 1 goto fail
java -cp "%OUT%" PdfTidyTest
if errorlevel 1 goto fail
echo.
echo === file header sniff (missing extension) ===
rem TextExtract needs android.content.Context and PDFBox on the desktop classpath.
rem Both already exist in the build environment; when either is missing this section
rem is skipped with a visible note instead of pretending "not tested" means "passed".
set "ANDROID_JAR="
for /d %%d in ("%ANDROID_HOME%\platforms\android-*") do set "ANDROID_JAR=%%d\android.jar"
set "PDFBOX_JAR="
for /f "delims=" %%f in ('dir /b /s "%USERPROFILE%\.gradle\caches\9.7.1\transforms\pdfbox-android-2.0.27.0" 2^>nul') do if not defined PDFBOX_JAR if exist "%%f\jars\classes.jar" set "PDFBOX_JAR=%%f\jars\classes.jar"
if not exist "%ANDROID_JAR%" goto nosniff
if not defined PDFBOX_JAR goto nosniff
if not exist "%PDFBOX_JAR%" goto nosniff
javac -encoding UTF-8 -cp "%ANDROID_JAR%;%PDFBOX_JAR%;%OUT%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\PdfTidy.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\PdfText.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\TextExtract.java" "%~dp0SniffTest.java"
if errorlevel 1 goto fail
java -cp "%OUT%" com.fan.shuatiku.SniffTest
if errorlevel 1 goto fail
goto afterSniff
:nosniff
echo   [SKIP] android.jar or pdfbox classes.jar not found; sniff test skipped
:afterSniff
echo.
echo === deepseek hash (Keccak-23 + pow solve) ===
rem android.jar ships org.json but the desktop JDK does not. Cache one copy so
rem these checks stay offline after the first successful run.
set "JSON_JAR=%~dp0..\build\test-libs\json-20240303.jar"
if not exist "%JSON_JAR%" (
  if not exist "%~dp0..\build\test-libs" mkdir "%~dp0..\build\test-libs"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile '%JSON_JAR%' -UseBasicParsing -TimeoutSec 60 } catch { }"
)
if not exist "%JSON_JAR%" goto nojson
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\DeepSeekHashV1.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\DeepSeekClient.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\DeepSeekWeb.java" "%~dp0../app/src/main/java/com/fan/shuatiku/HttpNet.java" "%~dp0DeepSeekHashTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" com.fan.shuatiku.DeepSeekHashTest
if errorlevel 1 goto fail
echo.
echo === deepseek client (prompt flatten / tool_call / sse dialect) ===
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\DeepSeekHashV1.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\DeepSeekClient.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\DeepSeekWeb.java" "%~dp0../app/src/main/java/com/fan/shuatiku/HttpNet.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\RiskGuard.java" "%~dp0DeepSeekClientTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" com.fan.shuatiku.DeepSeekClientTest
if errorlevel 1 goto fail
echo.
echo === risk guard (freeze / daily budget / answer cache) ===
rem 纯逻辑类，不依赖 Android 也不需要 org.json，单独编一遍即可。
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\RiskGuard.java" "%~dp0RiskGuardTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%" com.fan.shuatiku.RiskGuardTest
if errorlevel 1 goto fail
echo.
echo === js bridge guard (url / download source / callback id) ===
rem 桥方法对页面完全开放，这几条规则是 P0 的安全底线：
rem 交给系统的地址只认 http(s)，下载包只认发布源白名单，回调 id 必须像自己发出去的。
rem BridgeGuard 刻意不依赖 Android，这里连 DownloadSources 一起编，顺带钉住
rem 「白名单和生成规则不许脱节」——两边任何一边改动都会在这一段先红。
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\DownloadSources.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\BridgeGuard.java" "%~dp0BridgeTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%" com.fan.shuatiku.BridgeTest
if errorlevel 1 goto fail
echo.
echo === openai stream (api-key mode sse parsing) ===
rem 需要本地 mock（mock-openai-sse.py）；没起时测试自己 SKIP，不算失败。
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\OpenAiStream.java" "%~dp0OpenAiStreamTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" com.fan.shuatiku.OpenAiStreamTest
if errorlevel 1 goto fail
goto afterJson
:nojson
echo   [SKIP] org.json jar unavailable (no network on first run); deepseek checks skipped
:afterJson
echo.
echo === plan store (rev lock / atomic save / crash recovery) ===
rem 方案库是 P1 的地基：页面、MCP 工具、提醒都读同一份 plans.json。
rem 这里钉住三件事——派生字段算得对、rev 对不上就不覆盖、落盘死在任意一步都能恢复。
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\agent\PlanStore.java" "%~dp0PlanStoreTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" com.fan.shuatiku.agent.PlanStoreTest
if errorlevel 1 goto fail
echo.
echo === tool registry (10 schemas / fixed order / annotations) ===
rem MCP tools/list 与 AgentLoop 的 tools 参数都读这一份；这里钉一红，两边都不会漂。
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\agent\PlanStore.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\agent\ToolRegistry.java" "%~dp0ToolRegistryTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" ToolRegistryTest
if errorlevel 1 goto fail
echo.
echo === memory store (profile / session / clear) ===
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\agent\MemoryStore.java" "%~dp0MemoryStoreTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" MemoryStoreTest
if errorlevel 1 goto fail
echo.
echo === agent loop (budget / repeat / hitl) ===
rem 引擎只管闸门，不碰网络与工具；四类剧本都在 AgentLoopTest 里穷举。
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\agent\RunTrace.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\agent\AgentLoop.java" "%~dp0AgentLoopTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" AgentLoopTest
if errorlevel 1 goto fail
echo.
echo === run trace (spans / metrics / redaction) ===
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\agent\RunTrace.java" "%~dp0RunTraceTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" RunTraceTest
if errorlevel 1 goto fail
echo.
echo === mcp server (loopback / token / 202) ===
javac -encoding UTF-8 -cp "%JSON_JAR%" -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\McpServer.java" "%~dp0McpServerTest.java"
if errorlevel 1 goto fail
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" McpServerTest
if not errorlevel 1 goto mcpOk
rem 首跑偶发回环超时（本机防火墙/端口回收慢），单跑一次就能分辨：
rem 复跑过 = 环境偶发；复跑还红 = 代码问题。红灯不再含糊。
echo   [RETRY] McpServerTest failed on first run; retrying once to tell code bug from loopback flake
java "-Dfile.encoding=UTF-8" -cp "%OUT%;%JSON_JAR%" McpServerTest
if errorlevel 1 goto fail
:mcpOk
echo.
echo === release manifest (version/apk name single source of truth) ===
javac -encoding UTF-8 -d "%OUT%" "%~dp0..\app\src\main\java\com\fan\shuatiku\DownloadSources.java" "%~dp0..\app\src\main\java\com\fan\shuatiku\ReleaseManifest.java" "%~dp0ReleaseManifestTest.java"
if errorlevel 1 goto fail
java -cp "%OUT%" com.fan.shuatiku.ReleaseManifestTest
if errorlevel 1 goto fail
echo.
echo === release folder (manifest points at an existing, matching apk) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-release.ps1"
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
