# GIme MCP 接口

App 内置一个只监听 `127.0.0.1:8765` 的 MCP 服务（Streamable HTTP，JSON-RPC 2.0），
让 agent 直接读写 App 里的题库。服务默认就是开着的：应用进程在跑它就在跑，
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
| `list_questions` | 提取单题/多题与选项 | `bank`、`ids`、`offset`、`limit`、`type`、`query` |
| `write_explanations` | 写或改解析（explain）与主观题参考答案（ref） | `bank`、`items[{id, explain?, ref?}]`、`mode=replace\|append` |

题库用 id 或显示名指定；重名时会报错让你改用 id。
`import_bank` 的文本格式与 App 内导入一致（参见 App 里的「格式说明」举例）；
`dryRun:true` 只返回识别统计与前三题预览，不落盘。
`write_explanations` 的 `items` 可以给一条也可以给多条；写盘失败时内存不会改动。

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
- 工具能改题库文件（导入、写解析），但没有删除题库的工具；删除仍在 App 界面里做。
