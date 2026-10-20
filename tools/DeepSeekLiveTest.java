package com.fan.shuatiku;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 打真实上游的联调测试：用 web2api-mini/.env 里那份登录态，跑通
 * 「建会话 → 算 PoW → 流式对话 → 解析工具调用」整条链。
 *
 * <p>这个测试<b>不进 check.cmd</b>：它依赖网络和一份真实凭证，
 * 放在回归里会让离线构建失败。手工跑：
 * <pre>java -cp ... com.fan.shuatiku.DeepSeekLiveTest [.env 路径]</pre>
 */
public class DeepSeekLiveTest {

    private static int pass, fail;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : " — " + detail));
        }
    }

    public static void main(String[] args) throws Exception {
        String envPath = args.length > 0 ? args[0] : "D:\\desktop\\web2api-mini\\.env";
        File f = new File(envPath);
        if (!f.isFile()) {
            System.out.println("找不到 " + envPath + "，跳过联调");
            return;
        }
        String token = readEnv(f, "UPSTREAM_TOKEN");
        if (token == null || token.isEmpty()) {
            System.out.println("UPSTREAM_TOKEN 为空，跳过联调");
            return;
        }
        System.out.println("=== 真实上游联调（token 长度 " + token.length() + "）===");

        DeepSeekWeb web = new DeepSeekWeb(token);

        // 1) 建会话：随机 UUID 会被拒，必须走这个接口
        String sid = web.createSession();
        ok("建会话拿到 chat_session_id", sid != null && sid.length() >= 16, sid);

        // 2) PoW + 流式：一次带工具的问答
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final long[] respId = {-1};
        final int[] usage = {-1};
        final int[] done = {0};
        long t0 = System.currentTimeMillis();
        web.stream("用一句话说明什么是二叉树。", sid, null, false, new DeepSeekWeb.Listener() {
            @Override public void onThinking(String t) { thinking.append(t); }
            @Override public void onContent(String t) { content.append(t); }
            @Override public void onMeta(long id) { respId[0] = id; }
            @Override public void onUsage(int total) { usage[0] = total; }
            @Override public void onDone() { done[0]++; }
        });
        long ms = System.currentTimeMillis() - t0;

        System.out.println("  耗时 " + ms + "ms，正文 " + content.length() + " 字，"
                + "思维链 " + thinking.length() + " 字，usage=" + usage[0]);
        ok("收到正文", content.length() > 0, content.toString());
        ok("拿到 response_message_id", respId[0] > 0, String.valueOf(respId[0]));
        ok("收到结束信号", done[0] >= 1, String.valueOf(done[0]));
        if (content.length() > 0) {
            String head = content.toString();
            if (head.length() > 120) head = head.substring(0, 120) + "…";
            System.out.println("  回答：" + head);
        }

        // 3) 思考模式：thinking_enabled=true 时思维链与正文分两条通道，且要真能逐字到达
        String sidT = web.createSession();
        final StringBuilder tc = new StringBuilder();
        final StringBuilder tt = new StringBuilder();
        final long[] firstThink = {0};
        final long[] firstText = {0};
        final int[] thinkChunks = {0};
        final int[] textChunks = {0};
        long t1 = System.currentTimeMillis();
        web.stream("9.11 和 9.9 哪个大？只说结论。", sidT, null, true, new DeepSeekWeb.Listener() {
            @Override public void onThinking(String t) {
                if (firstThink[0] == 0) firstThink[0] = System.currentTimeMillis();
                thinkChunks[0]++;
                tt.append(t);
            }
            @Override public void onContent(String t) {
                if (firstText[0] == 0) firstText[0] = System.currentTimeMillis();
                textChunks[0]++;
                tc.append(t);
            }
            @Override public void onMeta(long id) { }
            @Override public void onUsage(int total) { }
            @Override public void onDone() { }
        });
        long msT = System.currentTimeMillis() - t1;
        long lagThink = firstThink[0] == 0 ? -1 : firstThink[0] - t1;
        long lagText = firstText[0] == 0 ? -1 : firstText[0] - t1;
        System.out.println("  思考模式：耗时 " + msT + "ms，首字(思考) " + lagThink + "ms，首字(正文) "
                + lagText + "ms，思维链 " + tt.length() + " 字/" + thinkChunks[0] + " 帧，正文 "
                + tc.length() + " 字/" + textChunks[0] + " 帧");
        System.out.println("  正文：" + brief(tc.toString()));
        ok("思考模式拿到思维链", tt.length() > 0, String.valueOf(tt.length()));
        ok("思考模式拿到正文", tc.length() > 0, String.valueOf(tc.length()));
        ok("思维链没有混进正文", !tc.toString().contains(tt.length() > 20
                ? tt.substring(0, 20) : "___never___"), brief(tc.toString()));
        ok("正文分多帧到达（真流式）", textChunks[0] > 1, String.valueOf(textChunks[0]));
        ok("首字延迟小于总耗时", lagText >= 0 && lagText <= msT, lagText + "/" + msT);

        // 4) 工具调用：让模型走一次 <tool_call> 通道，验证 parseAssistant 端到端
        org.json.JSONArray tools = new org.json.JSONArray();
        tools.put(new org.json.JSONObject().put("type", "function").put("function",
                new org.json.JSONObject()
                        .put("name", "get_current_question")
                        .put("description", "读取当前正在做的题目")
                        .put("parameters", new org.json.JSONObject().put("type", "object"))));
        org.json.JSONArray msgs = new org.json.JSONArray();
        msgs.put(new org.json.JSONObject().put("role", "user")
                .put("content", "先看看我现在做的是哪道题，再说说这题考什么。"));
        String prompt = DeepSeekClient.buildPrompt(msgs, tools);

        String sid2 = web.createSession();
        final StringBuilder c2 = new StringBuilder();
        web.stream(prompt, sid2, null, false, new DeepSeekWeb.Listener() {
            @Override public void onThinking(String t) { }
            @Override public void onContent(String t) { c2.append(t); }
            @Override public void onMeta(long id) { }
            @Override public void onUsage(int total) { }
            @Override public void onDone() { }
        });
        DeepSeekClient.Parsed parsed = DeepSeekClient.parseAssistant(c2.toString());
        System.out.println("  原始输出：" + brief(c2.toString()));
        ok("模型输出了 tool_call", parsed.toolCalls.length() > 0, brief(c2.toString()));
        if (parsed.toolCalls.length() > 0) {
            ok("工具名正确", "get_current_question".equals(
                    parsed.toolCalls.getJSONObject(0).getJSONObject("function")
                            .getString("name")), parsed.toolCalls.toString());
            ok("arguments 是字符串", parsed.toolCalls.getJSONObject(0)
                    .getJSONObject("function").get("arguments") instanceof String, null);
            System.out.println("  tool_calls：" + parsed.toolCalls);
        }

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    private static String brief(String s) {
        String t = s.replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    /** 极简 .env 读取：只认 KEY=VALUE，忽略注释和引号。 */
    private static String readEnv(File f, String key) throws Exception {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (!line.substring(0, eq).trim().equals(key)) continue;
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                    || v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            return v;
        }
        return null;
    }
}
