package com.fan.shuatiku;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 外部 OpenAI 兼容接口的流式客户端（走用户自己的 API Key）。
 *
 * <p>和内置通道是两条完全不同的协议：
 * <ul>
 *   <li>{@link DeepSeekWeb} —— 目标站的私有 SSE 方言，要算 PoW、自己拆 fragment；</li>
 *   <li>本类 —— 标准 OpenAI {@code chat.completion.chunk}，eventsource 的
 *       {@code data: {...}} 一行一帧，末帧 {@code [DONE]}。</li>
 * </ul>
 *
 * <p>为什么要单独一个类：内置通道的流式是「原生解析 + 回抛给页面」，
 * 而这里也要做同样的事——否则 API Key 模式只能等整段返回，用户看到的是
 * 转圈半天然后突然蹦出一大坨字。
 */
final class OpenAiStream {

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 180000;

    /** 回调：正文增量、思维链增量、结束原因。 */
    interface Listener {
        void onContent(String delta);
        void onReasoning(String delta);
        void onFinish(String reason);
    }

    private OpenAiStream() {}

    /**
     * 发一次流式请求并边读边回调。
     *
     * @return 上游的 finish_reason（拿不到就返回 "stop"）
     */
    static String run(String url, String apiKey, String bodyJson, Listener listener) throws Exception {
        // 请求体加 stream:true，并让上游把 usage 一起带回来
        JSONObject body = new JSONObject(bodyJson);
        body.put("stream", true);
        try {
            JSONObject opts = body.optJSONObject("stream_options");
            if (opts == null) opts = new JSONObject();
            opts.put("include_usage", true);
            body.put("stream_options", opts);
        } catch (Exception ignored) { }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        if (apiKey != null && !apiKey.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(b.length);
        String finish = "stop";
        try {
            OutputStream os = c.getOutputStream();
            try { os.write(b); } finally { os.close(); }

            int code = c.getResponseCode();
            String ct = c.getContentType();

            /* 上游不支持流式时会回 application/json 的完整响应（有些兼容实现如此）。
               这种情况要退回「一次性解析」，不能报错。 */
            if (code >= 400 || ct == null || ct.indexOf("event-stream") < 0) {
                /* 4xx/5xx 与「上游不认 stream」在这里汇合，必须分开处理。 */
                if (code >= 400) {
                    /* 踩过的坑：设了 Accept: text/event-stream + setFixedLengthStreamingMode
                       之后，HttpURLConnection 在 4xx 上会把 getErrorStream() 置空、
                       且 getInputStream() 抛 HttpRetryException —— 响应体根本拿不到。
                       所以错误体读不到时，退回「状态码 + 一句人话」，别把原始状态码
                       直接抛给用户（他看不懂，也不知道该改什么）。 */
                    String raw = "";
                    try { raw = readAll(c.getErrorStream()); } catch (Exception ignored) { }
                    String msg = raw.isEmpty() ? httpHint(code) : extractError(raw, code);
                    throw new ApiException(code, msg);
                }
                String raw = readAll(c.getInputStream());
                // 非流式回包：整段解析后当作一次增量吐出去
                JSONObject one = new JSONObject(raw);
                JSONObject msg = one.optJSONArray("choices") == null ? null
                        : one.getJSONArray("choices").optJSONObject(0);
                JSONObject m = msg == null ? null : msg.optJSONObject("message");
                if (m != null) {
                    String reasoning = m.optString("reasoning_content", "");
                    if (!reasoning.isEmpty()) listener.onReasoning(reasoning);
                    String content = m.optString("content", "");
                    if (!content.isEmpty()) listener.onContent(content);
                }
                String fr = msg == null ? "stop" : msg.optString("finish_reason", "stop");
                listener.onFinish(fr);
                return fr;
            }

            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith(":")) continue;      // 空行/注释心跳
                if (!s.startsWith("data:")) continue;                // 只认 data 行
                String payload = s.substring(5).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) break;

                JSONObject o;
                try { o = new JSONObject(payload); } catch (Exception e) { continue; }

                // 有的实现把错误塞进流里
                JSONObject err = o.optJSONObject("error");
                if (err != null) {
                    throw new ApiException(0, err.optString("message", "upstream stream error"));
                }

                JSONArray choices = o.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject ch = choices.optJSONObject(0);
                if (ch == null) continue;

                // 标准增量在 delta；少数实现在首帧给 message，这里都兜一下
                JSONObject d = ch.optJSONObject("delta");
                if (d == null) d = ch.optJSONObject("message");
                if (d != null) {
                    String reasoning = d.optString("reasoning_content", "");
                    if (reasoning.isEmpty()) reasoning = d.optString("reasoning", "");
                    if (!reasoning.isEmpty()) listener.onReasoning(reasoning);
                    String content = d.optString("content", "");
                    if (!content.isEmpty()) listener.onContent(content);
                }
                String fr = ch.optString("finish_reason", "");
                if (!fr.isEmpty() && !"null".equals(fr)) finish = fr;
            }
        } finally {
            c.disconnect();
        }
        listener.onFinish(finish);
        return finish;
    }

    /** 错误体拿不到时的兜底文案：把状态码翻成「用户该改什么」。 */
    private static String httpHint(int code) {
        switch (code) {
            case 400: return "请求被上游拒绝（400）。检查接口地址与模型名。";
            case 401: return "API Key 无效或已过期（401）。请到设置里换一个 Key。";
            case 402: return "上游账号余额不足（402）。";
            case 403: return "上游拒绝访问（403）。Key 可能没有该模型的权限。";
            case 404: return "接口地址不存在（404）。检查地址是否少写了 /v1。";
            case 413: return "请求太大（413）。题目或历史过长。";
            case 429: return "请求太频繁或额度用尽（429）。歇一会儿再试。";
            case 500:
            case 502:
            case 503:
            case 504: return "上游服务暂时不可用（" + code + "）。稍后重试。";
            default: return "上游返回 HTTP " + code + "。";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { in.close(); }
        return out.toString("UTF-8");
    }

    /** 把上游错误体翻成人话。OpenAI 风格是 {"error":{"message":...}}。 */
    private static String extractError(String raw, int code) {
        try {
            JSONObject j = new JSONObject(raw);
            JSONObject e = j.optJSONObject("error");
            if (e != null) return e.optString("message", "HTTP " + code);
            String m = j.optString("message", "");
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) { }
        String t = raw == null ? "" : raw.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    /** 上游错误（带 HTTP 码，便于上层区分 401 与 5xx）。 */
    static final class ApiException extends Exception {
        final int code;
        ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }
}
