# JS 桥方法表

`Android` 对象是 WebView 里页面与原生之间唯一的通道（`addJavascriptInterface(new Bridge(), "Android")`，见 `MainActivity.java:135`）。页面代码可以调其中任何一个方法，参数完全由页面决定。

这张表是 **P0 的导出面**：把现在暴露出去的东西一次列清楚，标明每个方法的入参形状与信任级别，供 P2 设计 AgentLoop 的工具注册时对照，也供后续安全收口时判断「哪些口子必须先收紧」。

- 生成依据：`app/src/main/java/com/fan/shuatiku/MainActivity.java` 里全部 `@JavascriptInterface` 注解方法（当前 **37 个**）。
- P0 已加闸的方法在「P0 校验」一列写明；标 `—` 的表示本次未改动（见文末「P0 没做什么」）。
- 校验规则集中在 `BridgeGuard.java`（纯逻辑、零 Android 依赖），回归用例在 `tools/BridgeTest.java`，由 `tools/check.cmd` 的 `js bridge guard` 段执行。

## 信任级别定义

| 级别 | 含义 | 处理原则 |
| --- | --- | --- |
| **L0 只读** | 只返回数据，不改任何状态 | 可自由调用 |
| **L1 本地写** | 只影响 App 自己的数据（题库、偏好） | 入参需可预期；已有 `BankStore.safe()` 之类兜底 |
| **L2 对外发起** | 会发网络请求或拉起系统界面 | **入参必须过闸**，P0 已覆盖主要几项 |
| **L3 敏感** | 携带凭据、可替换安装包、可执行外部动作 | 最严：来源白名单 + 长度上限；P2 起应改为「一次性令牌」而非直接暴露 |

## 全量方法表

### 生命周期与状态

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `pageReady()` | — | `void` | L0 | — | 页面脚本与事件函数挂好后调用一次；之后才放行排队的导入、启动 MCP 服务 |
| 2 | `mcpStatus()` | — | `String`(JSON) | L0 | — | MCP 服务状态：`{enabled,running,port,token,error}` |
| 3 | `dsStatus()` | — | `String`(JSON) | L0 | — | 内置通道（DeepSeek 登录态）状态 |
| 4 | `appVersion()` | — | `String` | L0 | — | 当前 `versionName`，页面用它和 Release tag 比对 |
| 5 | `themeHint(light)` | `boolean` | `void` | L1 | — | 同步系统栏图标深浅 + 记住冷启动纸色（写 SharedPreferences） |

### 网络与更新

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 6 | `chatApi(id, url, body, key)` | `id`、`url`、`body`、`key` | `void`（异步回 `window.onChatApi` / `window.onChatStream`） | **L3** | **id 形状 + url 协议 + body 长度** | 桥里权限最大的口子：`body` 原样发出，`key` 可能随之走。拒时回 `onChatApi(id, 400, …)` |
| 7 | `fpProbe(url)` | `url` | `void`（回 `window.__fp`） | L2 | **协议白名单 + 长度** | 仅 debug 生效（发布包直接 return）；按给定地址带 App UA 发一次 GET |
| 8 | `checkUpdate()` | — | `void` | L2 | — | 打 GitHub Releases API 取最新版 |
| 9 | `downloadUpdate(url)` | `url` | `void` | **L3** | **https + 主机白名单 + 路径/后缀** | 交给系统下载器，下完拉安装页 |
| 10 | `downloadUpdateFrom(urlsJson)` | `String`(JSON 数组) | `void` | **L3** | **逐条过滤，全被拒则整体拒** | 多源逐个试；被跳过的写日志 |
| 11 | `openUrl(url)` | `url` | `void` | L2 | **只允许 http(s)** | `Intent.ACTION_VIEW` 打开系统浏览器 |
| 12 | `openInstallPermission()` | — | `void` | L2 | — | 跳「安装未知应用」授权页 |

### 大肥鱼对话（内置通道）

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 13 | `dsLogin()` | — | `void` | L2 | — | 打开 DeepSeek 登录页 |
| 14 | `dsNewChat(convKey)` | `convKey` | `void` | L2 | — | 让上游也新开会话，保证两边一一对应 |
| 15 | `dsDeleteChat(convKey)` | `convKey` | `void` | L2 | — | 删除网页侧对应会话 |
| 16 | `dsLogout()` | — | `void` | L2 | — | 清登录态 |
| 17 | `dsCancel(id)` | `id` | `void` | L2 | **id 形状** | 掐断还在跑的请求；先 `chatEmit.drop(id)` 再 `cancel`，避免残留半句 |

### 方案库（P1）

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 18 | `planSnapshot()` | — | `String`(JSON) | L0 | — | 读全部方案：`{rev, plans:[...]}`；`plans` 只带派生字段与当天排期，不带整份 `days` |
| 19 | `planCreate(goal, days, title, daysJson, clientRev)` | `goal`、`days`、`title`、`daysJson`、`clientRev` | `String`(JSON) | L1 | — | 新建方案，回 `{rev, plan}` 或 `{error}`；`daysJson` 为空时用内置模板拆解 |
| 20 | `planUpdate(id, action, argsJson, clientRev)` | `id`、`action`、`argsJson`、`clientRev` | `String`(JSON) | L1 | — | 改方案；`action` 只认 `PlanStore.ACTIONS` 那九个，其余抛错 |
| 21 | `planDelete(id, clientRev)` | `id`、`clientRev` | `String`(JSON) | L1 | — | 删方案 |

四个方法都由 `PlanStore` 兜底：**rev 对不上就返回 `rev: -1` 且不落盘**，不抛异常也不回 `null`——页面侧拿到的一定是能 `JSON.parse` 的串，错误也包成 `{"error":"…"}`。方案库落在 `filesDir/plans/plans.json`，与 `banks/` 平级。
### MCP 服务

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 22 | `mcpReply(id, json)` | `id`、`json` | `void` | L2 | **id 形状 + 回包长度 + 未知 id 打日志** | 页面把工具执行结果交回 HTTP 线程；`id` 形如 `mcp3` |

### 文件与题库

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 23 | `pickFile()` | — | `void`（回调带结果） | L2 | — | 拉起系统文件选择器（导入题库） |
| 24 | `pickChatFile()` | — | `void` | L2 | — | 拉起文件选择器（大肥鱼附件） |
| 25 | `loadBanks()` | — | `String`(JSON 数组) | L0 | — | 启动时一次性读回全部已导入题库 |
| 26 | `saveBank(id, json)` | `id`、`json` | `boolean` | L1 | — | 写题库文件；落盘前经 `BankStore.save` |
| 27 | `deleteBank(id)` | `id` | `boolean` | L1 | — | 删题库文件；`BankStore.safe(id)` 兜底路径 |
| 28 | `importSample()` | — | `String` | L0 | — | 读内置示例题库原文 |
| 29 | `saveImportSample()` | — | `String` | L1 | — | 把示例题库写到外部下载目录（Q+ 走 MediaStore） |

### 界面与剪贴板

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 30 | `copyText(text)` | `text` | `boolean` | L1 | — | 写系统剪贴板（设置页复制地址/口令） |
| 31 | `ouiAction(action, payloadJson)` | `action`、`payloadJson` | `void` | L1 | — | OpenUI 卡片按钮点击；P0 只打一行 `GImeOui` 日志留痕，动作路由留到 P1 |

### 提醒与系统权限（P1）

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 32 | `planRemindState()` | — | `String`(JSON) | L0 | — | `{granted, battery, sdk}`：通知是否放行、是否已豁免省电、系统版本 |
| 33 | `planRemindAsk()` | — | `void` | L1 | — | Android 13+ 申请 `POST_NOTIFICATIONS`；已拒绝/系统关闭则跳应用通知设置 |
| 34 | `planBatteryAsk()` | — | `void` | L1 | — | 跳系统省电豁免列表；找不到该页时退到应用详情 |
| 35 | `planRemindTest(slot)` | `slot` | `void` | L1 | **仅 debug** | 进程内投递一次提醒：1/2 早/晚提醒、0 模拟开机广播、-1 只取消闹钟；发布包直接 return |

### 方案详情（P1 / S3）

| # | 方法 | 入参 | 返回 | 级别 | P0 校验 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 36 | `planGet(id)` | `id` | `String`(JSON) | L0 | — | 读一份完整方案：`{plan:{days,adjustments,…}}`；详情页逐日渲染用，摘要列表故意不带 `days` |
| 37 | `notifyPhone(title, body)` | `title`、`body` | `boolean` | L1 | — | 立即弹一条方案通知（渠道 `gime-plan`）；body 为空或系统没放行时返回 false |

## P0 加的闸门（`BridgeGuard`）

四条规则，全部纯字符串判断，不依赖 Android，可在桌面直接跑单测。

| 闸门 | 方法 | 规则 |
| --- | --- | --- |
| `okId(id)` | `mcpReply` / `chatApi` / `dsCancel` | 非空、≤ 64 字符、只含 `[A-Za-z0-9_-]`。页面实际生成的是 `c12`（`app-chat.js:1847`）与 `mcp3`（`MainActivity.java:532`）这种形状 |
| `navUrl(url)` / `probeUrl(url)` | `openUrl` / `fpProbe` | 只允许 `http(s)://`；必须有主机；不允许 `@` 用户信息；≤ 2048 字符。`probeUrl` 额外放行空串（表示用内置默认页） |
| `chatUrl(url)` | `chatApi` | `builtin://deepseek` 或 http(s)，同上长度与主机约束 |
| `downloadUrl(url)` | `downloadUpdate` / `downloadUpdateFrom` | **必须 https**；主机必须是六个发布源之一；路径必须含 `/HagimeLab/GIme` 与 `/release/`；必须以 `.apk` 结尾；不允许用户信息 |
| `body(s)` | `chatApi` | ≤ 512 KB。页面单次上行按 18000 字切段（`CHAT_SEG_MAX`），实际远低于此 |
| `reply(json)` | `mcpReply` | ≤ 4 MB（整库题目回包可能较大）；`null` 仍按空串处理，与原有语义一致 |

下载源白名单与 `DownloadSources.forFile()` 生成的六个入口一一对应：

```
cdn.jsdelivr.net / fastly.jsdelivr.net / testingcf.jsdelivr.net
gcore.jsdelivr.net / github.com / raw.githubusercontent.com
```

`BridgeTest` 里有一条专门的断言：直接调 `DownloadSources.forVersion("HagimeLab/GIme", "1.1.2")` 生成真实地址，逐个过闸。这样白名单和生成规则任何一边改动，测试都会先红——避免「白名单写着 A，页面实际请求 B」这类静默脱节。

## 被拒时的行为

| 方法 | 拒绝后的表现 |
| --- | --- |
| `openUrl` | 弹 `window.toast("这个链接不是网页地址，已拦下")`，不拉起任何界面 |
| `chatApi` | 回 `window.onChatApi(id, 400, {error:{message:…}})`，页面按普通失败处理 |
| `downloadUpdate` / `downloadUpdateFrom` | 回 `window.onUpdateError("更新包地址不可信，已拒绝。可以点「打开发布页」手动下载")` |
| `mcpReply` / `dsCancel` / `fpProbe` | 静默丢弃 + 一行 `android.util.Log.w("GImeBridge", …)` |

日志统一用 tag `GImeBridge`。页面自己的 `console` 进不了 logcat（App 没装 `WebChromeClient`），所以被拒一律在原生侧留痕，现场排查时 `adb logcat -s GImeBridge` 即可。

## P0 没做什么

按方案（`P0地基具体执行方案.md:82`）明确留到后续：

- **origin 校验**：现在任何加载到 WebView 里的源都能调桥。P3 再做「按页面分组注入 + 只对 `file://` 主文档暴露桥对象」。
- **一次性令牌**：`chatApi` 的 `key` 参数仍由页面直接给。P2 起应改为原生侧持有凭据、页面只传会话 id。
- **`ReleaseManifest` 深度校验**：当前下载闸门只保证「地址形状可信」，不校验「这个 apk 是不是清单里声明的那一个」。真正的防篡改要等签名校验，属 P2。
- 其余 L1/L2 方法（`saveBank`、`pickFile`、`copyText` 等）本次**未改动**，保持原行为。

## 维护约定

新增 `@JavascriptInterface` 方法时必须同步更新本表，并在 `BridgeTest` 里补一条非法入参用例。`tools/check.cmd` 的 `js bridge guard` 段是硬门禁，不过就整体失败。
