# GIme 大肥鱼 · 手机号直连 DeepSeek

App 里的「大肥鱼」答疑助手，现在**只要用手机号登录一次 DeepSeek，就能直接对话**——
不用 API Key，不用电脑开服务，不用任何中间转发。

## 怎么用

1. 随便进一道题，点中间那颗大肥鱼头像打开对话，或到「我的 → 大肥鱼」。
2. 点「用手机号登录 DeepSeek」，会弹出一个真实的登录页。
3. 输手机号 → 过人机验证 → 填短信验证码。
4. 登录成功后页面自动关掉，回到对话直接问就行。

**如果这个 WebView 里本来就登录着**（比如你之前在这个 App 里登过一次），
点进去会**立刻识别并自动返回**，不会再让你重复登录一次。

登录态存在 App 私有配置里，重启 App 不用再登。想换账号或退出：
「我的 → 大肥鱼 → 退出登录」。

## 为什么必须手动登录一次

DeepSeek 的登录接口要求人机验证令牌（数美 / hCaptcha），这些令牌只能由真人在
真实浏览器里产生。程序既不能也不该伪造它们——所以这一步做成了「真人登一次，
App 只搬走登录结果」，之后所有对话都由 App 自己完成。

登录成功后，App 只从页面里取 `userToken` 一个值。实测确认上游只强校验
`Authorization` 头，Cookie、`x-hif-leim`、`x-device-id`、`x-client-*`
全部可以省略，所以不需要在手机上保存整套浏览器指纹。

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
  会话闲置 1 小时后自动重建。
- 登录态过期（一般是几周）后会报「登录态失效，请重新登录」，重新登一次即可。
- **网络不好时**：上游 30 秒不吐字会被首帧看门狗掐断并自动重试（最多 2 次，
  重试时对话里会出现一条「网络不稳定…正在重试 n/2」的小灰字）。已经流出一部分
  正文后**不会重试**——那会让用户看到重复的答案，宁可报错。
- 等太久时可以点输入框右边的「停止」（忙的时候「发送」会变成它）：它会立刻掐断
  原生连接，不用干等超时。停止不会保存半截回答。

## 关于封号（重要）

用网页版登录态跑自动化，本质上是在用你自己的账号做「非官方客户端」的事，
上游风控判定封号是**有可能的**——本项目做的是尽量把流量做得像真人，不能保证不封。
已经做到的：

1. **带全套浏览器指纹**：登录时把 Cookie（含 `HWWAFSESID` / `smidV2` / `ds_session_id`）
   和 `deepseek-device-id:chat` 一起存下来，之后每个请求都带上；另外补了
   `x-client-platform` / `x-client-locale` / `x-client-timezone-offset` / `Accept-Language`。
   只带 `Authorization`、不带 Cookie 的请求，正常浏览器**不可能**产生，那是最强的脚本特征。
2. **不再每问一句就新建会话**：以前每轮都 `create_session`（真人不会这么干），
   现在按会话复用。实测 3 轮对话的 `create_session` 次数从 3 降到 1。
3. **出站节流**：两个上游请求之间至少 1.2 秒，任意 60 秒内不超过 12 发。
   工具调用循环以前是连着打，现在会被压回人类节奏；等待时间较长时界面会提示
   「为了账号安全，提问间隔会放慢一点」。

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
