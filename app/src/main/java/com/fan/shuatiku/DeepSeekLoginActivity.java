package com.fan.shuatiku;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * DeepSeek 手机号登录页 —— App 内唯一需要人操作一次的步骤。
 *
 * <p>为什么必须开一个真浏览器：登录接口要求人机验证令牌（数美 / hCaptcha），
 * 它们由真人在真实浏览器里产生，程序无法也不该伪造。所以这里只做
 * 「登录结果的搬运」：加载真实登录页 → 用户输手机号、过验证、填短信码
 * → 页面自己把 token 写进 localStorage → 我们读出来存进 App 私有配置。
 *
 * <p>只取 {@code userToken} 一个值就够：实测确认上游只强校验 {@code Authorization}，
 * Cookie、x-hif-leim、x-device-id、x-client-* 全部可以省略。
 *
 * <p>登录成功的判据：**问上游**，不是看本地 token 有没有变。
 *
 * <p>这里踩过一个坑：最初拿「localStorage 里的 userToken 值变了」当判据。
 * 那在「用户本来就登录着」时永远不成立 —— token 早就是真的了，不会变，
 * 于是页面停在已登录状态、App 一直等下去（用户实测反馈的 bug）。
 *
 * <p>现在改成分两种情况，都以上游 {@code /users/current} 有没有手机号为准：
 * <ul>
 *   <li><b>进来时就已经登录</b>：第一次探测就读到 token，直接问上游，是登录态
 *       就立刻存下并返回，不要求用户再操作；</li>
 *   <li><b>进来时是游客态</b>：上游说没有手机号，才提示用户去登录，
 *       之后靠定时轮询发现状态变化（用户登完不用手动关页面）。</li>
 * </ul>
 */
public class DeepSeekLoginActivity extends Activity {

    static final String PREFS = "ds";
    static final String PREF_TOKEN = "token";
    static final String PREF_MOBILE = "mobile";
    static final String PREF_LOGIN_AT = "login_at";

    private static final String SIGN_IN_URL = DeepSeekWeb.ORIGIN + "/sign_in";

    /** 普通手机 Chrome 的 UA：WebView 默认 UA 带 "; wv"，不少站点据此拦人机验证。 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

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
        s.setUserAgentString(UA);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                probe();
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
           如果用户本来就登录着（网页版已经登录过），localStorage 里已经是真 token，
           这里问一次上游就能立刻确认并返回，用户根本看不到这个页面。
           旧逻辑拿「token 值变了」当判据，这种情形永远等不到，一直卡住。 */
        poll(0);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
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
        new Thread(new Runnable() {
            @Override public void run() {
                String mobile = null;
                String err = null;
                boolean rejectedByUpstream = false;
                try {
                    mobile = new DeepSeekWeb(t).currentMobile();
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
                runOnUiThread(() -> onVerified(t, m, e2, rej));
            }
        }, "ds-verify").start();
    }

    private void onVerified(String token, String mobile, String err, boolean rejectedByUpstream) {
        if (mobile != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_TOKEN, token)
                    .putString(PREF_MOBILE, mobile)
                    .putLong(PREF_LOGIN_AT, System.currentTimeMillis())
                    .apply();
            setHint("登录成功（" + mobile + "），正在返回…");
            setResult(RESULT_OK);
            if (web != null) web.postDelayed(this::finish, 350);
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
