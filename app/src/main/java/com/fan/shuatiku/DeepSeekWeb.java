package com.fan.shuatiku;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * DeepSeek 网页版的私有协议客户端 —— 这是整个「App 内反代」的核心。
 *
 * <p>网页版没有公开 API，只在浏览器里用一套私有 SSE 方言。这里把它封装成
 * 三个动作，让上层（{@link DeepSeekClient}）完全不必知道方言长什么样：
 * <ol>
 *   <li>{@link #createSession()} —— 建一个真实会话，拿 chat_session_id（随机 UUID 会被拒）；</li>
 *   <li>{@link #powHeader()} —— 取挑战、本地暴破、拼出 x-ds-pow-response 头；</li>
 *   <li>{@link #stream} —— 发起流式对话，把方言帧解析成 thinking / content / meta / usage。</li>
 * </ol>
 *
 * <p><b>凭证只需要一个 Authorization</b>。实测确认：Cookie、x-hif-leim、x-device-id、
 * x-client-* 全部可以省略，上游不校验（缺 Authorization 才回 40003 INVALID_TOKEN）。
 * 这让「手机号登录后只存一个 token」成为可行方案。
 *
 * <p><b>PoW 是一次性的</b>：同一个 x-ds-pow-response 用第二次会回 40301
 * INVALID_POW_RESPONSE。所以每次请求都要现取挑战现算，没有缓存余地。
 */
public final class DeepSeekWeb {

    public static final String ORIGIN = "https://chat.deepseek.com";
    private static final String CHAT_PATH = "/api/v0/chat/completion";
    private static final String CREATE_SESSION_PATH = "/api/v0/chat_session/create";
    private static final String POW_CHALLENGE_PATH = "/api/v0/chat/create_pow_challenge";
    private static final String USER_PATH = "/api/v0/users/current";

    /** 与网页端一致的 UA。上游对 UA 宽松，但带上更稳。 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 180000;   // 推理模型 thinking 阶段可能很久不出字

    /**
     * 首帧超时：连上了、但上游一个字都不吐，多久算卡死。
     *
     * <p>实测正常首字（思维链）约 1 秒内到；30 秒还没动静基本就是连接僵死了。
     * 不能靠 {@link #READ_TIMEOUT} 兜底 —— 三分钟用户早就以为 App 挂了。
     */
    private static final int FIRST_FRAME_TIMEOUT = 30000;

    /** 看门狗线程池（守护线程，不拖住进程退出）。 */
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ds-first-frame-watchdog");
                    t.setDaemon(true);
                    return t;
                }
            });

    private final String token;
    private volatile String cookie;
    private final String deviceId;
    /** Cookie 发生变化时的回调：桥接层拿它落盘，跨重启接上轮换后的 WAF Cookie。 */
    private final CookieSink cookieSink;

    /** 上游轮换了 Cookie（Set-Cookie）时回调一次，参数是合并后的整串 Cookie。 */
    public interface CookieSink { void onCookies(String cookie); }

    /**
     * 当前正在用的连接，供 {@link #abort()} 从别的线程掐断。
     *
     * <p>为什么要这个：{@code HttpURLConnection} 一旦卡在 {@code read()} 上，
     * 只有读到 {@link #READ_TIMEOUT} 才会醒 —— 三分钟。手机网络一断，
     * 用户看到的就是「一直转圈」。掐断连接能让阻塞的读立刻抛 IOException。
     */
    private volatile HttpURLConnection active;

    public DeepSeekWeb(String token) {
        this(token, "", "", null);
    }

    public DeepSeekWeb(String token, String cookie, String deviceId) {
        this(token, cookie, deviceId, null);
    }

    public DeepSeekWeb(String token, String cookie, String deviceId, CookieSink sink) {
        this.token = token == null ? "" : token.trim();
        this.cookie = cookie == null ? "" : cookie.trim();
        this.deviceId = deviceId == null ? "" : deviceId.trim();
        this.cookieSink = sink;
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    /**
     * 请求头：尽量对齐真实浏览器。
     *
     * <p><b>Cookie 和设备指纹不是可有可无的</b>：只带 Authorization 的请求
     * 在风控看来就是「没有会话的脚本」。实测真实浏览器每一发都带着
     * {@code HWWAFSESID}/{@code smidV2}/{@code ds_session_id} 这串 Cookie
     * 和 {@code x-device-id}。能取到就一律带上，取不到才省略。
     */
    private Map<String, String> headers(String accept) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", accept);
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("User-Agent", UA);
        h.put("Origin", ORIGIN);
        h.put("Referer", ORIGIN + "/");
        /* 浏览器必发的两类头，缺了就是「只抄了 UA 的脚本」：
           ① Client Hints（要与 UA 里的 Chrome/120 自洽）；
           ② Sec-Fetch-*（现代浏览器对每个请求都会自动带，不从代码里写就不会有）。
           这两组都不参与业务校验，但它们是「请求长得像不像浏览器」的免费分。 */
        h.put("sec-ch-ua", "\"Chromium\";v=\"120\", \"Not:A-Brand\";v=\"99\"");
        h.put("sec-ch-ua-mobile", "?1");
        h.put("sec-ch-ua-platform", "\"Android\"");
        h.put("Sec-Fetch-Site", "same-origin");
        h.put("Sec-Fetch-Mode", "cors");
        h.put("Sec-Fetch-Dest", "empty");
        h.put("Cache-Control", "no-cache");
        h.put("Pragma", "no-cache");
        // 网页端始终带这两个客户端标识
        h.put("x-client-platform", "web");
        h.put("x-client-locale", "zh_CN");
        h.put("x-client-timezone-offset", "28800");
        if (!deviceId.isEmpty()) h.put("x-device-id", deviceId);
        // cookie 是 volatile：上游轮换之后，后续请求要立刻用新的那串
        String jar = cookie;
        if (jar != null && !jar.isEmpty()) h.put("Cookie", jar);
        if (!token.isEmpty()) {
            // 容错：用户可能连 "Bearer " 一起粘贴进来，别拼成 "Bearer Bearer x"
            String first = token.split(" ", 2)[0].toLowerCase();
            boolean scheme = token.contains(" ")
                    && (first.equals("bearer") || first.equals("token"));
            h.put("Authorization", scheme ? token : "Bearer " + token);
        }
        return h;
    }

    private HttpURLConnection open(String path, String method, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ORIGIN + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        for (Map.Entry<String, String> e : headers(accept).entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        active = c;
        return c;
    }

    /**
     * 把这次响应里轮换的 Cookie 收下来。
     *
     * <p>真实浏览器每发一次请求都会吸收 {@code Set-Cookie}：WAF 的 {@code HWWAFSESID}
     * 会换、{@code smidV2} 会滚、会话 Cookie 也会续期。以前这里只看响应体、从不看
     * 响应头，于是 App 一直拿着登录那一刻的 Cookie 用到失效——在风控眼里就是
     * 「会话时间线和 cookie 时间线对不上」的客户端。
     *
     * <p>合并规则交给 {@link #mergeCookies}（纯函数，桌面单测直接覆盖）。
     */
    private void harvest(HttpURLConnection c) {
        try {
            List<String> raw = new ArrayList<String>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k == null || !"set-cookie".equalsIgnoreCase(k)) continue;
                List<String> vs = e.getValue();
                if (vs != null) raw.addAll(vs);
            }
            if (raw.isEmpty()) return;
            String merged = mergeCookies(cookie, raw);
            if (merged.equals(cookie == null ? "" : cookie)) return;
            cookie = merged;
            if (cookieSink != null) cookieSink.onCookies(merged);
        } catch (Exception ignored) {
            // 收 Cookie 失败不该影响这次请求本身
        }
    }

    /**
     * 把若干条 {@code Set-Cookie} 合并进现有的 Cookie 串。
     *
     * <p>规则按浏览器的来：同名替换、值为空或 {@code Max-Age<=0} 或 Expires 在 1970
     * 表示删除；其余属性（Path/Domain/HttpOnly/Secure/SameSite）一律忽略——
     * 这是给 Cookie 头用的，不是 CookieJar。保持原有顺序，新名字追加在后面。
     *
     * <p>纯静态、不碰网络，所以桌面单测能直接喂字符串验证。
     */
    static String mergeCookies(String jar, List<String> setCookies) {
        java.util.LinkedHashMap<String, String> map =
                new java.util.LinkedHashMap<String, String>();
        if (jar != null) {
            for (String part : jar.split(";")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                map.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
            }
        }
        if (setCookies != null) {
            for (String one : setCookies) {
                if (one == null) continue;
                // 有些实现把多条 Set-Cookie 用逗号并成一行；Expires 里也有逗号，
                // 所以只在「逗号后面紧跟 name=」时才拆。
                for (String piece : one.split(",(?=\\s*[A-Za-z0-9_\\-]+=)")) {
                    applySetCookie(map, piece);
                }
            }
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            if (b.length() > 0) b.append("; ");
            b.append(e.getKey()).append('=').append(v);
        }
        return b.toString();
    }

    /** 应用一条 Set-Cookie：写入、覆盖或删除。 */
    private static void applySetCookie(Map<String, String> map, String piece) {
        String s = piece == null ? "" : piece.trim();
        if (s.isEmpty()) return;
        String[] parts = s.split(";");
        String pair = parts[0].trim();
        int eq = pair.indexOf('=');
        if (eq <= 0) return;
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (name.isEmpty()) return;
        boolean expired = value.isEmpty();
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim().toLowerCase(java.util.Locale.US);
            if (attr.startsWith("max-age=")) {
                try {
                    if (Long.parseLong(attr.substring(8).trim()) <= 0) expired = true;
                } catch (NumberFormatException ignored) { }
            } else if (attr.startsWith("expires=")) {
                String d = attr.substring(8);
                if (d.contains("1970") || d.contains("1969")) expired = true;
            }
        }
        if (expired) map.remove(name);
        else map.put(name, value);
    }

    /** 从别的线程掐断当前连接：让卡在 read() 上的请求立刻失败，而不是等满超时。 */
    public void abort() {
        HttpURLConnection c = active;
        active = null;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    /** 发一个 JSON 请求并读完整响应（非流式）。 */
    private String postJson(String path, String body) throws Exception {
        HttpURLConnection c = open(path, "POST", "application/json");
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(b.length);
        try {
            OutputStream os = c.getOutputStream();
            try { os.write(b); } finally { os.close(); }
            int code = c.getResponseCode();
            harvest(c);
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally { in.close(); }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** 发一个 GET 请求并读完整响应（非流式）。 */
    private String getJson(String path) throws Exception {
        HttpURLConnection c = open(path, "GET", "application/json");
        try {
            int code = c.getResponseCode();
            harvest(c);
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    /**
     * 校验 token 是不是**真登录态**，返回手机号（未登录返回 null）。
     *
     * <p>为什么必须校验：游客态本来就有 token，而且那个游客 token 建会话也会成功
     * （实测 code=0），所以「能建会话」完全不能证明已登录。唯一可靠的判据是
     * {@code /api/v0/users/current} 里的 {@code mobile_number} —— 登录后才有。
     *
     * <p>顺带解决另一个坑：这个响应里回显的 {@code token} 字段才是裸 token，
     * 而 localStorage 的 {@code userToken} 是 {@code {"value":"..."}} 的 JSON 包装。
     */
    public String currentMobile() throws Exception {
        String raw = getJson(USER_PATH);
        JSONObject j = new JSONObject(raw);
        if (j.optInt("code", -1) != 0) return null;
        JSONObject biz = j.optJSONObject("data") == null ? null
                : j.optJSONObject("data").optJSONObject("biz_data");
        if (biz == null) return null;
        String mobile = biz.optString("mobile_number", "");
        return mobile.isEmpty() ? null : mobile;
    }

    /**
     * 建会话，返回 chat_session_id。
     *
     * <p>实测：随机 UUID 当 chat_session_id 会被上游拒绝，必须先真的 create 一个。
     */
    public String createSession() throws Exception {
        String raw = postJson(CREATE_SESSION_PATH, "{}");
        JSONObject j = new JSONObject(raw);
        int code = j.optInt("code", -1);
        if (code != 0) {
            throw new DeepSeekException(code, j.optString("msg", "建会话失败"));
        }
        JSONObject biz = j.optJSONObject("data") == null ? null
                : j.optJSONObject("data").optJSONObject("biz_data");
        JSONObject sess = biz == null ? null : biz.optJSONObject("chat_session");
        String sid = sess == null ? null : sess.optString("id", null);
        if (sid == null || sid.isEmpty()) {
            throw new DeepSeekException(-1, "建会话响应里没有 chat_session.id");
        }
        return sid;
    }

    /**
     * 取挑战 → 本地暴破 → 拼出 x-ds-pow-response 头值（base64(JSON)）。
     *
     * <p>难度通常 144000，Java 单线程每秒约 37 万次，最坏半秒、平均 0.2 秒。
     */
    public String powHeader() throws Exception {
        String raw = postJson(POW_CHALLENGE_PATH, "{\"target_path\":\"" + CHAT_PATH + "\"}");
        JSONObject j = new JSONObject(raw);
        if (j.optInt("code", -1) != 0) {
            throw new DeepSeekException(j.optInt("code", -1), j.optString("msg", "取挑战失败"));
        }
        JSONObject biz = j.getJSONObject("data").getJSONObject("biz_data");
        JSONObject ch = biz.getJSONObject("challenge");

        String algorithm = ch.getString("algorithm");
        String challenge = ch.getString("challenge");
        String salt = ch.getString("salt");
        long expireAt = ch.getLong("expire_at");
        int difficulty = ch.getInt("difficulty");
        String signature = ch.getString("signature");

        String prefix = salt + "_" + expireAt + "_";
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        long answer = DeepSeekHashV1.solve(prefix.getBytes(StandardCharsets.UTF_8),
                challenge, difficulty, workers);
        if (answer < 0) {
            throw new DeepSeekException(-1, "PoW 在难度窗口内未找到解");
        }

        // 键序对齐网页端：algorithm, challenge, salt, answer, signature, target_path
        JSONObject payload = new JSONObject();
        payload.put("algorithm", algorithm);
        payload.put("challenge", challenge);
        payload.put("salt", salt);
        payload.put("answer", answer);
        payload.put("signature", signature);
        payload.put("target_path", CHAT_PATH);
        byte[] raw2 = payload.toString().getBytes(StandardCharsets.UTF_8);
        return base64(raw2);
    }

    /**
     * 标准 Base64（带 padding）。
     *
     * <p>不能用 {@code java.util.Base64}：它要 API 26，而本应用 minSdk 是 24；
     * 也不能用 {@code android.util.Base64}，那样桌面单测（DeepSeekLiveTest）跑不起来。
     * 二十行自己写，两边都能用。
     */
    static String base64(byte[] data) {
        final char[] T =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder b = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            b.append(T[b0 >> 2]);
            b.append(T[((b0 & 0x03) << 4) | (b1 >> 4)]);
            b.append(i + 1 < data.length ? T[((b1 & 0x0F) << 2) | (b2 >> 6)] : '=');
            b.append(i + 2 < data.length ? T[b2 & 0x3F] : '=');
        }
        return b.toString();
    }

    /** 流式回调：把方言帧翻译成人能用的四种事件。 */
    public interface Listener {
        /** 思维链增量（reasoning_content 的来源）。 */
        void onThinking(String text);
        /** 正文增量。 */
        void onContent(String text);
        /** 本轮 assistant 消息 id，下一轮的 parent_message_id。 */
        void onMeta(long responseMessageId);
        /** 上游上报的 token 用量（可能只有 total）。 */
        void onUsage(int totalTokens);
        /** 流正常结束。 */
        void onDone();
    }

    /**
     * 发起一次流式对话，边收边回调。
     *
     * @param prompt          拍平后的纯文本提示词
     * @param sessionId       {@link #createSession()} 拿到的 id
     * @param parentMessageId 上一轮的 response_message_id；首轮传 null
     * @param thinking        是否开思维链（答题场景建议关，省时间）
     */
    public void stream(String prompt, String sessionId, Long parentMessageId,
                       boolean thinking, Listener listener) throws Exception {
        String pow = powHeader();

        JSONObject body = new JSONObject();
        body.put("prompt", prompt);
        body.put("chat_session_id", sessionId);
        body.put("parent_message_id", parentMessageId == null
                ? JSONObject.NULL : parentMessageId);
        body.put("model_type", JSONObject.NULL);
        body.put("ref_file_ids", new JSONArray());
        body.put("thinking_enabled", thinking);
        body.put("search_enabled", false);
        body.put("action", JSONObject.NULL);

        HttpURLConnection c = open(CHAT_PATH, "POST", "text/event-stream");
        c.setRequestProperty("x-ds-pow-response", pow);
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(b.length);
        try {
            OutputStream os = c.getOutputStream();
            try { os.write(b); } finally { os.close(); }
            int code = c.getResponseCode();
            harvest(c);
            String ct = c.getContentType();

            // 非 event-stream：上游把业务错误当 JSON 返回（40300/40301/40003…）
            if (code >= 400 || ct == null || !ct.contains("event-stream")) {
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String err = readAll(in);
                throw businessError(err, code);
            }

            /* 首帧看门狗：连接建好了、但上游一个字节都不吐，是最常见的「卡住」。
               光靠 READ_TIMEOUT（180s）等不起，这里 30s 没动静就自己掐断。 */
            final AtomicBoolean silent = new AtomicBoolean(true);
            final ScheduledFuture<?> guard = WATCHDOG.schedule(new Runnable() {
                @Override public void run() {
                    if (silent.get()) abort();
                }
            }, FIRST_FRAME_TIMEOUT, TimeUnit.MILLISECONDS);
            try {
                new FrameParser(new Listener() {
                    @Override public void onThinking(String t) { silent.set(false); listener.onThinking(t); }
                    @Override public void onContent(String t) { silent.set(false); listener.onContent(t); }
                    @Override public void onMeta(long rid) { silent.set(false); listener.onMeta(rid); }
                    @Override public void onUsage(int total) { silent.set(false); listener.onUsage(total); }
                    @Override public void onDone() { silent.set(false); listener.onDone(); }
                }).consume(c.getInputStream());
            } catch (Exception e) {
                // 被看门狗掐断的连接抛的是 "Socket closed" 之类，翻成人话
                if (silent.get()) {
                    throw new DeepSeekException(CODE_SILENT,
                            "上游 " + (FIRST_FRAME_TIMEOUT / 1000) + " 秒没有响应，已中断");
                }
                throw e;
            } finally {
                guard.cancel(false);
            }
        } finally {
            c.disconnect();
        }
    }

    /** 把 {"code":40301,"msg":"INVALID_POW_RESPONSE"} 翻成人话。 */
    private static DeepSeekException businessError(String raw, int httpCode) {
        try {
            JSONObject j = new JSONObject(raw);
            int code = j.optInt("code", httpCode);
            String msg = j.optString("msg", "");
            String hint;
            switch (code) {
                case 40300: hint = "上游要求 PoW 头，本次没带上"; break;
                case 40301: hint = "PoW 校验失败（每个挑战只能用一次，不能复用）"; break;
                case 40003: hint = "登录态失效，请重新登录"; break;
                default: hint = msg;
            }
            return new DeepSeekException(code, hint);
        } catch (Exception e) {
            return blockedOrCode(raw, httpCode);
        }
    }

    /**
     * 响应体不是 JSON 时怎么定性。
     *
     * <p>上游正常只回 JSON；一旦回 HTML / WAF 页 / 人机校验，说明这一路已经被盯上了。
     * 这种错误必须单独标成 {@link #CODE_BLOCKED}，让上层按「上游在说别问了」处理
     * （熔断停手），而不是当成一次普通失败去重试——重试就是继续撞。
     */
    private static DeepSeekException blockedOrCode(String raw, int httpCode) {
        String t = raw == null ? "" : raw.trim();
        String low = t.toLowerCase(java.util.Locale.US);
        boolean html = t.startsWith("<") || low.contains("<html") || low.contains("<!doctype")
                || low.contains("captcha") || low.contains("waf");
        if (html) {
            return new DeepSeekException(CODE_BLOCKED,
                    "上游回了人机校验 / 拦截页（HTTP " + httpCode + "），先停手，别硬撞");
        }
        return new DeepSeekException(httpCode, t.length() > 200 ? t.substring(0, 200) : t);
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

    /**
     * SSE 方言状态机 —— 移植自 web2api-mini 的 {@code _token_frames}。
     *
     * <p>上游把「思考」和「正文」混在同一条流里，靠先下发的 fragment 类型声明
     * 后续裸 token 属于谁。所以必须记住 {@code fragType}，否则思维链会混进正文。
     *
     * <p>另一个坑：同一个 fragment 会既发增量又发累积全文。按 id 记账、只取新增尾部，
     * 否则正文会变成「收到收到收到…」。
     */
    static final class FrameParser {
        private final Listener listener;
        private String fragType = "content";          // "thinking" | "content"
        private final Map<Long, String> acc = new HashMap<Long, String>();
        private Long lastFid = null;

        FrameParser(Listener listener) { this.listener = listener; }

        void consume(InputStream in) throws Exception {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String event = "";
            List<String> data = new ArrayList<String>();
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) {
                    dispatch(event, data);
                    data.clear();
                    event = "";
                    continue;
                }
                if (s.startsWith(":")) continue;               // 注释/心跳
                if (s.startsWith("event:")) {
                    event = s.substring(6).trim();
                } else if (s.startsWith("data:")) {
                    String p = s.substring(5);
                    if (p.startsWith(" ")) p = p.substring(1);
                    data.add(p);
                }
            }
            dispatch(event, data);                             // 末尾无空行时补发
        }

        private void dispatch(String event, List<String> data) {
            if (data.isEmpty()) return;
            String joined = join(data);
            if ("done".equals(event) || "close".equals(event) || "[DONE]".equals(joined)) {
                listener.onDone();
                return;
            }
            if (joined.isEmpty()) return;
            JSONObject o;
            try {
                o = new JSONObject(joined);
            } catch (Exception e) {
                return;                                        // 非 JSON 帧（纯文本心跳）丢弃
            }

            if (!o.isNull("response_message_id")) {
                listener.onMeta(o.optLong("response_message_id", 0));
            }

            String p = o.optString("p", "");
            Object v = o.opt("v");

            // A：v 是 dict —— 整块响应元数据，可能带 fragments
            if (v instanceof JSONObject) {
                JSONObject respMeta = ((JSONObject) v).optJSONObject("response");
                if (respMeta == null) respMeta = (JSONObject) v;
                emitFragments(respMeta.optJSONArray("fragments"));
                return;
            }
            // B：p == response/fragments 且 v 是数组 —— 真实站点下发正文的主通路
            if ("response/fragments".equals(p) && v instanceof JSONArray) {
                emitFragments((JSONArray) v);
                return;
            }
            // C：状态帧
            if ("response/status".equals(p) || "quasi_status".equals(p)) {
                String sv = String.valueOf(v);
                if ("FINISHED".equals(sv) || "DONE".equals(sv)) listener.onDone();
                return;
            }
            // D：用量帧
            if (v instanceof JSONArray
                    && ("response".equals(p) || "response/accumulated_token_usage".equals(p))) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String key = it.optString("p", "");
                    if (!key.endsWith("token_usage")) continue;
                    if (it.opt("v") instanceof Integer) {
                        listener.onUsage(it.optInt("v"));
                    } else if (it.opt("v") instanceof JSONObject) {
                        JSONObject u = it.optJSONObject("v");
                        if (u.has("total_tokens")) listener.onUsage(u.optInt("total_tokens"));
                    }
                }
                return;
            }
            if (p.endsWith("accumulated_token_usage") && v instanceof Integer) {
                listener.onUsage((Integer) v);
                return;
            }
            // E：文本增量
            if (v instanceof String) {
                String sv = (String) v;
                if (sv.isEmpty()) return;
                if (p.isEmpty()) {
                    emit(lastFid, fragType, sv);
                } else if (p.endsWith("/content")) {
                    Long fid = fragId(p);
                    emit(fid != null ? fid : lastFid, fragType, sv);
                }
            }
        }

        private void emitFragments(JSONArray frags) {
            if (frags == null) return;
            for (int i = 0; i < frags.length(); i++) {
                JSONObject f = frags.optJSONObject(i);
                if (f == null) continue;
                long fid = f.optLong("id", -1);
                String kind = "THINK".equals(f.optString("type")) ? "thinking" : "content";
                fragType = kind;
                if (fid >= 0) lastFid = fid;
                emit(fid >= 0 ? fid : lastFid, kind, f.optString("content", ""));
            }
        }

        /** 按 fragment 记账，只吐新增尾部（全量帧会被去重）。 */
        private void emit(Long fid, String kind, String content) {
            if (content == null || content.isEmpty()) return;
            if (fid == null) {
                push(kind, content);                       // 无 fragment 上下文：无法去重，原样放行
                return;
            }
            String prev = acc.get(fid);
            if (prev == null) prev = "";
            String delta;
            if (content.startsWith(prev)) {
                delta = content.substring(prev.length());
            } else if (prev.isEmpty()) {
                delta = content;
            } else {
                delta = content;                           // 对不上前缀（上游重写）当纯增量
            }
            if (delta.isEmpty()) return;
            acc.put(fid, prev + delta);
            push(kind, delta);
        }

        private void push(String kind, String text) {
            if ("thinking".equals(kind)) listener.onThinking(text);
            else listener.onContent(text);
        }

        /** 从 "response/fragments/<id>/content" 取 id；"-1" 表示最后一个。 */
        private Long fragId(String path) {
            String[] parts = path.split("/");
            if (parts.length >= 3 && "response".equals(parts[0]) && "fragments".equals(parts[1])) {
                String raw = parts[2];
                if ("-1".equals(raw)) return lastFid;
                try { return Long.parseLong(raw); } catch (NumberFormatException e) { return lastFid; }
            }
            return null;
        }

        private static String join(List<String> parts) {
            if (parts.size() == 1) return parts.get(0);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) b.append('\n');
                b.append(parts.get(i));
            }
            return b.toString();
        }
    }

    /** 上游业务错误（带 code，便于上层区分「登录失效」和「其他」）。 */
    public static final class DeepSeekException extends Exception {
        public final int code;
        public DeepSeekException(int code, String msg) {
            super(msg);
            this.code = code;
        }
        /** 登录态失效（需要重新登录）。 */
        public boolean isAuthError() { return code == 40003; }

        /**
         * 这个错误值不值得重试。
         *
         * <p>值得：首帧超时（{@link #CODE_SILENT}）、各种负的本地错误（连不上、读不到）、
         * 网关类 5xx，以及 {@code 40301}——挑战是一次性的，重试会**重新取一个新挑战**，
         * 所以它真的能好；不重试反而让用户白等一次。
         *
         * <p>不值得：拦截页（{@link #CODE_BLOCKED}，重试=继续撞）、缺 PoW 头、
         * 登录失效、参数错。
         */
        public boolean isRetryable() {
            if (code == CODE_BLOCKED) return false;
            if (code == 40301) return true;
            if (code == 502 || code == 503 || code == 504) return true;
            return code < 0;
        }
    }

    /** 首帧看门狗掐断连接时用的错误码。 */
    public static final int CODE_SILENT = -2;

    /** 上游回了拦截页 / 人机校验（不是 JSON）。与 {@code RiskGuard.BLOCKED_CODE} 同值。 */
    public static final int CODE_BLOCKED = -5;
}
