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
    private static final String PREF_COOKIE = DeepSeekLoginActivity.PREF_COOKIE;
    private static final String PREF_DEVICE_ID = DeepSeekLoginActivity.PREF_DEVICE_ID;

    /** 会话缓存：页面传的 user 字段 → (chat_session_id, 上一轮 response_message_id)。 */
    private static final class Turn {
        final String sessionId;
        final Long parent;
        final long at;
        Turn(String sessionId, Long parent, long at) {
            this.sessionId = sessionId;
            this.parent = parent;
            this.at = at;
        }
    }

    /**
     * 是否复用上游会话：**开**。
     *
     * <p>以前是关的（怕历史重复），代价是**每问一句就在上游新建一个会话**——
     * 真人不会每说一句话换一个会话，这在风控眼里就是脚本。现在按会话键复用，
     * 并且每个会话只发「最新一轮」的 prompt（历史交给上游会话链维护），
     * 所以既不会重复，也不再刷 create_session。
     *
     * <p>页面「新建会话」会换一个新的 user 值，自然就落到新会话上。
     */
    private static final boolean REUSE_SESSION = true;

    /** 上游会话闲置多久就丢掉、下次重建（上游 ttl 约 3 天，这里保守取 1 小时）。 */
    private static final long SESSION_TTL_MS = 60 * 60 * 1000L;

    /**
     * 两次上游请求的最小间隔：真人打字没那么快。
     *
     * <p>再叠加一段随机抖动（{@link #JITTER_MS}）：固定 1.2 秒一次的节奏本身就是
     * 最好认的机器特征——人手打字的间隔从来不是等长的。
     */
    private static final long MIN_GAP_MS = 1200;
    /** 间隔抖动的上限（毫秒）：实际间隔 = MIN_GAP + 随机 0..JITTER。 */
    private static final int JITTER_MS = 900;
    /** 一分钟内最多发几次上游请求（建会话与对话各算一次，PoW 挑战随对话一起走）。 */
    private static final int MAX_PER_MINUTE = 12;

    /** 护栏：熔断、每日预算、答案缓存。 */
    private final RiskGuard guard = new RiskGuard();
    private static final String PREF_GUARD_DAY = "guard_day";
    private static final String PREF_GUARD_USED = "guard_used";
    /** 答案缓存落盘的上限：只留最近用过的，别让正文把私有配置撑大。 */
    private static final String PREF_GUARD_CACHE = "guard_cache";
    private static final int CACHE_BYTES_MAX = 256 * 1024;
    /** 熔断状态的落盘键：重启一次就把惩罚清空，等于白送一个绕过闸门的按钮。 */
    private static final String PREF_GUARD_FREEZE = "guard_freeze_until";
    private static final String PREF_GUARD_STRIKES = "guard_strikes";
    private static final String PREF_GUARD_RISK_AT = "guard_risk_at";
    /** 累计复用次数：跨重启保留，用户能在设置页看到缓存到底省了多少次。 */
    private static final String PREF_GUARD_HITS = "guard_hits";
    /** 出站节流的上次发送时刻与 60 秒窗口：重启后仍要守同一份额度。 */
    private static final String PREF_SEND_LAST = "send_last_at";
    private static final String PREF_SEND_WINDOW = "send_window";

    /** 网络类失败最多重试几次（总共最多跑 MAX_RETRY+1 次）。 */
    private static final int MAX_RETRY = 2;
    /** 退避基数：第 n 次重试前等 n 倍（1s、2s），不要一断就狂打上游。 */
    private static final int RETRY_DELAY_MS = 1000;

    /** 正在跑的请求 → 它的 DeepSeekWeb，用来支持「取消」。 */
    private final ConcurrentHashMap<String, DeepSeekWeb> cancelOf =
            new ConcurrentHashMap<String, DeepSeekWeb>();

    /**
     * 出站节流：串行化 + 抖动间隔 + 每分钟上限。
     *
     * <p>封号的直接诱因就是「短时间内连续打上游」。真人在网页上打字有间隔，而且
     * **间隔不等长**——固定 1.2 秒一次的节奏本身就是机器特征。所以这里两发之间取
     * {@code MIN_GAP_MS}+随机 0~{@link #JITTER_MS}，任意 60 秒窗口内不超过
     * {@link #MAX_PER_MINUTE} 发。
     *
     * <p>窗口与「上次发送时刻」都落盘：否则杀掉 App 重开就是一台全新的限速器，
     * 这是最容易被拿来自动化的一个洞。
     */
    private static final class Throttle {
        private final java.util.ArrayDeque<Long> window = new java.util.ArrayDeque<Long>();
        private final java.util.Random rnd = new java.util.Random();
        private final Runnable onChange;
        private long last = 0;
        /** 下一次要等多久：每次成功发送后重新掷一次，别让间隔成为常量。 */
        private long nextGap = MIN_GAP_MS;

        Throttle(Runnable onChange) { this.onChange = onChange; }

        /**
         * 取一个发送许可；不够就睡够。
         *
         * @param waitHint 第一次需要等待时回调，把「要等多久」告诉页面
         * @return true 表示已等到（永远为 true，留个返回值便于将来改成可拒绝）
         */
        synchronized boolean acquire(WaitHint waitHint) throws InterruptedException {
            boolean hinted = false;
            while (true) {
                long now = System.currentTimeMillis();
                trim(now);
                long wait = 0;
                if (last > 0 && now - last < nextGap) wait = nextGap - (now - last);
                if (window.size() >= MAX_PER_MINUTE) {
                    long oldest = window.peekFirst();
                    long until = oldest + 60_000L - now;
                    if (until > wait) wait = until;
                }
                if (wait <= 0) {
                    window.addLast(now);
                    last = now;
                    nextGap = MIN_GAP_MS + rnd.nextInt(JITTER_MS + 1);
                    fire();
                    return true;
                }
                if (!hinted && waitHint != null && wait >= 1000) {
                    hinted = true;
                    waitHint.waiting(wait);
                }
                Thread.sleep(Math.min(wait, 5_000L));
            }
        }

        private void trim(long now) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000L) window.pollFirst();
        }

        synchronized long lastAt() { return last; }

        /** 60 秒窗口的快照（逗号分隔的时间戳），调用方拿去落盘。 */
        synchronized String windowCsv() {
            StringBuilder b = new StringBuilder();
            for (Long t : window) {
                if (b.length() > 0) b.append(',');
                b.append(t);
            }
            return b.toString();
        }

        /** 重启后把窗口接上：跨重启也要守同一分钟额度。 */
        synchronized void restore(long lastAt, String csv) {
            last = Math.max(0, lastAt);
            window.clear();
            if (csv != null && !csv.isEmpty()) {
                for (String one : csv.split(",")) {
                    try {
                        long t = Long.parseLong(one.trim());
                        if (t > 0) window.addLast(t);
                    } catch (NumberFormatException ignored) { }
                }
            }
            trim(System.currentTimeMillis());
        }

        /** 状态变了就让调用方落一次盘（apply()，不挡问答线程）。 */
        private void fire() {
            if (onChange == null) return;
            try { onChange.run(); } catch (Exception ignored) { }
        }
    }

    private interface WaitHint { void waiting(long ms); }

    private final Throttle throttle;

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
        // 每日用量要跨重启保留，否则切一下 App 就能把预算重置掉
        guard.restore(prefs.getString(PREF_GUARD_DAY, ""), prefs.getInt(PREF_GUARD_USED, 0));
        // 熔断与递增惩罚也要跨重启：重启一次就把惩罚清空，等于白送一个绕过闸门的按钮
        guard.restoreFreeze(prefs.getLong(PREF_GUARD_FREEZE, 0),
                prefs.getInt(PREF_GUARD_STRIKES, 0),
                prefs.getLong(PREF_GUARD_RISK_AT, 0));
        guard.restoreHits(prefs.getLong(PREF_GUARD_HITS, 0));
        // 答案缓存也要跨重启：否则「昨天问过这道题」今天还要再打一次上游
        restoreCache();
        // 节流器要能在状态变化时落盘，所以把回调接上再恢复窗口
        throttle = new Throttle(new Runnable() {
            @Override public void run() { persistThrottle(); }
        });
        throttle.restore(prefs.getLong(PREF_SEND_LAST, 0), prefs.getString(PREF_SEND_WINDOW, ""));
    }

    /** 本地日期字符串，用于「今日已用」的换天判断。 */
    private static String today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private void persistUsage() {
        prefs.edit().putString(PREF_GUARD_DAY, guard.day())
                .putInt(PREF_GUARD_USED, guard.used()).apply();
    }

    /** 熔断状态落盘：被警告之后关掉 App 再开，惩罚仍然在。 */
    private void persistFreeze() {
        prefs.edit().putLong(PREF_GUARD_FREEZE, guard.frozenUntil())
                .putInt(PREF_GUARD_STRIKES, guard.strikes())
                .putLong(PREF_GUARD_RISK_AT, guard.lastRiskAt()).apply();
    }

    /** 节流窗口落盘：跨重启仍守同一份额度。 */
    private void persistThrottle() {
        prefs.edit().putLong(PREF_SEND_LAST, throttle.lastAt())
                .putString(PREF_SEND_WINDOW, throttle.windowCsv()).apply();
    }

    private void persistHits() {
        prefs.edit().putLong(PREF_GUARD_HITS, guard.hitCount()).apply();
    }

    /**
     * 把答案缓存写进应用私有配置。
     *
     * <p>按「最旧 → 最新」写盘：恢复时顺序放回，LRU 的淘汰顺序才和内存里一致。
     * 总量超预算就从最旧的一端丢，留下最近用过的那些——缓存是「省一次请求」，
     * 不该反过来把几百 KB 的正文长期压在一个 XML 里。写盘用 apply()，不挡问答线程。
     */
    private void persistCache() {
        try {
            java.util.Map<String, String> snap = guard.snapshot();      // 最旧 → 最新
            java.util.List<java.util.Map.Entry<String, String>> items =
                    new java.util.ArrayList<java.util.Map.Entry<String, String>>(snap.entrySet());
            int[] sizes = new int[items.size()];
            int total = 0;
            for (int i = 0; i < items.size(); i++) {
                sizes[i] = utf8Len(items.get(i).getKey()) + utf8Len(items.get(i).getValue());
                total += sizes[i];
            }
            int from = 0;
            while (from < items.size() && total > CACHE_BYTES_MAX) total -= sizes[from++];
            JSONArray arr = new JSONArray();
            for (int i = from; i < items.size(); i++) {
                arr.put(new JSONArray().put(items.get(i).getKey()).put(items.get(i).getValue()));
            }
            prefs.edit().putString(PREF_GUARD_CACHE, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** 按 UTF-8 字节数记账：中文一个字 3 字节，按字符数算会把 256KB 撑成 700KB。 */
    private static int utf8Len(String s) {
        if (s == null) return 0;
        try {
            return s.getBytes("UTF-8").length;
        } catch (Exception e) {
            return s.length();
        }
    }

    /** 启动时把上次落盘的答案缓存读回来；坏了就当没有，不影响问答。 */
    private void restoreCache() {
        String raw = prefs.getString(PREF_GUARD_CACHE, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            java.util.Map<String, String> saved = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray one = arr.optJSONArray(i);
                if (one == null || one.length() < 2) continue;
                saved.put(one.getString(0), one.getString(1));
            }
            guard.restoreCache(saved);
        } catch (Exception ignored) {
            prefs.edit().remove(PREF_GUARD_CACHE).apply();
        }
    }

    /** 护栏状态（页面用来显示「今日还剩几次」「熔断还剩多久」）。 */
    String guardJson() {
        long now = System.currentTimeMillis();
        String today = today();
        JSONObject o = new JSONObject();
        try {
            o.put("frozen", guard.isFrozen(now));
            o.put("frozenMs", guard.frozenRemainingMs(now));
            o.put("used", guard.touchDay(today));
            o.put("limit", RiskGuard.DAILY_LIMIT);
            o.put("remaining", guard.remaining(today));
            o.put("cacheSize", guard.cacheSize());
            // 「缓存到底省了多少次上游请求」：累计值，跨重启保留
            o.put("cacheHits", guard.hitCount());
            o.put("strikes", guard.strikes());
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** 用户主动解除熔断（strikes 保留：这是上游的账，不该被「再点一次」抹掉）。 */
    void resetGuard() {
        guard.resetFreeze();
        persistFreeze();
    }

    /** 清空答案缓存（连带磁盘上那份）。 */
    void clearCache() {
        guard.cacheClear();
        prefs.edit().remove(PREF_GUARD_CACHE).apply();
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
                .remove(PREF_TOKEN).remove(PREF_MOBILE).remove(PREF_LOGIN_AT)
                .remove(PREF_COOKIE).remove(PREF_DEVICE_ID).apply();
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

        final String today = today();
        long now = System.currentTimeMillis();

        // ① 熔断中：直接拒绝，不发任何请求
        if (guard.isFrozen(now)) {
            long sec = (guard.frozenRemainingMs(now) + 999) / 1000;
            throw new DeepSeekWeb.DeepSeekException(-3,
                    "上游刚有异常，已暂停 " + sec + " 秒（避免继续触发风控）。稍后再试，或点设置切到 API Key。");
        }
        // ② 今日预算用尽
        if (!guard.budgetLeft(today)) {
            throw new DeepSeekWeb.DeepSeekException(-4,
                    "今天已经问了 " + RiskGuard.DAILY_LIMIT + " 次，先歇一歇。"
                            + "需要更多可以把接入方式切到 API Key。");
        }

        final DeepSeekWeb web = new DeepSeekWeb(token, prefs.getString(PREF_COOKIE, ""),
                prefs.getString(PREF_DEVICE_ID, ""), new DeepSeekWeb.CookieSink() {
                    @Override public void onCookies(String cookie) {
                        /* 上游每次响应都可能轮换 WAF 与会话 Cookie。像浏览器一样收下来，
                           下次请求就用新的那串——一直攥着登录那一刻的 Cookie 用到失效，
                           在风控眼里就是「cookie 时间线和会话时间线对不上」的客户端。 */
                        prefs.edit().putString(PREF_COOKIE, cookie).apply();
                    }
                });
        cancelOf.put(id, web);

        /* ③ 答案缓存：键只看「哪道题 + 问的什么 + 这道题被答成什么样」，不含历史。
           刷题场景里同一道题常被反复问，这条能实打实省掉相当一部分请求
           （对手机号登录是不撞风控，对 API Key 是省 token）。
           只在本轮是「用户新提的一问」时查：工具调用的第二发带着工具返回，
           最后一条 user 没变，这时候查缓存会把刚拿到的工具结果整段吞掉。 */
        final boolean freshAsk = DeepSeekClient.isFreshAsk(messages);
        final String askNow = DeepSeekClient.lastUserText(messages);
        final String quesPrint = DeepSeekClient.questionFingerprint(messages);
        final String cacheKey = freshAsk
                ? RiskGuard.cacheKey(quesPrint, askNow, DeepSeekClient.answerState(messages))
                : "";
        if (!cacheKey.isEmpty()) {
            String cached = guard.cacheGet(cacheKey);
            if (cached != null) {
                cancelOf.remove(id);
                persistHits();
                sink.toJs("window.onChatCached(" + quote(id) + ")");
                return cached;
            }
        }
        /* 拿会话：命中就用，过期就重建。
           复用时只发「最新这一轮」，历史由上游会话链负责，
           所以既不会把历史灌两遍，也不用每句都 create_session。 */
        Turn prev = null;
        if (REUSE_SESSION) {
            Turn t = turns.get(convKey);
            if (t != null && System.currentTimeMillis() - t.at < SESSION_TTL_MS) prev = t;
            else if (t != null) turns.remove(convKey);
        }
        String prompt;
        String sid;
        Long parent;
        if (prev != null) {
            sid = prev.sessionId;
            parent = prev.parent;
            prompt = DeepSeekClient.buildPrompt(DeepSeekClient.latestTurn(messages), tools);
        } else {
            throttle.acquire(ms -> sink.toJs("window.onChatThrottle(" + quote(id) + "," + ms + ")"));
            sid = web.createSession();
            parent = null;
            prompt = DeepSeekClient.buildPrompt(messages, tools);
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
                throttle.acquire(ms -> sink.toJs("window.onChatThrottle(" + quote(id) + "," + ms + ")"));
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

                /* 上游表达「别问了」就直接计一次风险并停手，不重试。
                   这是防封的核心：被提醒之后继续重试，等于把警告当耳旁风。 */
                if (e instanceof DeepSeekWeb.DeepSeekException) {
                    DeepSeekWeb.DeepSeekException de = (DeepSeekWeb.DeepSeekException) e;
                    if (RiskGuard.isRisk(de.code, de.getMessage())) {
                        guard.onRisk(System.currentTimeMillis());
                        persistFreeze();
                        cancelOf.remove(id);
                        throw de;
                    }
                }

                if (gotSomething || attempt > MAX_RETRY || !isTransient(e)) throw e;
                // 建会话可能是它先挂的：一并重建，别拿着一个可能已经失效的 sid 重试
                try {
                    throttle.acquire(null);
                    sid = web.createSession();
                    parent = null;
                    turns.remove(convKey);
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
        /* 真出了一轮结果：连续失败计数清零；若「安静了一整天」，递增惩罚也降级。 */
        if (guard.onSuccess(System.currentTimeMillis())) persistFreeze();
        guard.onUsed(today);
        persistUsage();

        if (REUSE_SESSION && respId[0] >= 0) {
            turns.put(convKey, new Turn(sid, respId[0], System.currentTimeMillis()));
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
        /* 只有「用户新问 + 没有工具调用」的最终回答才进缓存：
           工具轮次是中间态，缓存它会让下次直接卡在调工具那一步。 */
        if (freshAsk && parsed.toolCalls.length() == 0
                && parsed.text != null && !parsed.text.isEmpty()) {
            guard.cachePut(cacheKey, resp.toString());
            persistCache();
        }
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
