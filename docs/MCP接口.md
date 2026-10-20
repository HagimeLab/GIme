# GIme MCP 接口

App 内置一个只监听 `127.0.0.1:8765` 的 MCP 服务（Streamable HTTP，JSON-RPC 2.0），
让 agent 直接读写 App 里的题库。服务默认关闭，在「我的 → MCP 接口」里打开。

## 打开与口令

1. 「我的 → MCP 接口」→「开启 MCP 服务」。
2. 面板里会显示：
   - 地址：`http://127.0.0.1:8765/mcp`
   - 口令：一串随机十六进制；请求头带 `Authorization: Bearer <口令>`
3. 「重新生成口令」会让旧口令立即失效；换 agent 或怀疑泄露时用它。

服务只绑回环地址，同一台手机上的 agent 可直接连；电脑上的 agent 先做端口转发：

```
adb forward tcp:8765 tcp:8765
```

App 被杀掉服务就停了；把它留在最近任务里即可。

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
- 每个请求都要口令；口令只存在 App 私有配置里，页面显示是为了让你复制。
- 工具能改题库文件（导入、写解析），但没有删除题库的工具；删除仍在 App 界面里做。
