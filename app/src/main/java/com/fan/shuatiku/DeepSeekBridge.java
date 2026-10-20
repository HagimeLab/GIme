package com.fan.shuatiku;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App 内「大肥鱼」的后端：把页面的 OpenAI 请求直接接到 DeepSeek 网页版。
 *
 * <p>这是整件事的落点 —— 用户只需要在手机上用官方手机号登录一次，
 * 之后 App 自己就是那个「反代」，不需要 API Key、不需要电脑、不需要中间服务。
 *
 * <p>页面侧接口保持原样（{@code Android.chatApi(id, url, body, key)}），
 * 只是当 base 指向内置通道时改走 {@link #ask}。这样页面代码与回归测试都不用动。
 */
public final class DeepSeekBridge {

    /** 页面把接口地址填成这个值，就表示「用 App 内置通道」。 */
    static final String BUILTIN_BASE = "builtin://deepseek";
    /** 对外回显的模型名。 */
    static final String MODEL = "deepseek-web";

    private static final String PREFS = DeepSeekLoginActivity.PREFS;
    private static final String PREF_TOKEN = DeepSeekLoginActivity.PREF_TOKEN;
    private static final String PREF_MOBILE = DeepSeekLoginActivity.PREF_MOBILE;
    private static final String PREF_LOGIN_AT = DeepSeekLoginActivity.PREF_LOGIN_AT;

    /** 会话缓存：页面传的 user 字段 → (chat_session_id, 上一轮 response_message_id)。 */
    private static final class Turn {
        final String sessionId;
        final Long parent;
        Turn(String sessionId, Long parent) {
            this.sessionId = sessionId;
            this.parent = parent;
        }
    }

    /**
     * 是否复用上游会话。
     *
     * <p>默认**关闭**：页面每轮都会把完整历史拍平发过来（工具调用循环里还会连发几轮），
     * 再让上游会话自己累积一遍，历史就会重复一遍。无状态（每次新建会话、只发完整 prompt）
     * 是唯一不会出错的做法，代价只是一次建会话的往返。
     */
    private static final boolean REUSE_SESSION = false;

    /** 网络类失败最多重试几次（总共最多跑 MAX_RETRY+1 次）。 */
    private static final int MAX_RETRY = 2;
    /** 退避基数：第 n 次重试前等 n 倍（1s、2s），不要一断就狂打上游。 */
    private static final int RETRY_DELAY_MS = 1000;

    /** 正在跑的请求 → 它的 DeepSeekWeb，用来支持「取消」。 */
    private final ConcurrentHashMap<String, DeepSeekWeb> cancelOf =
            new ConcurrentHashMap<String, DeepSeekWeb>();

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ConcurrentHashMap<String, Turn> turns = new ConcurrentHashMap<String, Turn>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicInteger seq = new AtomicInteger();

    /** 结果回抛给页面的回调（MainActivity 用 js() 实现）。 */
    interface Sink {
        void toJs(String script);
    }

    private final Sink sink;

    DeepSeekBridge(Activity activity, Sink sink) {
        this.activity = activity;
        this.sink = sink;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    /** 当前登录态（给页面显示用）。 */
    static String statusJson(Activity a) {
        SharedPreferences p = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String token = p.getString(PREF_TOKEN, "");
        long at = p.getLong(PREF_LOGIN_AT, 0);
        String mobile = p.getString(PREF_MOBILE, "");
        JSONObject o = new JSONObject();
        try {
            o.put("loggedIn", token != null && !token.isEmpty());
            o.put("loginAt", at);
            o.put("mobile", mobile == null ? "" : mobile);
            o.put("base", BUILTIN_BASE);
            o.put("model", MODEL);
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** 清掉登录态（退出登录）。 */
    static void clear(Activity a) {
        a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .remove(PREF_TOKEN).remove(PREF_MOBILE).remove(PREF_LOGIN_AT).apply();
    }

    /** 打开登录页。 */
    void openLogin() {
        activity.startActivity(new Intent(activity, DeepSeekLoginActivity.class));
    }

    /**
     * 取 token。兼容历史脏数据：老版本曾把 localStorage 的 JSON 包装
     * {@code {"value":"cnd...","__version":"0"}} 整串存进来（导致 401），
     * 读到这种就解包取 value 并回写修好，用户不用重新登录。
     */
    private String token() {
        String t = prefs.getString(PREF_TOKEN, "");
        if (t == null) t = "";
        t = t.trim();
        if (t.startsWith("{")) {
            try {
                String v = new org.json.JSONObject(t).optString("value", "");
                if (v != null && !v.isEmpty()) {
                    t = v.trim();
                    prefs.edit().putString(PREF_TOKEN, t).apply();
                }
            } catch (Exception ignored) { }
        }
        return t;
    }

    /**
     * 处理一次 chatApi 调用。与 {@code MainActivity.chatApi} 的签名保持一致，
     * 页面完全感知不到差别。
     *
     * @param id   请求 id，回包时原样带回
     * @param body OpenAI 请求体（JSON 字符串）
     */
    void ask(final String id, final String body) {
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    String out = runSync(id, body);
                    sink.toJs("window.onChatApi(" + quote(id) + ",200," + quote(out) + ")");
                } catch (DeepSeekWeb.DeepSeekException e) {
                    // 登录失效要让页面能识别出来，单独给一个可读的 401
                    int status = e.isAuthError() ? 401 : 502;
                    fail(id, status, e.getMessage());
                } catch (Exception e) {
                    String m = e.getMessage();
                    if (m == null) m = e.getClass().getSimpleName();
                    fail(id, 502, m);
                } finally {
                    cancelOf.remove(id);
                }
            }
        });
    }

    /**
     * 这个异常值不值得重试。
     *
     * <p>值得：连接超时、读超时、DNS 解析失败、连接被重置、首帧看门狗掐断（code=-2）。
     * 不值得：登录失效（40003）、PoW 错（40301）、参数错 —— 重试多少次都一样，
     * 只会让用户多等几十秒才看到同样的错误。
     */
    private static boolean isTransient(Exception e) {
        if (e instanceof DeepSeekWeb.DeepSeekException) {
            return ((DeepSeekWeb.DeepSeekException) e).isRetryable();
        }
        return e instanceof java.io.IOException;
    }

    /** 重试提示文案（会以一条小灰字插进对话里）。 */
    private static String retryNote(int attempt, Exception e) {
        String why = e.getMessage();
        if (why == null || why.isEmpty()) why = e.getClass().getSimpleName();
        if (why.length() > 40) why = why.substring(0, 40);
        return "网络不稳定（" + why + "），正在重试 " + attempt + "/" + MAX_RETRY;
    }

    /** 取消一个正在跑的请求（用户点了「停止」）。 */
    void cancel(String id) {
        DeepSeekWeb web = cancelOf.remove(id);
        if (web != null) web.abort();
    }

    private void fail(String id, int status, String msg) {
        JSONObject err = new JSONObject();
        try {
            JSONObject inner = new JSONObject();
            inner.put("message", msg == null ? "" : msg);
            err.put("error", inner);
        } catch (Exception ignored) { }
        sink.toJs("window.onChatApi(" + quote(id) + "," + status + "," + quote(err.toString()) + ")");
    }

    /** 同步跑完一次对话（在池线程里执行）。 */
    private String runSync(final String id, String bodyJson) throws Exception {
        String token = token();
        if (token.isEmpty()) {
            throw new DeepSeekWeb.DeepSeekException(40003, "还没登录 DeepSeek，请先在设置里用手机号登录");
        }

        JSONObject req = new JSONObject(bodyJson);
        JSONArray messages = req.optJSONArray("messages");
        if (messages == null) messages = new JSONArray();
        JSONArray tools = req.optJSONArray("tools");

        // 页面传的 user 当会话键；没有就退化成一个固定值（单用户 App 足够）
        String convKey = req.optString("user", "");
        if (convKey.isEmpty()) convKey = "default";

        final DeepSeekWeb web = new DeepSeekWeb(token);
        cancelOf.put(id, web);
        String prompt = DeepSeekClient.buildPrompt(messages, tools);

        Turn prev = REUSE_SESSION ? turns.get(convKey) : null;
        String sid;
        Long parent;
        if (prev != null) {
            sid = prev.sessionId;
            parent = prev.parent;
        } else {
            sid = web.createSession();
            parent = null;
        }

        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final long[] respId = {-1};
        final int[] usage = {-1};

        /* 流式回抛：边收边推给页面，用户不用盯着「正在想…」等十几秒。
           思维链单独走一条通道（kind=think），页面拿它画可折叠的「思考中」。 */
        final Streamer thinkOut = new Streamer(id, "think");
        final Streamer textOut = new Streamer(id, "text");

        /* 重试：只在「一个字都没收到」时重来。
           一旦已经有字流到页面上了，重试就会让用户看到重复的正文，
           所以那种情况宁可报错也不重试。 */
        int attempt = 0;
        while (true) {
            try {
                content.setLength(0);
                reasoning.setLength(0);
                web.stream(prompt, sid, parent, true, new DeepSeekWeb.Listener() {
                    @Override public void onThinking(String t) {
                        reasoning.append(t);
                        thinkOut.feed(t);
                    }
                    @Override public void onContent(String t) {
                        content.append(t);
                        textOut.feed(t);
                    }
                    @Override public void onMeta(long rid) { respId[0] = rid; }
                    @Override public void onUsage(int total) { usage[0] = total; }
                    @Override public void onDone() { }
                });
                break;
            } catch (Exception e) {
                boolean gotSomething = content.length() > 0 || reasoning.length() > 0;
                attempt++;
                if (gotSomething || attempt > MAX_RETRY || !isTransient(e)) throw e;
                // 建会话可能是它先挂的：一并重建，别拿着一个可能已经失效的 sid 重试
                try {
                    sid = web.createSession();
                    parent = null;
                } catch (Exception ignored) { }
                sink.toJs("window.onChatRetry(" + quote(id) + "," + attempt + ","
                        + quote(retryNote(attempt, e)) + ")");
                try { Thread.sleep(RETRY_DELAY_MS * attempt); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        thinkOut.finish();
        textOut.finish();
        cancelOf.remove(id);

        if (REUSE_SESSION && respId[0] >= 0) {
            turns.put(convKey, new Turn(sid, respId[0]));
        }

        // 解析工具调用：只要本轮带了 tools 就必须解析，否则页面永远收不到 tool_calls
        DeepSeekClient.Parsed parsed = (tools != null && tools.length() > 0)
                ? DeepSeekClient.parseAssistant(content.toString())
                : new DeepSeekClient.Parsed(content.toString(), new JSONArray());

        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", parsed.text);
        if (parsed.toolCalls.length() > 0) {
            msg.put("tool_calls", parsed.toolCalls);
        }
        if (reasoning.length() > 0) {
            msg.put("reasoning_content", reasoning.toString());
        }

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", msg);
        choice.put("finish_reason", parsed.toolCalls.length() > 0 ? "tool_calls" : "stop");

        JSONObject resp = new JSONObject();
        resp.put("id", "chatcmpl-" + Long.toHexString(System.nanoTime()) + (seq.incrementAndGet()));
        resp.put("object", "chat.completion");
        resp.put("created", System.currentTimeMillis() / 1000);
        resp.put("model", MODEL);
        resp.put("choices", new JSONArray().put(choice));
        resp.put("usage", buildUsage(prompt, parsed.text, reasoning.toString(), usage[0]));
        return resp.toString();
    }

    /** 组装 OpenAI usage。上游只给总数时按字符比例分配，保证「输入+输出=总量」。 */
    private static JSONObject buildUsage(String prompt, String content, String reasoning, int total) {
        int estP = Math.max(1, prompt.length() / 2);
        int estC = Math.max(1, (content.length() + reasoning.length()) / 2);
        int pt, ct, tt;
        if (total > 0) {
            tt = total;
            pt = Math.max(1, Math.round((float) tt * estP / (estP + estC)));
            ct = Math.max(1, tt - pt);
        } else {
            pt = estP;
            ct = estC;
            tt = pt + ct;
        }
        JSONObject u = new JSONObject();
        try {
            u.put("prompt_tokens", pt);
            u.put("completion_tokens", ct);
            u.put("total_tokens", tt);
        } catch (Exception ignored) { }
        return u;
    }

    /**
     * 把一个通道的增量攒成小批再推给页面。
     *
     * <p>上游的 token 帧有时一两个字一条，逐条 {@code evaluateJavascript} 会把
     * WebView 主线程打满（页面会卡、字反而更慢）。这里按「至少 40ms 或攒够 24 字」
     * 合并成一次推送 —— 视觉上仍是逐字冒出来，调用次数降一个数量级。
     */
    private final class Streamer {
        private final String id;
        private final String kind;
        private final StringBuilder buf = new StringBuilder();
        private long last = 0;

        Streamer(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        synchronized void feed(String delta) {
            if (delta == null || delta.isEmpty()) return;
            buf.append(delta);
            long now = System.currentTimeMillis();
            if (buf.length() >= 24 || now - last >= 40) flush();
        }

        /** 把缓冲里的内容立刻推出去（流结束时必须调，否则尾巴会丢）。 */
        synchronized void finish() {
            flush();
        }

        private void flush() {
            if (buf.length() == 0) return;
            String chunk = buf.toString();
            buf.setLength(0);
            last = System.currentTimeMillis();
            sink.toJs("window.onChatStream(" + quote(id) + "," + quote(kind) + ","
                    + quote(chunk) + ")");
        }
    }

    /** 与 MainActivity.quote 同构：把字符串安全嵌进 JS 源码。 */
    private static String quote(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    void shutdown() {
        pool.shutdownNow();
    }
}
