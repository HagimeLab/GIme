package com.fan.shuatiku;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * DeepSeek 手机号登录页 —— App 内唯一需要人操作一次的步骤。
 *
 * <p>为什么必须开一个真浏览器：登录接口要求人机验证令牌（数美 / hCaptcha），
 * 它们由真人在真实浏览器里产生，程序无法也不该伪造。所以这里只做
 * 「登录结果的搬运」：加载真实登录页 → 用户输手机号、过验证、填短信码
 * → 页面自己把 token 写进 localStorage → 我们读出来存进 App 私有配置。
 *
 * <p>只取 {@code userToken} 一个值就够：上游只强校验 {@code Authorization}，
 * Cookie、x-device-id、x-client-* 这些它并不校验——但真实浏览器每个请求都会带，
 * 所以照样补齐（少了它们，请求形状就不像浏览器）。
 *
 * <p>登录成功的判据：**问上游**，不是看本地 token 有没有变。
 *
 * <p>这里踩过一个坑：最初拿「localStorage 里的 userToken 值变了」当判据。
 * 那在「用户本来就登录着」时永远不成立 —— token 早就是真的了，不会变，
 * 于是页面停在已登录状态、App 一直等下去（用户实测反馈的 bug）。
 *
 * <p>现在改成分两种情况，都以上游 {@code /users/current} 有没有手机号为准：
 * <ul>
 *   <li><b>进来时就已经登录</b>：App 里已存 token 就先问上游，是登录态就立刻存下并返回；</li>
 *   <li><b>进来时是游客态</b>：上游说没有手机号，才提示用户去登录，之后靠两条路发现
 *       状态变化 —— 轮询 localStorage，以及直接观察登录页自己发出的 API 请求里的
 *       {@code Authorization}（后者不依赖页面把 token 存到哪个键，登完必触发）。</li>
 * </ul>
 */
public class DeepSeekLoginActivity extends Activity {

    static final String PREFS = "ds";
    static final String PREF_TOKEN = "token";
    static final String PREF_MOBILE = "mobile";
    static final String PREF_LOGIN_AT = "login_at";
    /** 登录时的整串 Cookie。真实浏览器每次请求都带它，不带就成了“没有会话的程序”。 */
    static final String PREF_COOKIE = "cookie";
    /** x-device-id：设备指纹。2026-10-21 抓真实请求核对：页面还带 x-client-version / x-client-bundle-id / x-device-model，但不带 x-hif-leim。 */
    static final String PREF_DEVICE_ID = "device_id";

    private static final String SIGN_IN_URL = DeepSeekWeb.ORIGIN + "/sign_in";

    private WebView web;
    private TextView hint;
    /**
     * 已确认「不是登录态」的 token —— 同一个游客 token 别每 400ms 打一次上游。
     * 登录成功后 token 会变，自然就绕开这个记录了。
     */
    private String rejected;
    private boolean captured;
    private boolean destroyed;
    /** 是否已经给过「去登录」的提示（避免每 400ms 重设一次文本）。 */
    private boolean seenLoginHint;
    /** 登录页自己发出的 API 请求里的 Cookie / x-device-id（比 CookieManager 更新）。 */
    private volatile String pageCookie = "";
    private volatile String pageDevice = "";
    /** 页面流量里见过的 token：同一个只验一次，避免每个请求都问上游。 */
    private final Set<String> pageSeen = new HashSet<>();
    /** 页面流量里被一次性判否的 token，只给它一次延迟重试（登录态可能刚写入）。 */
    private final Set<String> pageRetried = new HashSet<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("登录 DeepSeek");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFEDEEF1);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        /* 普通手机 Chrome 的 UA：WebView 默认 UA 带 "; wv"，不少站点据此拦人机验证。
           与原生请求共用同一份常量，否则同一账号会在两条链路上报出两种浏览器。 */
        s.setUserAgentString(DeepSeekWeb.UA);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                probe();
            }

            /**
             * 登录页的 API 请求本身带着 Authorization / Cookie。直接看它比读
             * localStorage 可靠：手机号登录成功后，页面一定会马上发一个带新
             * token 的请求，不管它把 token 存在哪个键、什么时候写进去。
             */
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest req) {
                try {
                    String host = req.getUrl().getHost();
                    if (host != null && host.endsWith("deepseek.com")) {
                        Map<String, String> h = req.getRequestHeaders();
                        String auth = header(h, "authorization");
                        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                            final String tok = auth.substring(7).trim();
                            final String ck = header(h, "cookie");
                            final String dev = header(h, "x-device-id");
                            if (!tok.isEmpty()) {
                                runOnUiThread(() -> onPageToken(tok, ck, dev));
                            }
                        }
                    }
                } catch (Exception ignored) { }
                return null;              // 只看，不改页面流量
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("正在打开登录页…");
        hint.setTextColor(Color.parseColor("#6B7078"));
        hint.setTextSize(13);
        hint.setPadding(dp(18), dp(10), dp(18), dp(10));
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        web.loadUrl(SIGN_IN_URL);
        /* 关键：**先立刻探一次**。
           如果用户本来就登录着，localStorage 里已经是真 token，
           这里问一次上游就能立刻确认并返回，用户根本看不到这个页面。
           旧逻辑拿「token 值变了」当判据，这种情形永远等不到，一直卡住。 */
        poll(0);
        /* 再补一条更可靠的路：App 自己已经存着 token 时，先用它问一次上游。
           WebView 的 localStorage 可能被清过、或者页面还没把 token 写回来；
           只看 localStorage 就会出现「网页已经登录着，App 却不自动返回」。 */
        String savedToken = unwrap(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TOKEN, ""));
        if (!savedToken.isEmpty()) verify(savedToken);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** 请求头名大小写不固定，按小写查。 */
    private static String header(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /**
     * 页面发了一次带 token 的请求：先收下这一刻的 Cookie / 设备指纹，
     * 再用这个 token 问上游。登录成功后的第一个请求就能触发自动返回。
     */
    private void onPageToken(String token, String cookie, String device) {
        if (destroyed) return;
        if (cookie != null && !cookie.isEmpty()) pageCookie = cookie;
        if (device != null && !device.isEmpty()) pageDevice = device;
        if (captured) {
            /* 上一个校验还没结束。别把这个 token 记成「已见过」，
               否则等校验失败后它就再也不会被验一次。只等一次。 */
            if (!pageSeen.contains(token)) {
                web.postDelayed(() -> {
                    if (!destroyed && !captured && pageSeen.add(token)) verify(token);
                }, 800);
            }
            return;
        }
        if (!pageSeen.add(token)) {
            /* 这个 token 被上游明确判否过一次。登录态可能刚好还在写入，
               给它一次延迟重试，避免「刚登完就判死、token 不再变」的卡死。 */
            if (token.equals(rejected) && pageRetried.add(token)) {
                web.postDelayed(() -> {
                    if (destroyed || captured) return;
                    if (token.equals(rejected)) {
                        rejected = null;
                        verify(token);
                    }
                }, 2500);
            }
            return;
        }
        android.util.Log.d("GImeLogin", "page token len=" + token.length()
                + " cookie=" + pageCookie.length() + " device=" + pageDevice.length());
        verify(token);
    }

    /**
     * 探一次登录态。
     *
     * <p>不再比较 token 有没有变（那在「本来就登录着」时永远不成立），
     * 而是**读到 token 就直接问上游**：{@code /users/current} 有手机号就是登录态。
     * 游客 token 也能建会话，所以只有这一步能区分真假登录。
     */
    private void probe() {
        if (captured) return;
        readToken(tok -> {
            if (captured || tok.isEmpty()) return;
            // 问过上游且明确不是登录态的同一个 token，跳过（登录后 token 会变）
            if (tok.equals(rejected)) {
                if (!seenLoginHint) {
                    seenLoginHint = true;
                    setHint("请用手机号登录。登录成功后会自动返回，不用手动关闭。");
                }
                return;
            }
            verify(tok);
        });
    }

    /**
     * 拿 token 去上游问「这是谁」。
     *
     * <p>三种结果分开处理，别混成一个：
     * <ul>
     *   <li>有手机号 → 真登录态，存下并返回；</li>
     *   <li>上游明确说不认（40003 等）→ 记进 rejected，继续等用户登录；</li>
     *   <li>网络错误 → 不记 rejected（网络恢复了同一个 token 还有效），
     *       提示检查网络后继续探。</li>
     * </ul>
     */
    private void verify(String token) {
        final String t = token.startsWith("Bearer ") ? token.substring(7).trim() : token;
        captured = true;                 // 先挡住并发探测
        setHint("正在确认登录状态…");
        /* 自检优先用裸 token（token 才是身份凭证）。刚登录完的那一瞬间 Cookie 可能
           还是旧的/游客的，带着旧 Cookie 去问会被判失败，而 token 之后不会再变，
           于是永远等不到自动返回。裸 token 失败再带 Cookie/设备指纹兜底。 */
        String ck = pageCookie;
        if (ck.isEmpty()) ck = cookieHeader();
        if (ck.isEmpty()) ck = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_COOKIE, "");
        final String verifyCookie = ck == null ? "" : ck;
        final String verifyDevice = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_DEVICE_ID, "");
        android.util.Log.d("GImeLogin", "verify start token=" + t.length()
                + " cookie=" + verifyCookie.length());
        new Thread(new Runnable() {
            @Override public void run() {
                String mobile = null;
                String err = null;
                boolean rejectedByUpstream = false;
                try {
                    mobile = mobileOf(t, verifyCookie, verifyDevice);
                    if (mobile == null) {
                        // 能连上但没手机号：游客态或已过期，这个 token 不值得再问
                        rejectedByUpstream = true;
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String m = mobile;
                final String e2 = err;
                final boolean rej = rejectedByUpstream;
                android.util.Log.d("GImeLogin", "verify result mobile=" + (m != null)
                        + " err=" + e2 + " rejected=" + rej);
                runOnUiThread(() -> onVerified(t, m, e2, rej));
            }
        }, "ds-verify").start();
    }

    /** 裸 token 先问一次；没手机号或请求失败，再带 Cookie / 设备指纹兜底。 */
    private static String mobileOf(String token, String cookie, String device) throws Exception {
        try {
            String m = new DeepSeekWeb(token).currentMobile();
            if (m != null) return m;
        } catch (Exception ignored) { }
        return new DeepSeekWeb(token, cookie, device).currentMobile();
    }

    private void onVerified(String token, String mobile, String err, boolean rejectedByUpstream) {
        if (mobile != null) {
            /* 顺手把 Cookie 和设备指纹一起收下。
               真实浏览器每个 API 请求都带 Cookie + x-device-id，
               只带 Authorization 的流量在风控眼里就是「没有会话的程序」。 */
            final String cookie = pageCookie.isEmpty() ? cookieHeader() : pageCookie;
            readDeviceId(deviceId -> finishSave(token, mobile, cookie,
                    pageDevice.isEmpty() ? deviceId : pageDevice));
            return;
        }
        // 没成功：退回继续等
        captured = false;
        if (rejectedByUpstream) {
            rejected = token;            // 同一个 token 不再重复问
            if (!seenLoginHint) {
                seenLoginHint = true;
                setHint("请用手机号登录。登录成功后会自动返回，不用手动关闭。");
            }
        } else {
            // 网络问题：别把 token 记进 rejected，网络好了它可能还是有效的
            setHint(err != null
                    ? "还连不上 DeepSeek（" + err + "），请检查网络后重试"
                    : "还没登录成功。请完成手机号登录（游客状态下不算）");
        }
    }

    /** 从 WebView 的 CookieManager 取整串 Cookie（含 httpOnly 的 ds_session_id）。 */
    private String cookieHeader() {
        try {
            String c = android.webkit.CookieManager.getInstance().getCookie(DeepSeekWeb.ORIGIN);
            if (c == null) return "";
            // 去掉空项；保留原顺序，上游按它认会话
            StringBuilder b = new StringBuilder();
            for (String part : c.split(";")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                if (b.length() > 0) b.append("; ");
                b.append(s);
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 设备 id：网页端存在 localStorage 的 {@code deepseek-device-id:chat}，
     * 也是它拼进 {@code x-device-id} 头的值。取不到就不带这个头。
     */
    private void readDeviceId(final DeviceSink sink) {
        if (web == null) { sink.got(""); return; }
        web.evaluateJavascript(
                "(function(){try{"
                + "var v=localStorage.getItem('deepseek-device-id:chat')||'';"
                + "return v"
                + "}catch(e){return ''}})()",
                value -> sink.got(unquote(value)));
    }

    private interface DeviceSink { void got(String deviceId); }

    /** 真正落盘并收尾（Cookie 与设备 id 都是异步取到的，所以单独一步）。 */
    private void finishSave(String token, String mobile, String cookie, String deviceId) {
        if (destroyed) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        /* 已存 token 快速自检时 WebView 可能还没加载完，Cookie/设备 id 会取到空；
           空值不能覆盖掉上一次登录存下的那一份。 */
        String oldCookie = p.getString(PREF_COOKIE, "");
        String oldDevice = p.getString(PREF_DEVICE_ID, "");
        p.edit()
                .putString(PREF_TOKEN, token)
                .putString(PREF_MOBILE, mobile)
                .putLong(PREF_LOGIN_AT, System.currentTimeMillis())
                .putString(PREF_COOKIE, cookie == null || cookie.isEmpty() ? oldCookie : cookie)
                .putString(PREF_DEVICE_ID, deviceId == null || deviceId.isEmpty() ? oldDevice : deviceId)
                .apply();
        setHint("登录成功（" + mobile + "），正在返回…");
        setResult(RESULT_OK);
        if (web != null) web.postDelayed(this::finish, 350);
    }

    /**
     * 兜底轮询：前端路由切换未必触发 onPageFinished，而且用户收验证码要时间。
     *
     * <p>上限 5 分钟；页面销毁时由 destroyed 收口。
     */
    private void poll(final int round) {
        if (captured || destroyed || round > 750) return;
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (captured || destroyed || web == null) return;
                probe();
                poll(round + 1);
            }
        }, 400);
    }

    private interface TokenSink { void got(String token); }

    /**
     * 读 localStorage 的 userToken 并**解包**。
     *
     * <p>网页端存的不是裸 token，而是 {@code {"value":"cnd...","__version":"0"}}。
     * 直接把整串当 token 用会得到 401 INVALID_TOKEN（实测踩过）。所以在 JS 里就
     * 取 {@code .value}；万一格式变了，再在 Java 侧兜一次 JSON 解析。
     */
    private void readToken(final TokenSink sink) {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){try{"
                + "var raw=localStorage.getItem('userToken');"
                + "if(!raw)return '';"
                + "var v=raw;"
                + "try{var o=JSON.parse(raw);if(o&&typeof o.value==='string')v=o.value}catch(e){}"
                + "return v||''"
                + "}catch(e){return ''}})()",
                value -> sink.got(unwrap(unquote(value))));
    }

    /** 兜底解包：万一 JS 侧解析失败，Java 侧再试一次 {"value": "..."}。 */
    private static String unwrap(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (!t.startsWith("{")) return t;
        try {
            org.json.JSONObject o = new org.json.JSONObject(t);
            String v = o.optString("value", "");
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return t;
        }
    }

    /** evaluateJavascript 回传的是 JS 字面量：字符串带引号，null 是 "null"。 */
    private static String unquote(String js) {
        if (js == null) return "";
        String s = js.trim();
        if (s.equals("null") || s.equals("undefined")) return "";
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
            // 处理 \" 与 \\ 两种转义
            s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s.trim();
    }

    private void setHint(String text) {
        if (hint != null) hint.setText(text);
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;                 // 停掉轮询链
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
