package com.fan.shuatiku;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WebView 外壳 + 导入通道。
 * 选文件、读文件、存题库文件都在这一层；把正文切成题目在页面 JS 里做。
 */
public class MainActivity extends Activity {

    private static final int PICK = 4711;
    /** 导入格式举例：assets 里那一份是唯一来源，页面看的和下载的都是它。 */
    private static final String SAMPLE_ASSET = "import-sample.md";
    private static final String SAMPLE_NAME = "GIme导入格式举例.md";
    private WebView web;
    private File bankDir;
    private Updater updater;

    /** 页面报告 ready 之前收到的导入请求排队等它，ready 后按序消费，每条只消费一次。 */
    private final ArrayDeque<Uri> pendingImports = new ArrayDeque<Uri>();
    private volatile boolean pageReady;
    private boolean importing;

    private static final String PREFS = "ui";
    private static final String PREF_LIGHT = "light";
    private static final String PREFS_MCP = "mcp";
    private static final String PREF_MCP_TOKEN = "token";

    private volatile McpServer mcp;
    private volatile String mcpError = "";
    private long mcpSeq;
    private final java.util.concurrent.ConcurrentHashMap<String, McpWait> mcpWait =
            new java.util.concurrent.ConcurrentHashMap<String, McpWait>();

    /** 大肥鱼的后端：走 App 内置的 DeepSeek 反代，不需要 API Key。 */
    private DeepSeekBridge dsBridge;

    private static final class McpWait {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        volatile String response;
    }

    /**
     * 关掉整棵子树的触摸反馈。setHapticFeedbackEnabled 不是继承属性，
     * 而 Chrome 的内部视图是延迟挂上来的，所以 onCreate 里还要再 post 一次。
     */
    private static void killTouchHaptics(View v) {
        v.setHapticFeedbackEnabled(false);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) killTouchHaptics(g.getChildAt(i));
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        bankDir = new File(getFilesDir(), "banks");
        if (!bankDir.isDirectory()) bankDir.mkdirs();
        BankStore.cleanup(bankDir);      // 清掉上次被打断留下的 .tmp/.bak

        web = new WebView(this);
        // 只在 debug 构建里开调试口。Release 开着它等于把整个 WebView 连同
        // localStorage 里的题库交出去——用 adb 连上就能读写。
        if (BuildConfig.DEBUG) {
            try { WebView.setWebContentsDebuggingEnabled(true); } catch (Exception ignored) {}
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        /* 首帧之前 WebView 自己会刷一层底色。写死浅色等于深色主题用户每次冷启动
           都先被闪一下浅纸底——启动页「不出现白屏」的要求在原生这一侧同样成立才算数。
           上一次运行用的是哪套主题，themeHint 会存下来，这里照它取。 */
        boolean lastLight = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_LIGHT, true);
        web.setBackgroundColor(lastLight ? 0xFFEDEEF1 : 0xFF17181B);
        /* 「设置」里翻开关时那一下震动不是 App 发的：页面侧 navigator.vibrate 一次都没被调用，
           它是框架替应用发的触摸反馈（dumpsys vibrator 里是 Usage=TOUCH / Virtual Key - Press）。
           只能逐个 View 关掉，见 killTouchHaptics。 */
        web.setHapticFeedbackEnabled(false);
        killTouchHaptics(web);
        web.post(new Runnable() {
            @Override public void run() { killTouchHaptics(web); }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        /* 这台 ROM 的 WebView 在没挂 WebViewClient 时，页面内导航会退化成
           startActivity(file:///android_asset/index.html)，被 StrictMode 判成
           FileUriExposed 直接崩掉进程（10-19 用 CDP 触发一次 location.reload() 就复现）。
           页面里没有任何 <a href>，挂一个默认 client 只关这条崩溃路径，不改变现有行为。 */
        web.setWebViewClient(new WebViewClient());
        updater = new Updater(new Updater.Sink() {
            @Override public void toJs(String script) { js(script); }
            @Override public Activity activity() { return MainActivity.this; }
        });
        /* 大肥鱼改走 App 内置通道：页面发请求 → 这里直连 DeepSeek 网页协议。
           用户只需要用官方手机号登录一次，不再需要 API Key 或外部反代服务。 */
        dsBridge = new DeepSeekBridge(this, new DeepSeekBridge.Sink() {
            @Override public void toJs(String script) { js(script); }
        });
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        edgeToEdge();

        /* 冷启动就带着文件时不能马上读：WebView 还没把 index.html 跑起来，
           evaluateJavascript 会落在 document 上，onImportText 根本不存在，文件就丢了。
           先入队，等页面 ready 再消费。 */
        enqueueImport(intentOf(getIntent()));
    }

    /**
     * 让 WebView 铺满整块屏幕：不这么做时导航栏是一块不透明的白条，
     * 页面底部会被它盖住一截（.tabs 正好落在那条带子上）。
     * 系统栏改成透明，实际高度再通过 onApplyWindowInsets 回灌给页面用。
     */
    private void edgeToEdge() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(0x00000000);
        window.setNavigationBarColor(0x00000000);
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        decor.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                sendInsets(insets);
                return insets;
            }
        });
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                sendInsets(insets);
                return insets;
            }
        });
        decor.post(new Runnable() {
            @Override public void run() { decor.requestApplyInsets(); }
        });
    }

    private void sendInsets(WindowInsets insets) {
        if (insets == null || web == null) return;
        final int top = insets.getSystemWindowInsetTop();
        final int bottom = insets.getSystemWindowInsetBottom();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                js("window.__insets&&window.__insets(" + top + "," + bottom + ")");
            }
        });
    }

    /** 浅色主题要把系统栏图标改成深色，否则透明状态栏压在浅灰底上看不清。 */
    private void applyBarAppearance(final boolean light) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                View decor = getWindow().getDecorView();
                int vis = decor.getSystemUiVisibility();
                int bits = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (light) bits |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 27) {
                    bits |= light ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
                }
                decor.setSystemUiVisibility(bits);
                web.setBackgroundColor(light ? 0xFFEDEEF1 : 0xFF17181B);
            }
        });
    }

    /**
     * 返回键先交给页面：弹层 > 全屏页 > 答题页，都没得收才退出 App。
     * 不能直接 super，否则弹层开着时一次返回就把整个应用退掉了。
     */
    @Override
    public void onBackPressed() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("window.onBack?window.onBack():null", new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if ("true".equals(value)) return;
                finish();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeIn(intent);
    }

    /** 微信里「用其他应用打开 → 刷题」会把文件以 VIEW/SEND 递过来。 */
    private void takeIn(final Intent intent) {
        Uri uri = intentOf(intent);
        if (uri == null) return;
        enqueueImport(uri);
        /* 同一条 intent 只处理一次：旋转屏幕会重新走到这里，重复读会把同一个题库导两遍 */
        intent.setAction(null);
        setIntent(intent);
    }

    /** 从 VIEW/SEND 的 intent 里取出文件 URI；不是这两类就返回 null。 */
    private static Uri intentOf(Intent intent) {
        if (intent == null) return null;
        String act = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(act) && !Intent.ACTION_SEND.equals(act)) return null;
        Uri uri = intent.getData();
        if (uri == null) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return uri;
    }

    private void enqueueImport(final Uri uri) {
        /* 从桌面图标启动时没有文件，intentOf 会返回 null；ArrayDeque 拒绝 null，
           直接 add 会在 onCreate 里把 App 炸掉。 */
        if (uri == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                pendingImports.add(uri);
                pumpImports();
            }
        });
    }

    /** 页面 ready 且没有正在跑的导入时才取下一条。 */
    private void pumpImports() {
        if (!pageReady || importing || pendingImports.isEmpty()) return;
        final Uri uri = pendingImports.poll();
        if (uri == null) return;
        importing = true;
        startImport(uri);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // 有些提供器不给持久授权，本次读取仍然可用
        }
        enqueueImport(uri);
    }

    private void startImport(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = displayName(uri);
                try {
                    js("window.importStage&&window.importStage(\"read\")");
                    InputStream in;
                    try {
                        in = getContentResolver().openInputStream(uri);
                    } catch (SecurityException se) {
                        /* 文件提供方把权限收回去了（常见于微信/网盘的临时授权过期）。
                           原来会把 "com.fan.shuatiku has no access to content://..." 直接甩给用户，
                           那是给开发者看的，不是给人看的。 */
                        throw new Exception("没有读取这个文件的权限，请重新选择一次该文件");
                    }
                    if (in == null) throw new Exception("打不开这个文件");
                    String text;
                    try {
                        js("window.importStage&&window.importStage(\"extract\")");
                        text = TextExtract.extract(MainActivity.this, in, name);
                    } finally {
                        in.close();
                    }
                    if (text.trim().isEmpty()) throw new Exception("没读到文字内容");
                    js("window.onImportText&&window.onImportText(" + quote(name) + "," + quote(text) + ")");
                } catch (final Exception e) {
                    js("window.onImportError&&window.onImportError(" + quote(readable(e)) + ")");
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            importing = false;
                            pumpImports();
                        }
                    });
                }
            }
        }).start();
    }

    /** 把异常翻成用户能看懂的一句话；认得的类型才翻译，其余保留原文以免丢失线索。 */
    private static String readable(Exception e) {
        if (e instanceof TextExtract.Reject) return String.valueOf(e.getMessage());
        if (e instanceof java.io.FileNotFoundException) return "找不到这个文件，可能已被移动或删除";
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty() || msg.startsWith("java.") || msg.contains("content://")) {
            return "读取文件失败，请重试或换一个文件";
        }
        return msg;
    }

    private String displayName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名文件" : last;
    }

    private void js(String script) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(script, null);
            }
        });
    }

    private static String quote(String s) {
        try {
            return JSONObject.quote(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    /* ---------------- MCP 服务 ----------------
       HTTP 只绑 127.0.0.1；口令存在 SharedPreferences 里。
       服务跟着进程走：页面就绪就开，Activity 销毁（应用关掉）就关，
       没有开关也不落「开/关」状态——后台常驻一个没人管的监听端口没有意义。
       工具分发在页面 JS（mcpDispatch），这里把请求推进 WebView、等它回包。 */

    private synchronized void mcpStart() {
        if (mcp != null && mcp.isRunning()) return;
        if (web == null || !pageReady) return;
        android.content.SharedPreferences p = getSharedPreferences(PREFS_MCP, MODE_PRIVATE);
        String token = p.getString(PREF_MCP_TOKEN, "");
        if (token == null || token.isEmpty()) {
            token = McpServer.randomHex(24);
            p.edit().putString(PREF_MCP_TOKEN, token).apply();
        }
        McpServer s = new McpServer(McpServer.PORT, token, new McpServer.Handler() {
            @Override public String handle(String requestJson) { return mcpHandle(requestJson); }
        });
        if (s.start()) {
            mcp = s;
            mcpError = "";
            /* 页面不再提供口令入口：要接电脑上的 agent，就从这行日志取。
               adb logcat -s GImeMCP  （Android 上别的应用读不到别的应用的日志） */
            android.util.Log.i("GImeMCP", "http://127.0.0.1:" + McpServer.PORT + "/mcp token=" + token);
        } else {
            mcp = null;
            mcpError = s.error();
        }
    }

    private synchronized String mcpStatusJson() {
        mcpStart();
        String token = getSharedPreferences(PREFS_MCP, MODE_PRIVATE).getString(PREF_MCP_TOKEN, "");
        boolean running = mcp != null && mcp.isRunning();
        try {
            return new JSONObject()
                    .put("enabled", true)
                    .put("running", running)
                    .put("port", McpServer.PORT)
                    .put("token", token == null ? "" : token)
                    .put("error", running ? "" : (mcpError == null ? "" : mcpError))
                    .toString();
        } catch (Exception e) {
            return "{\"enabled\":true,\"running\":false,\"port\":" + McpServer.PORT
                    + ",\"token\":\"\",\"error\":\"status failed\"}";
        }
    }

    /** HTTP 线程调用；把请求塞给页面并等回包，页面通过 Android.mcpReply 交回。 */
    private String mcpHandle(String json) {
        if (web == null || !pageReady) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32002,\"message\":\"page not ready\"}}";
        }
        final String id;
        synchronized (this) { id = "mcp" + (++mcpSeq); }
        McpWait w = new McpWait();
        mcpWait.put(id, w);
        js("window.__mcpHandle(" + quote(id) + "," + quote(json) + ")");
        try {
            w.latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        mcpWait.remove(id);
        return w.response == null
                ? "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"no response within 20s\"}}"
                : w.response;
    }

    @Override
    protected void onDestroy() {
        synchronized (this) {
            if (mcp != null) { mcp.stop(); mcp = null; }
        }
        if (dsBridge != null) { dsBridge.shutdown(); dsBridge = null; }
        super.onDestroy();
    }

    private class Bridge {

        /** 页面把脚本跑完、事件函数都挂上之后调这里，之后才开始放行排队的导入。 */
        @JavascriptInterface
        public void pageReady() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    pageReady = true;
                    pumpImports();
                    mcpStart();
                }
            });
        }

        /** MCP 服务的运行状态（没有开关：服务跟着进程生命周期走）。 */
        @JavascriptInterface
        public String mcpStatus() {
            return mcpStatusJson();
        }

        /** 页面把工具执行结果交回 HTTP 线程。 */
        @JavascriptInterface
        public void mcpReply(String id, String json) {
            McpWait w = mcpWait.get(id);
            if (w == null) return;
            w.response = json == null ? "" : json;
            w.latch.countDown();
        }

        /** 设置页复制地址 / 口令。 */
        @JavascriptInterface
        public boolean copyText(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null) return false;
                cm.setPrimaryClip(ClipData.newPlainText("GIme", text == null ? "" : text));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * 大肥鱼对话的 HTTP 出口。
         * 页面是 file:// 源，WebView 里直接 fetch 外部 API 会被 CORS 拦；
         * 这里用原生 HTTP，结果异步回抛给页面（window.onChatApi）。
         */
        @JavascriptInterface
        public void chatApi(final String id, final String url, final String body, final String key) {
            /* 内置通道：页面把接口地址填成 builtin://deepseek 就走到这里。
               这条路不需要 API Key —— 用的是 App 里存的 DeepSeek 登录态。 */
            if (url != null && url.startsWith("builtin://")) {
                if (dsBridge == null) {
                    js("window.onChatApi(" + quote(id) + ",502,"
                            + quote("{\"error\":{\"message\":\"内置通道未就绪\"}}") + ")");
                    return;
                }
                dsBridge.ask(id, body);
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    int code = 0;
                    String text = "";
                    HttpURLConnection c = null;
                    try {
                        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                            throw new IllegalArgumentException("接口地址要以 http(s):// 开头");
                        }
                        /* 外部 OpenAI 兼容接口也走流式：边收边把增量推给页面，
                           不再是「转圈半天、然后蹦出一大坨」。 */
                        final String[] finish = {"stop"};
                        final StringBuilder content = new StringBuilder();
                        final StringBuilder reasoning = new StringBuilder();
                        try {
                            finish[0] = OpenAiStream.run(url, key, body, new OpenAiStream.Listener() {
                                @Override public void onContent(String d) {
                                    content.append(d);
                                    js("window.onChatStream(" + quote(id) + ",\"text\"," + quote(d) + ")");
                                }
                                @Override public void onReasoning(String d) {
                                    reasoning.append(d);
                                    js("window.onChatStream(" + quote(id) + ",\"think\"," + quote(d) + ")");
                                }
                                @Override public void onFinish(String reason) { finish[0] = reason; }
                            });
                        } catch (OpenAiStream.ApiException ae) {
                            /* 上游明确报错：按它的状态码回，页面能显示可读原因 */
                            code = ae.code > 0 ? ae.code : 502;
                            text = ae.getMessage();
                            js("window.onChatApi(" + quote(id) + "," + code + "," + quote(
                                    "{\"error\":{\"message\":" + quote(text) + "}}") + ")");
                            return;
                        }

                        // 收完拼成标准 OpenAI 非流式回包交给页面（页面两条路径都能吃）
                        JSONObject msg = new JSONObject();
                        msg.put("role", "assistant");
                        msg.put("content", content.toString());
                        if (reasoning.length() > 0) msg.put("reasoning_content", reasoning.toString());
                        JSONObject choice = new JSONObject();
                        choice.put("index", 0);
                        choice.put("message", msg);
                        choice.put("finish_reason", finish[0]);
                        JSONObject resp = new JSONObject();
                        resp.put("id", "chatcmpl-" + Long.toHexString(System.nanoTime()));
                        resp.put("object", "chat.completion");
                        resp.put("created", System.currentTimeMillis() / 1000);
                        resp.put("model", "external");
                        resp.put("choices", new JSONArray().put(choice));
                        text = resp.toString();
                        code = 200;
                    } catch (Exception e) {
                        code = 0;
                        text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    final int fc = code;
                    final String ft = text;
                    js("window.onChatApi(" + quote(id) + "," + fc + "," + quote(ft) + ")");
                }
            }, "gime-chat").start();
        }

        /** 大肥鱼的登录态：页面据此决定显示「登录」还是「已登录」。 */
        @JavascriptInterface
        public String dsStatus() {
            return DeepSeekBridge.statusJson(MainActivity.this);
        }

        /** 打开手机号登录页。 */
        @JavascriptInterface
        public void dsLogin() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (dsBridge != null) dsBridge.openLogin();
                }
            });
        }

        /** 退出登录：清掉本地 token。 */
        @JavascriptInterface
        public void dsLogout() {
            DeepSeekBridge.clear(MainActivity.this);
        }

        /** 取消一个还在跑的大肥鱼请求（掐断连接，不用等到超时）。 */
        @JavascriptInterface
        public void dsCancel(String id) {
            if (dsBridge != null) dsBridge.cancel(id);
        }

        /** 护栏状态：熔断剩余时间、今日已用/上限、缓存条数。页面用它显示配额。 */
        @JavascriptInterface
        public String dsGuard() {
            return dsBridge == null ? "{}" : dsBridge.guardJson();
        }

        /** 用户看完提示后主动解除熔断（「我知道了，继续」）。 */
        @JavascriptInterface
        public void dsGuardReset() {
            if (dsBridge != null) dsBridge.resetGuard();
        }

        /** 清空答案缓存（题库改过、或想要新答案时用）。 */
        @JavascriptInterface
        public void dsClearCache() {
            if (dsBridge != null) dsBridge.clearCache();
        }

        /** 页面换肤时同步系统栏图标深浅和窗口底色。 */
        @JavascriptInterface
        public void themeHint(final boolean light) {
            applyBarAppearance(light);
            /* 只多存一份：底色本身 applyBarAppearance 里已经在 UI 线程上设过了。
               这份存的目的是下次冷启动——首帧之前 WebView 得知道用哪套纸色。 */
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_LIGHT, light).apply();
        }

        /** 当前版本号，页面拿来显示，也拿来和 Release 的 tag 比对。 */
        @JavascriptInterface
        public String appVersion() {
            return Updater.versionOf(MainActivity.this);
        }

        @JavascriptInterface
        public void checkUpdate() {
            updater.check(Updater.versionOf(MainActivity.this));
        }

        @JavascriptInterface
        public void downloadUpdate(String url) {
            updater.download(url);
        }

        /** 页面传多个下载源时走这里；逐个试，第一个通的就是它。 */
        @JavascriptInterface
        public void downloadUpdateFrom(String urlsJson) {
            java.util.List<String> list = new java.util.ArrayList<String>();
            try {
                JSONArray arr = new JSONArray(urlsJson);
                for (int i = 0; i < arr.length(); i++) {
                    String u = arr.optString(i, "");
                    if (!u.isEmpty()) list.add(u);
                }
            } catch (Exception ignored) {
            }
            updater.download(list);
        }

        /** 没给「安装未知应用」授权时，页面点「去设置」走这里。 */
        @JavascriptInterface
        public void openInstallPermission() {
            final Activity act = MainActivity.this;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + act.getPackageName())));
                    } catch (Exception e) {
                        try { act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)); }
                        catch (Exception ignored) { }
                    }
                }
            });
        }

        /** 下载域名连不上时，页面让用户去发布页手动取安装包。 */
        @JavascriptInterface
        public void openUrl(final String url) {
            if (url == null || url.isEmpty()) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        js("window.toast&&window.toast(\"这台机器上没有可用的浏览器\")");
                    }
                }
            });
        }

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "text/plain", "text/markdown"});
                    try {
                        startActivityForResult(i, PICK);
                    } catch (Exception e) {
                        js("window.onImportError&&window.onImportError(\"这台机器上没有可用的文件选择器\")");
                    }
                }
            });
        }

        /** 已导入的题库原文，启动时一次性读回。 */
        @JavascriptInterface
        public String loadBanks() {
            JSONArray out = new JSONArray();
            File[] files = bankDir.listFiles();
            if (files == null) return out.toString();
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    byte[] raw = new byte[(int) f.length()];
                    int off = 0;
                    FileInputStream in = new FileInputStream(f);
                    try {
                        while (off < raw.length) {
                            int n = in.read(raw, off, raw.length - off);
                            if (n <= 0) break;
                            off += n;
                        }
                    } finally {
                        in.close();
                    }
                    out.put(new JSONObject(new String(raw, 0, off, "UTF-8")));
                } catch (Exception ignored) {
                    // 坏文件跳过，别让它连累整个 App 启动
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public boolean saveBank(String id, String json) {
            try {
                return BankStore.save(bankDir, id, json.getBytes("UTF-8"));
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean deleteBank(String id) {
            File target = new File(bankDir, BankStore.safe(id) + ".json");
            return !target.exists() || target.delete();
        }

        /** 导入格式举例：读随包带的那份示例文件。页面展示它，也用它写下载件，两边同一来源。 */
        private byte[] sampleBytes() throws Exception {
            InputStream in = getAssets().open(SAMPLE_ASSET);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        }

        @JavascriptInterface
        public String importSample() {
            try {
                return new String(sampleBytes(), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        /**
         * 把举例文件存成用户能打开、能改、再导回来的 .md。返回能给用户看的位置，失败返回空串。
         * Android 10 以上走 MediaStore 的下载集合（不需要任何权限）；8、9 写到 App 自己的
         * 外部下载目录——那里同样不用申请权限，文件管理器也进得去。
         */
        @JavascriptInterface
        public String saveImportSample() {
            try {
                byte[] data = sampleBytes();
                if (data.length == 0) return "";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, SAMPLE_NAME);
                    v.put(MediaStore.Downloads.MIME_TYPE, "text/markdown");
                    Uri uri = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "";
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) return "";
                    try {
                        os.write(data);
                    } finally {
                        os.close();
                    }
                    /* 同名第二次保存时系统会自动加「(1)」，回读真实文件名再报给用户，
                       否则提示会说一个下载列表里根本不存在的名字。 */
                    String shown = SAMPLE_NAME;
                    Cursor c = getContentResolver().query(uri,
                            new String[] { MediaStore.MediaColumns.DISPLAY_NAME }, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst() && !c.isNull(0)) shown = c.getString(0);
                        } finally {
                            c.close();
                        }
                    }
                    return "系统「下载」目录里的 " + shown;
                }
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return "";
                File f = new File(dir, SAMPLE_NAME);
                FileOutputStream out = new FileOutputStream(f);
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
                return f.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }
    }
}
