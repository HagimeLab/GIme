# GIme MCP 接口

App 内置一个只监听 `127.0.0.1:8765` 的 MCP 服务（Streamable HTTP，JSON-RPC 2.0），
让 agent 直接读写 App 里的题库与方案。服务默认就是开着的：应用进程在跑它就在跑，
应用关掉（Activity 销毁）才停；没有开关，也不需要配置面板。

## 口令

服务仍要口令：每个请求带 `Authorization: Bearer <口令>`。
口令在应用私有配置里，第一次启动时随机生成后固定下来。
页面不提供查看/重置入口，要取口令看应用启动时打的那行日志：

```
adb logcat -s GImeMCP
# I GImeMCP: http://127.0.0.1:8765/mcp token=<24 位十六进制>
```

换手机或怀疑泄露时：清掉应用的
`/data/data/com.fan.shuatiku/shared_prefs/mcp.xml`（或重装），下次启动会换新的。

服务只绑回环地址，同一台手机上的 agent 可直接连；电脑上的 agent 先做端口转发：

```
adb forward tcp:8765 tcp:8765
```

## 工具

| 工具 | 作用 | 关键参数 |
|---|---|---|
| `list_banks` | 列出当前题库 | 无 |
| `import_bank` | 把一段文本按 App 导入格式解析成新题库 | `text`（必填）、`name`、`dryRun` |
| `list_questions` | 提取单题/多题与选项 | `bank`、`ids`、`offset`、`limit`、`type`、`query`、`wrong`、`compact` |
| `write_explanations` | 写或改解析（explain）与主观题参考答案（ref） | `bank`、`items[{id, explain?, ref?}]`、`mode=replace\|append` |
| `write_answers` | 改客观题（单选/多选/判断）的正确答案 | `bank`、`items[{id, answer:["A"]}]` |
| `create_plan` | 建立执行方案（可只给目标+天数，也可给逐日内容） | `goal`（必填）、`totalDays`、`title`、`days`、`notify`、`note` |
| `update_plan` | 改方案：暂停/恢复、改名、开关提醒、加删项、勾选、整体重排 | `planId`、`action`、`date`、`text`、`done`、`title`、`days`、`notify`、`note` |
| `delete_plan` | 删除方案及其执行记录（不可恢复） | `planId` |
| `list_plans` | 列出方案进度与今天要做的项 | 无 |
| `notify_phone` | 给手机发一条通知（App 运行时立即弹出） | `title`、`body`（必填） |

题库用 id 或显示名指定；重名时会报错让你改用 id。
`import_bank` 的文本格式与 App 内导入一致（参见 App 里的「格式说明」举例）；
`dryRun:true` 只返回识别统计与前三题预览，不落盘。
`write_explanations` / `write_answers` 的 `items` 可以给一条也可以给多条；
先整体校验再写盘，写盘失败时内存不会改动。
`list_questions` 的 `wrong:true` 只看错题本；`compact:true` 只回题目 id 列表
（不带题干/选项，建计划时用，避免重复读正文烧 token）。
`write_answers` 只接受客观题，选项字母必须存在；单选/判断只能一个答案，
主观题请用 `write_explanations` 改参考答案。写操作只在用户明确要求时调用。

方案工具与页面共用同一份 `plans.json` 与同一个 `rev`：
`action` 枚举就是 `PlanStore.ACTIONS`（pause / resume / rename / remind_on / remind_off /
add_item / remove_item / toggle_item / set_days），传别的值会被拒绝而不是当成没发生。
`create_plan` / `update_plan` 的 `notify` 默认分别是 true / false；
`notify_phone` 在没有通知权限时返回 `posted:false`，工具本身不报错。
MCP 写操作遇到 rev 冲突会自动重读一次再重试，仍冲突才把错误交回调用方。

## 直接走 HTTP

```bash
curl -s http://127.0.0.1:8765/mcp \
  -H 'Authorization: Bearer <口令>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

curl -s http://127.0.0.1:8765/mcp \
  -H 'Authorization: Bearer <口令>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"list_questions",
                 "arguments":{"bank":"<题库 id 或名称>","limit":5}}}'
```

## stdio 客户端（Qoder / Claude 等）

用仓库里的 `tools/gime-mcp.mjs` 把 stdio 转给 App：

```json
"gime": {
  "command": "node",
  "args": ["<repo>/tools/gime-mcp.mjs"],
  "env": {
    "GIME_TOKEN": "<App 里的口令>",
    "ADB": "<可选，adb 可执行文件路径>"
  }
}
```

给了 `ADB` 时脚本会先执行 `adb forward tcp:8765 tcp:8765`。
客户端跑 stdio，工具定义与执行都在 App 内，桥不维护第二份 schema。

## 安全边界

- 只绑 `127.0.0.1`，不做局域网监听。
- 每个请求都要口令；口令只存在 App 私有配置里，日志里那一行是唯一的取用方式
  （Android 上别的应用读不到别的应用的日志，拿不到 adb 也看不到）。
- 工具能改题库文件（导入、写解析），但没有删除题库的工具；删除题库仍在 App 界面里做。
- 方案可以 `delete_plan`（方案要求如此），但它只删 `plans.json` 里的那一份，
  碰不到题库、进度或错题本。

## P3 · 双版本：2025 版式 + 2026-07-28 无状态

服务端按**每个请求**是否带 `_meta.protocolVersion` 分流，老客户端不需要改：

| 请求形状 | 走的版式 |
|---|---|
| 无 `_meta` | 2025（`initialize` / `ping` / 有状态会话兼容） |
| `_meta.protocolVersion = "2026-07-28"` | 新版无状态；版本不匹配返回 `-32022` 与 `supported` |

新版要求：

- 不再握手：不接受 `initialize` / `ping`；先调 `server/discover` 拿版本、能力与身份。
- POST 必须带 `Mcp-Method`；`tools/call` 还必须带 `Mcp-Name`（缺/不符回 HTTP 400）。
- 所有成功结果带 `resultType`（`complete` 或 `input_required`）；list 类还带 `ttlMs` / `cacheScope`。
- 需要补充信息时返回 `resultType:"input_required"` + `inputRequests` + `requestState`；
  客户端带 `inputResponses` 与同一 `requestState` 重试原请求（题库重名选 id 已走这条路）。
- 日志级别由每请求 `_meta.logLevel` 表达，不再有 `logging/setLevel`。

过渡口径：新版与老版并存 **12 个月**（自 2026-10-31 起）；老 `tools/gime-mcp.mjs` 默认仍走 2025。
要让 stdio 桥走新版，在 mcpServers.env 里加 `"GIME_PROTOCOL": "2026-07-28"`；
桥会自动补 `_meta`、`Mcp-Method` 与 `Mcp-Name`。
