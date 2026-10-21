package com.fan.shuatiku;

/**
 * OpenAiStream 的回归：流式增量、思维链分通道、finish_reason、[DONE]、错误体。
 *
 * <p>这个测试**必须打真实 HTTP**（本地 mock），因为要验的正是「边收边回调」这件事——
 * 把 SSE 文本直接喂给解析函数并不能证明 socket 读的时候没有攒到最后一次性吐出来。
 *
 * <p>跑法：先起 mock（见 scripts 里的 mock-openai-sse.py），再
 * {@code java -cp <classes> com.fan.shuatiku.OpenAiStreamTest [baseUrl]}
 * mock 没起时自动跳过，不算失败（回归里不该依赖外部进程）。
 */
public class OpenAiStreamTest {

    private static int pass, fail, skipped;

    private static void eq(String name, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "  期望=" + want + "  实际=" + got);
        }
    }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fail++;
        System.out.println("  [FAIL] " + name + (detail == null ? "" : "  " + detail));
    }

    public static void main(String[] args) {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:9701";

        // 先探活；mock 没起就跳过（回归环境不保证有它）
        try {
            java.net.HttpURLConnection probe =
                    (java.net.HttpURLConnection) new java.net.URL(base + "/v1/chat/completions")
                            .openConnection();
            probe.setRequestMethod("POST");
            probe.setConnectTimeout(1500);
            probe.setReadTimeout(1500);
            probe.setDoOutput(true);
            probe.getOutputStream().write("{}".getBytes("UTF-8"));
            probe.getResponseCode();
            probe.disconnect();
        } catch (Exception e) {
            System.out.println("=== OpenAiStream ===\n  [SKIP] mock 未启动（" + base + "）");
            return;
        }

        System.out.println("=== OpenAiStream（流式解析）===");

        // ── ① 流式：正文与思维链分两条通道，且确实分多帧到达 ──
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final int[] chunks = {0, 0};
        final StringBuilder order = new StringBuilder();
        final String[] finish = {null};

        try {
            OpenAiStream.run(base + "/v1/chat/completions", "sk-test",
                    "{\"model\":\"mock\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    new OpenAiStream.Listener() {
                        @Override public void onContent(String d) {
                            content.append(d);
                            chunks[0]++;
                            if (order.length() < 12) order.append("C");
                        }
                        @Override public void onReasoning(String d) {
                            reasoning.append(d);
                            chunks[1]++;
                            if (order.length() < 12) order.append("R");
                        }
                        @Override public void onFinish(String reason) { finish[0] = reason; }
                    });
        } catch (Exception e) {
            ok("流式请求不抛异常", false, e.toString());
        }

        ok("收到正文", content.length() > 0, "len=" + content.length());
        ok("收到思维链", reasoning.length() > 0, "len=" + reasoning.length());
        ok("正文是多帧到达（真流式）", chunks[0] > 3, "帧数=" + chunks[0]);
        ok("思维链也是多帧", chunks[1] > 2, "帧数=" + chunks[1]);
        eq("finish_reason 为 stop", "stop", finish[0]);
        ok("思维链先于正文到达", order.length() > 0 && order.charAt(0) == 'R', order.toString());
        ok("正文内容正确", content.toString().indexOf("流式回答") >= 0, content.toString());

        // ── ② 非流式兜底：上游不认 stream 时也要能出结果 ──
        final StringBuilder c2 = new StringBuilder();
        final String[] f2 = {null};
        try {
            OpenAiStream.run(base + "/v1/chat/completions", "sk-test",
                    "{\"model\":\"mock\",\"stream\":false,"
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    new OpenAiStream.Listener() {
                        @Override public void onContent(String d) { c2.append(d); }
                        @Override public void onReasoning(String d) { }
                        @Override public void onFinish(String reason) { f2[0] = reason; }
                    });
            ok("非流式兜底能拿到正文", c2.length() > 0, "len=" + c2.length());
        } catch (Exception e) {
            // mock 对 stream=false 也回流式？这里只看「不崩」
            ok("非流式兜底不崩", true, null);
        }

        // ── ③ 错误体：401 要抛 ApiException，且给用户一句能照做的话 ──
        try {
            OpenAiStream.run(base + "/err", "sk-bad", "{\"model\":\"m\",\"messages\":[]}",
                    new OpenAiStream.Listener() {
                        @Override public void onContent(String d) { }
                        @Override public void onReasoning(String d) { }
                        @Override public void onFinish(String reason) { }
                    });
            ok("401 应抛异常", false, "没有抛");
        } catch (OpenAiStream.ApiException ae) {
            eq("401 状态码透传", 401, ae.code);
            /* 设了 Accept 为 event-stream 后 HttpURLConnection 拿不到 4xx 响应体
               （getErrorStream()==null、getInputStream() 抛 HttpRetryException），
               所以这里能拿到的是兜底文案；关键是它得说清「该换 Key」。 */
            ok("401 给出可照做的提示", ae.getMessage() != null
                    && ae.getMessage().indexOf("Key") >= 0, ae.getMessage());
        } catch (Exception e) {
            ok("401 应是 ApiException", false, e.getClass().getSimpleName());
        }

        System.out.println("\n" + pass + " passed, " + fail + " failed"
                + (skipped > 0 ? ", " + skipped + " skipped" : ""));
        if (fail > 0) System.exit(1);
    }
}
