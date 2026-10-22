# GIme 大肥鱼 · 手机号直连 DeepSeek

App 里的「大肥鱼」答疑助手，现在**只要用手机号登录一次 DeepSeek，就能直接对话**——
不用 API Key，不用电脑开服务，不用任何中间转发。

## 怎么用

1. 随便进一道题，点中间那颗大肥鱼头像打开对话，或到「我的 → 大肥鱼」。
2. 点「用手机号登录 DeepSeek」，会弹出一个真实的登录页。
3. 输手机号 → 过人机验证 → 填短信验证码。
4. 登录成功后页面自动关掉，回到对话直接问就行。

**如果这个 WebView 里本来就登录着**（比如你之前在这个 App 里登过一次），
点进去会**立刻识别并自动返回**，不会再让你重复登录一次。打开登录页时会先用 App 里
已存的 token 问一次 `/users/current`，失败再带 Cookie / 设备指纹兜底；即使 WebView 的
localStorage 被清过、或页面还没把 token 写回来，也会自动返回，不用手动点。

如果在 App 里用手机号从游客态登录，登录页本身发出的 API 请求会带上新的
`Authorization` / `Cookie` / `x-device-id`；App 直接观察这些请求，登录成功后的
第一个请求就会触发保存和返回，不依赖页面把 token 存进哪个 localStorage 键。

登录态存在 App 私有配置里，重启 App 不用再登。想换账号或退出：
「我的 → 大肥鱼 → 退出登录」。退出会清掉本地登录态。

## 为什么必须手动登录一次

DeepSeek 的登录接口要求人机验证令牌（数美 / hCaptcha），这些令牌只能由真人在
真实浏览器里产生。程序既不能也不该伪造它们——所以这一步做成了「真人登一次，
App 只搬走登录结果」，之后所有对话都由 App 自己完成。

登录成功后，App 从页面里取三样东西：`userToken`、整串 Cookie（含 httpOnly 的
`ds_session_id`）、`deepseek-device-id:chat`。上游只强校验 `Authorization`
（其余头实测可以省略），但**不带 Cookie 的请求正常浏览器不可能发出**——
所以默认都带上：这不是「上游要求」，而是「不带上就像脚本」。

**判据是问上游，不是看 token 变没变。** 一开始拿「localStorage 里的 userToken
值变了」当成功判据，结果在「用户本来就登录着」时永远不成立——token 早就是真的，
不会变，页面停在已登录状态、App 一直等下去。现在改成读到 token 就直接问
`/api/v0/users/current`：有 `mobile_number` 才算真登录（游客 token 也能建会话，
所以只有这一步能区分真假）。顺带把「网络错误」和「上游不认这个 token」分开处理：
网络错误不拉黑 token（网络恢复后它可能还有效），上游明确拒绝才拉黑。

## 它到底做了什么

```
页面 index.html                    原生 Java
──────────────                    ────────────────────────────────
chatRequest()  ──Android.chatApi──▶ MainActivity.chatApi
                                      │ url 是 builtin:// ？
                                      ▼
                                   DeepSeekBridge        会话/回包组装
                                      │
                                      ├─ DeepSeekClient  OpenAI messages ⇄ 单个 prompt
                                      │                  <tool_call> ⇄ tool_calls
                                      └─ DeepSeekWeb     私有 SSE 方言 + PoW
                                            │
                                            ▼
                                      chat.deepseek.com
```

- **`DeepSeekHashV1.java`** —— 上游 PoW 用的 Keccak 变体（只跑 23 轮）。纯 Java 实现，
  多线程暴破，实测约 37 万次/秒；难度 144000 的全窗口 0.4 秒，平均 0.2 秒。
  上游每个挑战只能用一次，所以每次请求都要现取现算。
- **`DeepSeekWeb.java`** —— 建会话、算 PoW、发流式请求，把 SSE 方言解析成
  thinking / content / meta / usage 四类回调。
- **`DeepSeekClient.java`** —— OpenAI 协议层：把 `messages + tools` 拍平成一个 prompt，
  再把模型吐的 `<tool_call>{...}</tool_call>` 解析回标准 `tool_calls`。
  页面侧代码一行没改。
- **`DeepSeekBridge.java`** —— 落点：认 `builtin://deepseek` 这个地址，跑完整轮对话，
  按 OpenAI 的 `choices[0].message` 形状回给页面。
- **`DeepSeekLoginActivity.java`** —— 手机号登录页，只负责把登录结果搬进 App。

页面把接口地址填成 `builtin://deepseek` 就走内置通道；填任何 `http(s)://` 地址
仍然按原来的方式调外部 OpenAI 兼容接口，两种用法并存。

## 两种接入方式二选一

面板顶部只有一个切换按钮，**同一时刻只显示当前那一种配置**，不会把登录按钮和
Key 输入框并排摆出来：

```
┌────────────────────────────────────────┐
│ 当前：手机号登录                        │
│ 点这里换成 API Key ›                    │
└────────────────────────────────────────┘
```

按钮第一行是「现在用什么」，第二行是「点下去会换成什么」。切换只改
`set.agent.mode`（`"login"` / `"key"`），另一套的输入值原样留着，
换回来时不用重新填。

- `mode = "login"`：地址恒为 `builtin://deepseek`，页面上没有 Key 这个概念；
  只显示登录态（含脱敏手机号）和「退出登录」。
- `mode = "key"`：只显示地址 / 模型 / Key 三个输入框和「保存」。
- 老数据没有 `mode` 字段，按地址是不是 `builtin://` 推断，升级后行为不变。

## 想换回 API Key 方式

「我的 → 大肥鱼」里点顶部按钮切到 API Key，填地址与 Key 保存即可。
切回来点同一个按钮，原来的 Key 还在。

## 已知限制

- 上游协议是私有的，随时可能变。变了的话症状是对话报 `40300` / `40301`（PoW 相关）
  或直接解析不出正文——这时需要按新的抓包更新 `DeepSeekWeb`。
- 内置通道**复用上游会话**：同一聊天会话沿用同一个 `chat_session_id` +
  `parent_message_id`，每轮只发「最新一轮」（历史由上游会话链维护），
  所以不会把历史重复灌进去，也不再每问一句就 `create_session`。
  页面上「新会话」会换一条上游会话，不会带着上一段历史回答新问题。
  会话闲置 12 小时后重建；万一上游那边先过期了，第一次复用失败会自动
  重建一次再问，用户看不到这个动作。网络超时/断连**不重建**——沿用原会话重试，
  否则一次网络波动就会把同一段对话拆成两条网页会话。
- 登录态过期（一般是几周）后会报「登录态失效，请重新登录」，重新登一次即可。
- **网络不好时**：上游 30 秒不吐字会被首帧看门狗掐断并自动重试（最多 2 次，
  重试时对话里会出现一条「网络不稳定…正在重试 n/2」的小灰字）。已经流出一部分
  正文后**不会重试**——那会让用户看到重复的答案，宁可报错。
- 等太久时可以点输入框右边的「停止」（忙的时候「发送」会变成它）：它会立刻掐断
  原生连接，不用干等超时。停止不会保存半截回答，**也不会在背后继续重试**——
  用户叫停之后又偷偷重发，等于背着他继续打上游。

## 少发请求：上游会话

**「少发一次」比「发得像人」更防封。** 现在唯一省请求的手段是上游会话复用；
答案本身不再缓存，每次提问都会真实请求上游。只保留两类与回答无关的缓存：
Markdown 渲染结果（纯显示）和 HIF 令牌（见下文）。

### 上游会话（内置通道）

同一聊天会话沿用 `chat_session_id` + `parent_message_id`，每轮只发「最新一轮」，
历史交给上游会话链维护。这样既不会把历史重复灌两遍，也不再每问一句就
`create_session`；会话闲置 12 小时后重建，上游先过期时复用失败会自动重建一次。
会话键取页面上的对话 id：换对话就换链路，新问题不会被上一段历史带偏。

页面上点「新会话」时会**立刻**在上游建一条会话（真实网页自己也会预建会话），第一条消息直接复用；
即使用户只是新开不提问，网页侧也有这条会话——App 与网页一一对应。
预建失败不挡用户：第一条提问时还会按老路再建一次。

切到另一条页面会话时，也会确保网页侧有对应的一条：已有映射直接复用，没有就补建。
删除一条会话时，会调用 `POST /api/v0/chat_session/delete`（body 是
`{"chat_session_ids":["<id>"]}`）把网页侧那条也删掉；「页面会话 → 上游会话」的映射落盘，
重启 App 后仍然能找到对应关系。删除不看当前接入方式：登录态下建的会话，即使后来切到
API Key 模式，删本地时也会把网页侧一起删掉。

会话过期或重建时，旧的上游会话会一并删除，网页版不会留下与 App 对不上的孤儿会话。

### 接入方式与会话隔离

- 每条会话记录建立时的接入方式（网页版 / API Key）；旧版本留下的会话标为「旧会话」，
  第一次发送时绑定当时的方式。
- 中途切换接入方式会自动新开一条会话，旧会话留在原来的通道里。
- 历史列表显示来源标签；另一方式的会话置灰，点它只提示、不切换，也不能发送。
- 设置里的「大肥鱼」面板会写明这条规则。

## 回答的显示

大肥鱼返回的**正文**按 Markdown 渲染：标题、粗体/斜体、行内代码、围栏代码块、
有序/无序列表、引用、分隔线、链接、删除线。**思考链不解析**，原样显示纯文本；
用户自己发的消息也不解析。

- 流式期间保持纯文本（每 40ms 重排一次 Markdown 又慢又闪），答完一次性排版；
  已落盘的回答按消息 id 缓存**渲染结果**（只缓存 HTML，不缓存答案）。
- 先转义 HTML 再套标签，回答里的 `<script>` 不会变成标签；图片不加载远程资源，
  退化成「alt（地址）」文字。
- 链接只允许 http/https，点击交给原生 `openUrl` 打开系统浏览器，不会把 App 页面导航走。
- 轻量实现：不支持表格、嵌套列表和数学公式，代码块不做语法高亮。

## 会话规则与空会话界面

- **没有消息的会话不算会话**：启动、切换会话、新建会话时都会把空会话清掉；
  历史列表只列有消息的会话。当前正在用的那个空会话保留（它就是新对话界面）。
- **至少聊过一句才能新开**：当前会话有任意一条消息（用户、回答、报错都算）才允许点「+」；
  空会话里点「+」只提示「先问一句，再开新会话」，不会叠出第二个空会话。
- **空会话界面**：大肥鱼头像放大、名字「蓝色大肥鱼」和输入框居中，虚线提示在输入框下面；
  发出第一条消息后输入框滑回底部、头像恢复消息里的原大小、虚线提示消失，头部重新显示
  「蓝色大肥鱼」。头部始终是 agent 的名字，对话标题只在历史列表里出现。
- 新建会话时上游也立刻建一条，删除会话时会同步删掉网页侧那条（见「上游会话」），
  App 与网页一一对应。
- 从头部「+」新建时**面板保持打开**，直接切到空会话界面；只有从历史面板删光会话时
  才由删除动作关掉那一层。

## 关于封号（重要）

用网页版登录态跑自动化，本质上是在用你自己的账号做「非官方客户端」的事，
上游风控判定封号是**有可能的**——本项目做的是尽量把流量做得像真人，不能保证不封。
已经做到的：

1. **带全套浏览器指纹**：登录时把 Cookie（含 `HWWAFSESID` / `smidV2` / `ds_session_id`）
   和 `deepseek-device-id:chat` 一起存下来，之后每个请求都带上；另外补了
   `x-client-platform` / `x-client-locale` / `x-client-timezone-offset` /
   `x-client-version`（2.5.0）/ `x-client-bundle-id`（com.deepseek.chat）/
   `x-device-model`（空值）/ `Accept-Language`、Client Hints（`sec-ch-ua-*`）、
   `Sec-Fetch-*`、`Cache-Control: no-cache`、`Pragma: no-cache`。
   UA 里的设备型号与 Android 版本取自真机（Build.MODEL / VERSION.RELEASE），与登录
   WebView 的引擎 UA 使用同一台设备信息；Chrome 版本各自对齐自己的引擎——原生是
   Cronet 155，登录 WebView 是它真实的 Chromium 80。
   语言、时区、Accept-Language 的条目与 q 值都按真实页面实测的形状生成，不写死。
   完成请求的 Origin / Referer（会话路径）/ Accept / body 形状也按实测对齐。
   只带 `Authorization`、不带 Cookie 的请求，正常浏览器**不可能**产生，那是最强的脚本特征。
   **完成请求上的两枚 `x-hif-*` 令牌**也从官方 HIF 接口按 TTL 拉取并缓存，原生就能挂上
   （见「完成请求基线」）。传输层已在 v1.0.9 换成 Cronet（见下面的实测），
   真正补不了的只剩页面资源/埋点流量画像。

### 真实请求基线（2026-10-21）

用登录 WebView（游客态，停在 `/sign_in`）经 CDP 抓了一轮它自己发出的请求，
作为「真实浏览器长什么样」的对照，共 33 个请求（文档 / 脚本 / 样式 / 字体 / XHR），
其中 `/api/v0/client/settings` 五次。可核对的事实：

- API 请求带 `x-client-version: 2.5.0`、`x-client-bundle-id: com.deepseek.chat`、
  `x-device-model:`（空值）、`x-device-id`、`x-client-platform/locale/timezone-offset`；
- `referer` 是当前页面路径（`/sign_in`），不是恒为根路径；
- `accept-encoding: gzip, deflate`；`accept-language` 是
  `zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7`；
- `pragma: no-cache` 确实存在（不是脚本特征）；
- `client/settings` 与 `create_pow_challenge` **没有** `x-hif-leim`，也**没有** `sec-ch-ua-*`
  （这台机器的 WebView 内核是 Chrome 80，Client Hints 是 Chrome 89 才有的）；
- WebView 会给 XHR 自动加 `x-requested-with: com.fan.shuatiku`（真实 Chrome 不会）；
- 页面流量是 33 个请求，内置通道只有 API 请求——这是目前最大的画像差异。

### 完成请求基线（2026-10-21，登录态真实页面）

在系统浏览器登录后驱动真实页面发了一条消息，抓到 `/chat/completion`。与内置通道逐项对照：

| 项 | 真实页面 | 内置通道（改后） |
|---|---|---|
| Referer | `https://chat.deepseek.com/a/chat/s/<session_id>` | 同（首轮挑战仍是根路径，完成请求用会话路径） |
| Origin | 仅 POST 带 | 同（GET 不再带） |
| Accept | `*/*` | 同 |
| body 字段/顺序 | session, parent, model_type=`default`, prompt, ref_file_ids, thinking_enabled, search_enabled, action, preempt=`false` | 同 |
| `x-hif-leim` / `x-hif-dliq` | **完成请求上有**（每次不同） | 同（原生从 HIF 接口拉取并缓存） |
| `x-requested-with` | 浏览器包名（`com.feimi.browser`） | 原生不带；换 WebView 传输会带 App 包名 |
| `x-ds-pow-response` | 有 | 有（本地暴破） |

两枚 `x-hif-*` 不是 JS 现算的指纹，而是**令牌**：网页从
`GET https://hif-leim.deepseek.com/query` 与 `https://hif-dliq.deepseek.com/query`
拉取，返回体 `data.biz_data.value` 就是令牌值，响应头 `x-hif-ttl` 是有效期（600 秒），
网页把值缓存在 localStorage（`hif_leim_cached` / `hif_dliq_cached`），完成请求时挂上。
接口是普通 GET、**不需要 Authorization**，所以原生可以自己拉；必须从手机上拉——
令牌与出口 IP 绑定，绕到电脑上取会失效。

内置通道现在按 TTL 缓存这两枚令牌并只挂在完成请求上。拉取时刻意**不带**聊天 Cookie：
HIF 是另一个 host，带过去既不对，也绝不能把它响应的 `Set-Cookie` 吸收进聊天的 Cookie 罐
（`HWWAFSESID` 同名，会覆盖掉聊天会话的）。

另外：真实页面从取挑战到发出完成请求约 **9.7 秒**（含路由切换与页面准备），原生只有 PoW 暴破的
~0.2 秒；这个区间不是纯求解时间，不能直接当「太快就是脚本」的证据。

改后真机复测：完成请求 **200（带 HIF 版 4.6s）**，日志 `hif leim=73,dliq=73,attached=true`；
中途停止 2.5s 内结束且无重试。

已按这个基线改掉的：三枚缺失的客户端头、Accept-Language 的条目形状、
完成请求的 Origin / Referer / Accept / body 形状，以及两枚 HIF 令牌的原生拉取与缓存。
仍不一致、且不是补头能解决的：`x-requested-with`、页面流量画像，以及下面实测出来的传输层差异。

### TLS / HTTP2 实测（2026-10-21）

用只读 ClientHello 的 CONNECT 代理量了一次（不解密、不抓正文），特意只走
`api.github.com`（App 的更新检查）和浏览器自身流量，**没有让任何 DeepSeek 账号请求绕道**：

| 链路 | JA3 | ALPN | 备注 |
|---|---|---|---|
| 原生（OkHttp / Conscrypt） | `9b02ebd3a43b62d825e1ac605b621dc8` | **仅 `http/1.1`** | 扩展里没有 GREASE / SCT / 证书压缩 |
| WebView（Chromium 80） | `554719594ba90b02ae410c297c6e50ad` | `h2, http/1.1` | 带 GREASE、SCT、证书压缩等扩展 |

JA3 不含 SNI，换主机不影响结论：v1.0.8 的原生这条路连 HTTP/2 都不协商，JA3 也和浏览器不同。
这不是补请求头能修的，只能换传输栈（同源 WebView，或 Cronet 这类 Chromium 网络栈）。

### v1.0.9：Cronet 实测（2026-10-22）

原生 API / HIF 改走 `org.chromium.net:cronet-bundled:500.1.0`（native 155.0.8059.16，
statically linked，QUIC 关闭、H2 打开）。用 Cronet 请求指纹体检页，真机读数：

| 链路 | JA3 | ALPN | HTTP/2（Akamai） |
|---|---|---|---|
| 原生（Cronet 155） | `090e18a94158a024691e72e3b53ae59f` | **h2** | `1:65536;2:0;4:6291456;6:262144\|15663105\|0\|m,a,s,p` |
| 登录 WebView（Chromium 80） | `554719594ba90b02ae410c297c6e50ad` | h2, http/1.1 | Chrome 80 自己的 |

JA4 为 `t13d1516h2_8daaf6152771_806a8c22fdea`。UA 与 Client Hints 同步改成 Chrome 155；
登录 WebView 的 UA 改为从 `WebSettings.getDefaultUserAgent()` 读出的引擎真实版本
（Chrome/80.0.3987.132），不再用 Chrome/120 覆盖。
两条链路各自自洽；登录 WebView 的引擎版本受设备限制，仍是 80。
2. **Cookie 罐**：每个响应都吸收 `Set-Cookie`（同名替换、`Max-Age=0` / `Expires=1970` 删除），
   轮换后的 WAF 与会话 Cookie 立刻用于下一次请求并落盘。以前只看响应体、不看响应头，
   一直攥着登录那一刻的 Cookie 用到失效——那是「cookie 时间线和会话时间线对不上」。
3. **不再每问一句就新建会话**：以前每轮都 `create_session`（真人不会这么干），
   现在按会话复用，而且复用的是**页面上某一个对话**对应的那条上游会话；换对话
   才换链路。闲置 12 小时才重建，过期了也会自动重建一次。实测 3 轮对话的
   `create_session` 次数从 3 降到 1。
4. **出站节流 + 抖动**：两个上游请求之间至少 1.2 秒，再叠加 0~0.9 秒随机
   ——固定 1.2 秒一次的节奏本身就是机器特征；任意 60 秒内不超过 12 发。
   窗口与「上次发送时刻」都落盘，杀掉 App 重开不会得到一台全新的限速器。
   等待时间较长时界面会提示「为了账号安全，提问间隔会放慢一点」。
5. **被警告就停手，但不替你封停**：上游回 `40300` / 429 / 含「频繁 / 配额 /
   人机 / captcha / 异常流量」等，代表这一路被盯上了——这一次直接报错、**不重试**。
   不设定时熔断，也不累计「被警告几次」：要不要接着问由你自己判断。
6. **拦截页单独识别**：上游回 HTML / 人机校验页（不是 JSON）时不是「一次网络错误」，
   而是明确的「别问了」——按风险处理，**不重试**（重试就是继续撞验证码）。
   反过来，`40301`（挑战一次性）**会重试一次**：重试会重新取一个新挑战，它真的能好。
7. **每条提问都真实请求**：答案不缓存，命中旧答案的情况不存在；风险判定只决定
   这一发要不要重试。
8. **一次只跑一轮对话**：同一账号不会同时出现两条并行的上游链路——真人不会
   边等答案边开另一段对话。
9. **「停止」就是停止**：掐断连接后不会再按「网络抖动」重试。以前用户点了停止，
   页面虽然收手，App 还可能背后再发两发——那是纯粹的浪费与撞风控。

**仍然要注意**：不要用这个通道批量刷题、不要长时间挂着自动重试、不要在多个设备上
同时用同一个账号。真被限流时上游会先回错误码（`40300` 等），这时应当停手，别继续重试。

## 回归

```cmd
tools\check.cmd
```

里面包含两个离线单测：`DeepSeekHashTest`（10 条 Keccak 向量 + 多线程求解）
和 `DeepSeekClientTest`（prompt 拍平 / tool_call 解析 / SSE 状态机）。

打真实上游的联调测试不进回归（依赖网络和真实凭证），手工跑：

```cmd
java -cp "build\test-classes;build\test-libs\json-20240303.jar" ^
     com.fan.shuatiku.DeepSeekLiveTest D:\desktop\web2api-mini\.env
```
