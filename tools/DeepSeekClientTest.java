package com.fan.shuatiku;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * DeepSeekClient 的回归：拍平 prompt、解析 tool_call、以及 SSE 方言状态机。
 *
 * <p>这里用**真实抓到的上游帧**当输入（见 docs 里的协议实测记录），
 * 而不是自己编的样例——编的样例只会让测试和实现一起错。
 */
public class DeepSeekClientTest {

    private static int pass, fail;

    private static void eq(String name, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
            System.out.println("         期望: " + want);
            System.out.println("         实际: " + got);
        }
    }

    private static void ok(String name, boolean cond) {
        eq(name, true, cond);
    }

    /** 带失败详情：断言不成立时把实际值打出来，省得只知道「挂了」。 */
    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fail++;
        System.out.println("  [FAIL] " + name + " — " + detail);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== DeepSeekClient（OpenAI 协议层）===");

        // ── 拍平 prompt ──
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", "你是助手"));
        msgs.put(new JSONObject().put("role", "user").put("content", "这题为什么选 A？"));
        String p = DeepSeekClient.buildPrompt(msgs, null);
        ok("system 前缀", p.startsWith("System: 你是助手"));
        ok("user 前缀", p.contains("User: 这题为什么选 A？"));
        ok("多消息空行分隔", p.contains("\n\n"));

        // 空内容的非 user 消息被丢弃，user 保留
        JSONArray m2 = new JSONArray();
        m2.put(new JSONObject().put("role", "assistant").put("content", ""));
        m2.put(new JSONObject().put("role", "user").put("content", ""));
        String p2 = DeepSeekClient.buildPrompt(m2, null);
        ok("空的 assistant 被丢弃", !p2.contains("Assistant:"));
        ok("空的 user 保留", p2.contains("User:"));

        // tool 结果回灌
        JSONArray m3 = new JSONArray();
        m3.put(new JSONObject().put("role", "tool").put("name", "list_questions")
                .put("content", "{\"total\":3}"));
        ok("工具结果渲染", DeepSeekClient.buildPrompt(m3, null)
                .contains("工具返回: (list_questions)"));

        // assistant 带 tool_calls 的历史
        JSONArray m4 = new JSONArray();
        JSONArray tcs = new JSONArray();
        tcs.put(new JSONObject().put("function",
                new JSONObject().put("name", "list_questions").put("arguments", "{}")));
        m4.put(new JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", tcs));
        ok("历史工具调用渲染", DeepSeekClient.buildPrompt(m4, null).contains("调用工具:"));

        // 工具规则块注入
        JSONArray tools = new JSONArray();
        tools.put(new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", "write_explanations")
                        .put("description", "写解析")
                        .put("parameters", new JSONObject().put("type", "object"))));
        String withTools = DeepSeekClient.buildPrompt(msgs, tools);
        ok("工具规则块标记", withTools.startsWith("# 工具调用规则"));
        ok("工具名进入规则", withTools.contains("write_explanations"));
        ok("规则含 tool_call 示例", withTools.contains("<tool_call>"));

        // ── tool_call 解析 ──
        DeepSeekClient.Parsed r1 = DeepSeekClient.parseAssistant(
                "我先查一下。\n<tool_call>{\"name\": \"list_questions\", "
                        + "\"arguments\": {\"bank\": \"nb1\"}}</tool_call>");
        eq("正文摘掉标签", "我先查一下。", r1.text);
        eq("解析出一个调用", 1, r1.toolCalls.length());
        JSONObject c1 = r1.toolCalls.getJSONObject(0);
        eq("调用名", "list_questions", c1.getJSONObject("function").getString("name"));
        ok("arguments 是字符串", c1.getJSONObject("function").get("arguments") instanceof String);
        ok("type=function", "function".equals(c1.getString("type")));

        // 带 ```json 围栏
        DeepSeekClient.Parsed r2 = DeepSeekClient.parseAssistant(
                "<tool_call>```json\n{\"name\":\"a\",\"arguments\":{}}\n```</tool_call>");
        eq("围栏也能解析", 1, r2.toolCalls.length());

        // arguments 是字符串（模型常这么走样）
        DeepSeekClient.Parsed r3 = DeepSeekClient.parseAssistant(
                "<tool_call>{\"name\":\"b\",\"arguments\":\"{\\\"x\\\":1}\"}</tool_call>");
        eq("字符串 arguments 再解一层", "{\"x\":1}",
                r3.toolCalls.getJSONObject(0).getJSONObject("function").getString("arguments"));

        // 无工具调用时原样返回
        DeepSeekClient.Parsed r4 = DeepSeekClient.parseAssistant("直接回答，没有工具。");
        eq("无调用正文不变", "直接回答，没有工具。", r4.text);
        eq("无调用返回空数组", 0, r4.toolCalls.length());

        // 未闭合标签不能吞掉用户内容
        DeepSeekClient.Parsed r5 = DeepSeekClient.parseAssistant("前缀<tool_call>{\"name\":");
        ok("未闭合标签保留正文", r5.text.contains("<tool_call>"));

        // ── SSE 方言状态机（真实帧）──
        System.out.println("\n=== SSE 方言状态机（真实上游帧）===");
        String sse =
                "event: ready\n"
                + "data: {\"request_message_id\":1,\"response_message_id\":2,\"model_type\":\"default\"}\n"
                + "\n"
                + "data: {\"v\":{\"response\":{\"message_id\":2,\"status\":\"WIP\","
                + "\"fragments\":[{\"id\":1,\"type\":\"THINK\",\"content\":\"思考中\"},"
                + "{\"id\":2,\"type\":\"RESPONSE\",\"content\":\"代\"}]}}}\n"
                + "\n"
                + "data: {\"p\":\"response/fragments/-1/content\",\"o\":\"APPEND\",\"v\":\"可用\"}\n"
                + "\n"
                + "data: {\"p\":\"response\",\"o\":\"BATCH\",\"v\":["
                + "{\"p\":\"accumulated_token_usage\",\"v\":43},"
                + "{\"p\":\"quasi_status\",\"v\":\"FINISHED\"}]}\n"
                + "\n"
                + "data: {\"p\":\"response/status\",\"o\":\"SET\",\"v\":\"FINISHED\"}\n"
                + "\n"
                + "event: close\n"
                + "data: {\"click_behavior\":\"none\"}\n"
                + "\n";

        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final long[] meta = {-1};
        final int[] usage = {-1};
        final int[] done = {0};

        DeepSeekWeb.FrameParser fp = new DeepSeekWeb.FrameParser(new DeepSeekWeb.Listener() {
            @Override public void onThinking(String t) { thinking.append(t); }
            @Override public void onContent(String t) { content.append(t); }
            @Override public void onMeta(long id) { meta[0] = id; }
            @Override public void onUsage(int total) { usage[0] = total; }
            @Override public void onDone() { done[0]++; }
        });
        fp.consume(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));

        eq("正文拼装", "代可用", content.toString());
        eq("思维链单独归入", "思考中", thinking.toString());
        eq("response_message_id", 2L, meta[0]);
        eq("usage 总数", 43, usage[0]);
        ok("收到 done 信号", done[0] >= 1);

        // 累积全文去重：同一 fragment 发两次全量，只取新增
        String dup = "data: {\"v\":{\"response\":{\"message_id\":1,\"fragments\":"
                + "[{\"id\":1,\"type\":\"RESPONSE\",\"content\":\"你好\"}]}}}\n\n"
                + "data: {\"p\":\"response/fragments\",\"v\":[{\"id\":1,\"type\":\"RESPONSE\","
                + "\"content\":\"你好世界\"}]}\n\n";
        final StringBuilder c2 = new StringBuilder();
        new DeepSeekWeb.FrameParser(new DeepSeekWeb.Listener() {
            @Override public void onThinking(String t) { }
            @Override public void onContent(String t) { c2.append(t); }
            @Override public void onMeta(long id) { }
            @Override public void onUsage(int total) { }
            @Override public void onDone() { }
        }).consume(new ByteArrayInputStream(dup.getBytes(StandardCharsets.UTF_8)));
        eq("累积全文去重", "你好世界", c2.toString());

        // THINK 段之后必须切回 content，不能把正文当思维链
        String switchSeg = "data: {\"v\":{\"response\":{\"fragments\":"
                + "[{\"id\":1,\"type\":\"THINK\",\"content\":\"想\"}]}}}\n\n"
                + "data: {\"p\":\"response/fragments\",\"v\":[{\"id\":2,\"type\":\"RESPONSE\","
                + "\"content\":\"答\"}]}\n\n";
        final StringBuilder c3 = new StringBuilder();
        final StringBuilder t3 = new StringBuilder();
        new DeepSeekWeb.FrameParser(new DeepSeekWeb.Listener() {
            @Override public void onThinking(String t) { t3.append(t); }
            @Override public void onContent(String t) { c3.append(t); }
            @Override public void onMeta(long id) { }
            @Override public void onUsage(int total) { }
            @Override public void onDone() { }
        }).consume(new ByteArrayInputStream(switchSeg.getBytes(StandardCharsets.UTF_8)));
        eq("THINK 段归思维链", "想", t3.toString());
        eq("RESPONSE 段归正文", "答", c3.toString());

        // Base64：不能用 java.util.Base64（API 26）也不能用 android.util.Base64（桌面没有），
        // 所以自带实现必须和标准结果逐字节一致，含 0/1/2 个尾字节三种 padding。
        eq("base64 空", "", DeepSeekWeb.base64(new byte[0]));
        eq("base64 1 字节", "QQ==", DeepSeekWeb.base64(new byte[]{'A'}));
        eq("base64 2 字节", "QUI=", DeepSeekWeb.base64(new byte[]{'A', 'B'}));
        eq("base64 3 字节", "QUJD", DeepSeekWeb.base64(new byte[]{'A', 'B', 'C'}));
        eq("base64 4 字节", "QUJDRA==", DeepSeekWeb.base64(new byte[]{'A', 'B', 'C', 'D'}));
        // 0xFF 覆盖高位（byte 是有符号的，漏了 &0xFF 会出负数下标）
        eq("base64 高位字节", "/w==", DeepSeekWeb.base64(new byte[]{(byte) 0xFF}));
        eq("base64 与 JDK 一致", java.util.Base64.getEncoder().encodeToString(
                "{\"answer\":24423,\"算法\":\"DeepSeekHashV1\"}".getBytes(StandardCharsets.UTF_8)),
                DeepSeekWeb.base64(
                "{\"answer\":24423,\"算法\":\"DeepSeekHashV1\"}".getBytes(StandardCharsets.UTF_8)));

        // 重试判定：网络类错误重试有意义，上游明确拒绝的重试一万次也一样。
        ok("首帧超时可重试", new DeepSeekWeb.DeepSeekException(
                DeepSeekWeb.CODE_SILENT, "30 秒没响应").isRetryable());
        ok("连不上可重试", new DeepSeekWeb.DeepSeekException(-1, "connect failed").isRetryable());
        ok("登录失效不重试", !new DeepSeekWeb.DeepSeekException(40003, "INVALID_TOKEN").isRetryable());
        ok("PoW 失败不重试", !new DeepSeekWeb.DeepSeekException(40301, "INVALID_POW_RESPONSE").isRetryable());
        ok("缺 PoW 头不重试", !new DeepSeekWeb.DeepSeekException(40300, "MISSING_HEADER").isRetryable());
        ok("登录失效仍被识别为鉴权错误", new DeepSeekWeb.DeepSeekException(40003, "x").isAuthError());

        // ── latestTurn：复用上游会话时只发最新一轮，避免历史灌两遍 ──
        JSONArray full = new JSONArray();
        full.put(new JSONObject().put("role", "system").put("content", "系统提示"));
        full.put(new JSONObject().put("role", "user").put("content", "第一问"));
        full.put(new JSONObject().put("role", "assistant").put("content", "第一答"));
        full.put(new JSONObject().put("role", "user").put("content", "第二问"));
        JSONArray cut = DeepSeekClient.latestTurn(full);
        eq("最新一轮只留 2 条", 2, cut.length());
        ok("保留 system", "system".equals(cut.getJSONObject(0).getString("role")));
        ok("保留最后一条 user", "第二问".equals(cut.getJSONObject(1).getString("content")));
        ok("不再包含更早的问答",
           !cut.toString().contains("第一问") && !cut.toString().contains("第一答"), cut.toString());

        /* 工具调用轮次：assistant(tool_calls) 与 tool 结果都在最后一条 user 之后，必须保留 */
        JSONArray toolHist = new JSONArray();
        toolHist.put(new JSONObject().put("role", "system").put("content", "系统提示"));
        toolHist.put(new JSONObject().put("role", "user").put("content", "看这题"));
        toolHist.put(new JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", new JSONArray().put(new JSONObject()
                        .put("function", new JSONObject().put("name", "get_current_question")))));
        toolHist.put(new JSONObject().put("role", "tool").put("name", "get_current_question")
                .put("content", "{\"stem\":\"甲题\"}"));
        JSONArray toolCut = DeepSeekClient.latestTurn(toolHist);
        eq("工具轮次保留 4 条（system + user + assistant + tool）", 4, toolCut.length());
        ok("工具结果没被截掉", toolCut.toString().contains("甲题"), toolCut.toString());

        /* 没有 user 消息时不截断，宁可重复也别丢上下文 */
        JSONArray noUser = new JSONArray();
        noUser.put(new JSONObject().put("role", "system").put("content", "只有系统"));
        eq("没有 user 时整段返回", 1, DeepSeekClient.latestTurn(noUser).length());
        eq("空消息返回空", 0, DeepSeekClient.latestTurn(new JSONArray()).length());

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
