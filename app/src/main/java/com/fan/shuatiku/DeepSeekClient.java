package com.fan.shuatiku;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI 协议层 —— 把「OpenAI messages + tools」翻译成 DeepSeek 能吃的单个 prompt，
 * 再把 DeepSeek 的回答翻译回 OpenAI 的 {@code choices[0].message}。
 *
 * <p>这是 web2api-mini 三层结构的第 2、3 层搬进 Android 的结果：
 * <ul>
 *   <li>{@link #buildPrompt} —— messages 拍平成纯文本（网页端只吃一个字符串）；</li>
 *   <li>{@link #parseAssistant} —— 把模型吐出的 {@code <tool_call>{...}</tool_call>}
 *       解析回标准 OpenAI {@code tool_calls}。</li>
 * </ul>
 *
 * <p>页面侧完全不用改：它照旧发 OpenAI 请求、照旧读 {@code choices[0].message}，
 * 只是不再需要 API Key 和外部地址。
 */
public final class DeepSeekClient {

    /** 工具调用规则块标记，注入 prompt 头部。 */
    private static final String TOOL_MARKER = "# 工具调用规则";

    private DeepSeekClient() {}

    /**
     * 把 tools 定义渲染成「工具调用规则」文本，注入 prompt 开头。
     *
     * <p>网页端没有原生 function 通道，只能用纯文本约定：要调用工具时只输出一个
     * {@code <tool_call>JSON</tool_call>} 块，之后由 {@link #parseAssistant} 解析回来。
     */
    static String renderToolInstructions(JSONArray tools) {
        if (tools == null || tools.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        b.append(TOOL_MARKER).append('\n');
        b.append("你可以通过调用外部工具来帮助用户。可用工具（JSON Schema）：\n");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) fn = t;
            b.append("- ").append(fn.optString("name", "")).append(": ")
                    .append(fn.optString("description", "")).append(" 参数: ")
                    .append(fn.optJSONObject("parameters") == null
                            ? "{}" : fn.optJSONObject("parameters").toString())
                    .append('\n');
        }
        b.append("调用工具的规则：\n");
        b.append("1) 需要调用工具时，只输出下面这个块，一次只调用一个，除此之外不要输出任何别的内容：\n");
        b.append("<tool_call>\n");
        b.append("{\"name\": \"<工具名>\", \"arguments\": { ... }}\n");
        b.append("</tool_call>\n");
        b.append("2) 用户会以「工具返回: (<工具名>) ...」的形式把执行结果反馈给你，拿到结果后再决定下一步。\n");
        b.append("3) 不需要工具时，直接正常回答，绝对不要输出 <tool_call>。\n");
        return b.toString();
    }

    /** 把 content（可能是字符串或 parts 数组）归一成纯文本。 */
    static String extractText(Object content) {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                if ("text".equals(p.optString("type")) && p.has("text")) {
                    if (b.length() > 0) b.append(' ');
                    b.append(p.optString("text", ""));
                }
            }
            return b.toString();
        }
        return String.valueOf(content);
    }

    /**
     * messages 拍平成单个 prompt。
     *
     * <p>规则与 web2api-mini 的 {@code build_prompt} 一致：
     * 每条消息渲染成「角色前缀 + 文本」，多条之间空行分隔；内容为空的非 user 消息丢弃。
     */
    static String buildPrompt(JSONArray messages, JSONArray tools) {
        List<String> parts = new ArrayList<String>();
        String toolBlock = renderToolInstructions(tools);
        if (!toolBlock.isEmpty()) parts.add(toolBlock);

        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "user");
            String text = extractText(m.opt("content"));

            // assistant 带 tool_calls：让模型看到自己上一轮调了什么
            JSONArray tcs = m.optJSONArray("tool_calls");
            if ("assistant".equals(role) && tcs != null && tcs.length() > 0) {
                JSONArray calls = new JSONArray();
                for (int k = 0; k < tcs.length(); k++) {
                    JSONObject tc = tcs.optJSONObject(k);
                    if (tc == null) continue;
                    JSONObject fn = tc.optJSONObject("function");
                    JSONObject one = new JSONObject();
                    try {
                        one.put("name", fn == null ? "" : fn.optString("name", ""));
                        one.put("arguments", fn == null ? "" : fn.optString("arguments", ""));
                    } catch (Exception ignored) { }
                    calls.put(one);
                }
                String body = "调用工具: " + calls.toString();
                parts.add("Assistant: " + (text.isEmpty() ? body : text + "\n" + body));
                continue;
            }

            // role=tool：工具执行结果回灌
            if ("tool".equals(role)) {
                String label = m.optString("name", "");
                if (label.isEmpty()) label = m.optString("tool_call_id", "tool");
                parts.add("工具返回: (" + label + ") " + text);
                continue;
            }

            if (text.isEmpty() && !"user".equals(role)) continue;
            String prefix;
            if ("system".equals(role)) prefix = "System: ";
            else if ("assistant".equals(role)) prefix = "Assistant: ";
            else prefix = "User: ";
            parts.add(prefix + text);
        }

        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append("\n\n");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    /** 解析结果：可见正文 + OpenAI 风格 tool_calls。 */
    static final class Parsed {
        final String text;
        final JSONArray toolCalls;
        Parsed(String text, JSONArray toolCalls) {
            this.text = text;
            this.toolCalls = toolCalls;
        }
    }

    /**
     * 从完整输出里摘出 {@code <tool_call>} 块，返回 (正文, tool_calls)。
     *
     * <p>容忍三类常见走样：裸 JSON、带 ```json 围栏、arguments 被写成字符串。
     */
    static Parsed parseAssistant(String text) {
        JSONArray calls = new JSONArray();
        if (text == null || text.isEmpty()) return new Parsed("", calls);

        StringBuilder visible = new StringBuilder();
        int pos = 0;
        while (true) {
            int open = text.indexOf("<tool_call>", pos);
            if (open < 0) {
                visible.append(text, pos, text.length());
                break;
            }
            int close = text.indexOf("</tool_call>", open + 11);
            if (close < 0) {
                visible.append(text, pos, text.length());     // 未闭合：原样当正文，不吞用户内容
                break;
            }
            visible.append(text, pos, open);
            String inner = text.substring(open + 11, close);
            JSONObject one = parseToolPayload(inner);
            if (one != null) calls.put(one);
            pos = close + 12;
        }

        String out = visible.toString().trim();
        return new Parsed(out, calls);
    }

    /** 把标签内文本解析成 OpenAI 的 tool_call 对象；不是合法调用返回 null。 */
    private static JSONObject parseToolPayload(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            // 去掉 ```json ... ``` 围栏
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        JSONObject obj;
        try {
            obj = new JSONObject(t);
        } catch (Exception e) {
            return null;
        }
        JSONObject fn = obj.optJSONObject("function");
        if (fn == null) fn = obj;

        String name = fn.optString("name", "");
        if (name.isEmpty()) name = fn.optString("tool", "");
        if (name.isEmpty()) name = obj.optString("name", "");
        if (name.isEmpty()) return null;

        Object args = fn.has("arguments") ? fn.opt("arguments")
                : fn.has("parameters") ? fn.opt("parameters") : obj.opt("arguments");
        if (args == null || args == JSONObject.NULL) args = new JSONObject();

        String argsJson;
        if (args instanceof String) {
            String s = (String) args;
            try {
                argsJson = new JSONObject(s).toString();       // 字符串里是 JSON：解一层
            } catch (Exception e) {
                argsJson = s;                                  // 解不动原样保留
            }
        } else {
            argsJson = String.valueOf(args);
        }

        JSONObject call = new JSONObject();
        try {
            call.put("id", "call_" + Long.toHexString(System.nanoTime()));
            call.put("type", "function");
            JSONObject f = new JSONObject();
            f.put("name", name);
            f.put("arguments", argsJson);
            call.put("function", f);
        } catch (Exception e) {
            return null;
        }
        return call;
    }
}
