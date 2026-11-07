package com.fan.shuatiku;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 绑定哔哩哔哩：App 里唯一需要真人操作一次的步骤。
 *
 * <p>为什么只能这么登：B 站没有面向第三方 App 的 OAuth ——「绑定账号」在现实中只有
 * 「借用网页登录产生的 SESSDATA」这一条路。人机验证与验证码必须由真人在真实网页里
 * 完成，所以这里加载真登录页，只搬运行登录结果，不碰它的表单与验证。
 *
 * <p>登录判据<b>问上游</b>（{@code /x/web-interface/nav} 给得出 mid 才算数），
 * 不看「本地 cookie 变没变」：这是 {@link DeepSeekLoginActivity} 已经踩过的坑 ——
 * 用户进来时就处于登录态时，cookie 不会变，只看变化就会永远等不下去。
 *
 * <p>取到的是整串 cookie（含 httpOnly 的 SESSDATA），落盘前一律过 {@link SecurePrefs}。
 * 之后所有 B 站请求都由原生带着它发，页面一个字节都看不到。
 */
public class BiliLoginActivity extends Activity {

    static final String PREFS = "bili";
    /** 整串 cookie：真实浏览器每个请求都带，缺了就成了「没有会话的程序」。 */
    static final String PREF_COOKIE = "cookie";
    static final String PREF_MID = "mid";
    static final String PREF_UNAME = "uname";
    static final String PREF_FACE = "face";
    static final String PREF_LOGIN_AT = "login_at";

    /**
     * 登录页地址。
     *
     * <p>用 PC passport（实测 200，页面里同时有「扫码登录」和「手机号登录」两个入口）：
     * 二维码由它自己画，App 因此不必为一个编码库引第三方依赖，也不用把
     * {@code qrcode_key} 交给任何外部渲染服务。屏幕窄，所以开了缩放。
     */
    private static final String LOGIN_URL = "https://passport.bilibili.com/login";
    /** 探测间隔与上限。1.5 秒一次、5 分钟封顶：再密就是拿上游接口当轮询器练手。 */
    private static final long POLL_MS = 1500L;
    private static final int MAX_ROUNDS = 200;

    private WebView web;
    private TextView hint;
    private volatile boolean captured;
    private volatile boolean destroyed;
    /** 上一次探测用的 cookie；一模一样就别再问一次上游。 */
    private volatile String lastProbed = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        CronetNet.install(this);
        DeepSeekWeb.setDevice(android.os.Build.MODEL, android.os.Build.VERSION.RELEASE);
        setTitle("绑定哔哩哔哩");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFEDEEF1);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(DeepSeekLoginActivity.loginUa(this));
        /* PC 登录页压在 360dp 的屏上会挤，允许双指缩放；不显示缩放控件（点击会抖） */
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("正在打开哔哩哔哩登录页…");
        hint.setTextColor(Color.parseColor("#6B7078"));
        hint.setTextSize(13);
        hint.setPadding(dp(18), dp(10), dp(18), dp(10));
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        web.loadUrl(LOGIN_URL);
        /* 进来时浏览器里可能已经是登录态（在系统浏览器登过 B 站）：
           先立刻探一次，是的话用户根本不用碰这个页面。 */
        probe();
        poll(0);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void poll(final int round) {
        if (captured || destroyed || round >= MAX_ROUNDS || web == null) return;
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (captured || destroyed || web == null) return;
                probe();
                poll(round + 1);
            }
        }, POLL_MS);
    }

    /** 拿当前 cookie 去上游问一次「这算登录了吗」。 */
    private void probe() {
        if (captured) return;
        final String cookie = cookieHeader();
        if (cookie.isEmpty() || cookie.equals(lastProbed)) return;
        lastProbed = cookie;
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject nav = null;
                try {
                    nav = BiliWeb.nav(cookie);
                } catch (Exception ignored) {
                    // 连不上或还没登录：都不算失败，下一轮再问
                }
                final long mid = nav == null ? 0L : nav.optLong("mid");
                if (mid <= 0) {
                    lastProbed = "";     // 这串 cookie 不认，允许它变了以后再问
                    return;
                }
                final String uname = nav.optString("uname");
                final String face = nav.optString("face");
                runOnUiThread(new Runnable() {
                    @Override public void run() { finishSave(cookie, mid, uname, face); }
                });
            }
        }, "bili-verify").start();
    }

    /** 从 WebView 的 CookieManager 取整串 cookie（SESSDATA 是 httpOnly，只有这里读得到）。 */
    private String cookieHeader() {
        try {
            String c = CookieManager.getInstance().getCookie(BiliWeb.SITE);
            if (c == null) return "";
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

    private void finishSave(String cookie, long mid, String uname, String face) {
        if (destroyed || captured) return;
        captured = true;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit()
                .putString(PREF_COOKIE, SecurePrefs.enc(cookie))
                .putLong(PREF_MID, mid)
                .putString(PREF_UNAME, SecurePrefs.enc(uname))
                .putString(PREF_FACE, SecurePrefs.enc(face))
                .putLong(PREF_LOGIN_AT, System.currentTimeMillis())
                .apply();
        /* 落盘由 WebView 异步做；不等它的话，进程被系统回收时 cookie 会丢在内存里。 */
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) { }
        setHint("已绑定 " + (uname.isEmpty() ? String.valueOf(mid) : uname) + "，正在返回…");
        setResult(RESULT_OK);
        if (web != null) web.postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, 350);
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
        destroyed = true;
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
