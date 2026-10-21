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

    /**
     * 只截出「最新一轮」的消息，供复用上游会话时用。
     *
     * <p>复用上游会话时历史由上游维护，若再把整段历史拍平发过去，模型会看到两遍。
     * 这里保留：所有 system 消息（含本轮工具规则）+ 最后一条 user 及其之后的内容
     * （assistant 的工具调用轮次 + tool 结果）。
     *
     * <p>如果没有 user 消息（异常情况），退回整段，宁可重复也不要丢上下文。
     */
    static JSONArray latestTurn(JSONArray messages) {
        if (messages == null || messages.length() == 0) return new JSONArray();
        int lastUser = -1;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) { lastUser = i; break; }
        }
        if (lastUser < 0) return messages;
        JSONArray out = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            if ("system".equals(role)) { out.put(m); continue; }
            if (i >= lastUser) out.put(m);
        }
        return out;
    }

    /**
     * 最后一条 user 消息的纯文本 = 本轮「问的是什么」。
     *
     * <p>缓存键要用它，而不是整段拍平后的 prompt：后者带着历史，多聊一句就不命中。
     * 没有 user 消息时返回空串（调用方据此跳过缓存）。
     */
    static String lastUserText(JSONArray messages) {
        if (messages == null) return "";
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) {
                return extractText(m.opt("content"));
            }
        }
        return "";
    }

    /** 题目上下文在 system 消息里的标记：页面用 {@code chatCtxMsg} 拼出来。 */
    static final String CTX_MARK = "当前题目上下文（JSON）：";

    /**
     * 挖出「当前题目上下文」那个 JSON 对象（没有就是 null）。
     *
     * <p>页面把题目作为**单独一条 system 消息**发过来。这里只认标记之后那一截 JSON；
     * 解析不动就当没有上下文——指纹、作答状态都靠它，坏了只该退化成「不命中」，
     * 不该让问答整个失败。
     */
    static JSONObject questionContext(JSONArray messages) {
        if (messages == null) return null;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"system".equals(m.optString("role"))) continue;
            String text = extractText(m.opt("content"));
            int at = text.indexOf(CTX_MARK);
            if (at < 0) continue;
            String json = text.substring(at + CTX_MARK.length()).trim();
            try {
                return new JSONObject(json);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 题目指纹：从题目上下文里按题干/选项/答案重组出一段稳定字符串。
     *
     * <p>这样同一道题每次的指纹都一样（缓存能命中），换了题目指纹就变
     * （不会把 A 题的答案套到 B 题上）。没有上下文（闲聊）时返回空串，
     * 此时缓存键只剩提问本身。
     */
    static String questionFingerprint(JSONArray messages) {
        JSONObject ctx = questionContext(messages);
        if (ctx == null) return "";
        // 只取与「这道题是什么」有关的字段，顺序固定，保证指纹稳定
        StringBuilder b = new StringBuilder();
        b.append(ctx.optString("id", "")).append('\u0001');
        b.append(ctx.optString("stem", "")).append('\u0001');
        JSONArray opts = ctx.optJSONArray("options");
        if (opts != null) {
            for (int k = 0; k < opts.length(); k++) {
                JSONObject o = opts.optJSONObject(k);
                if (o == null) continue;
                b.append(o.optString("key", "")).append('=')
                 .append(o.optString("text", "")).append('\u0002');
            }
        }
        JSONArray ans = ctx.optJSONArray("answer");
        if (ans != null) {
            for (int k = 0; k < ans.length(); k++) {
                b.append(String.valueOf(ans.opt(k))).append('\u0002');
            }
        }
        return b.toString();
    }

    /**
     * 本轮是不是「用户刚提了一个新问题」——决定这一轮能不能查/写答案缓存。
     *
     * <p>为什么需要这道闸：一轮工具调用会连续发好几次请求，中间的 prompt 里带着
     * 「工具返回」，而最后一条 user 消息**没变**。若不看最后一条消息的角色就查缓存，
     * 第二发可能直接命中上一次的最终回答，把刚拿到的工具结果整段吞掉——表现就是
     * 「大肥鱼查了题库，答的还是旧话」。
     *
     * <p>判据只看最后一条消息是不是 user。工具轮次、assistant 收尾轮都不算，
     * 因此它们既不读缓存也不写缓存。
     */
    static boolean isFreshAsk(JSONArray messages) {
        if (messages == null || messages.length() == 0) return false;
        JSONObject last = messages.optJSONObject(messages.length() - 1);
        if (last == null) return false;
        if (!"user".equals(last.optString("role"))) return false;
        return !extractText(last.opt("content")).trim().isEmpty();
    }

    /**
     * 作答状态：这道题「被答成什么样」——选了哪几个选项、判没判分、什么模式。
     *
     * <p>它是缓存键的第三块原料。少了它，用户换个选项再问同一句话会拿到上一回的答案
     * （那答案里正说着「你选的 B」）。选项先大写归一、再排序，这样「B、A」和「A、B」
     * 是同一个状态，顺序反过来也照样命中。
     */
    static String answerState(JSONArray messages) {
        JSONObject ctx = questionContext(messages);
        if (ctx == null) return "";
        StringBuilder b = new StringBuilder();
        JSONArray picks = ctx.optJSONArray("userPick");
        if (picks != null && picks.length() > 0) {
            java.util.List<String> keys = new java.util.ArrayList<String>();
            for (int i = 0; i < picks.length(); i++) {
                String k = String.valueOf(picks.opt(i)).trim();
                if (!k.isEmpty()) keys.add(k.toUpperCase(java.util.Locale.US));
            }
            java.util.Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) b.append(',');
                b.append(keys.get(i));
            }
        }
        b.append('|').append(ctx.optBoolean("graded", false) ? '1' : '0');
        b.append('|').append(ctx.optString("mode", ""));
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
