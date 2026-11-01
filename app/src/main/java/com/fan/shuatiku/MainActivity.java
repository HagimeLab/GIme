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
    /** 大肥鱼的附件：同一个选择器、同一条抽取路径，只是结果交给对话而不是直接入库。 */
    private static final int PICK_CHAT = 4712;
    /** 通知权限（Android 13+ 运行时申请）。 */
    private static final int REQ_NOTIFY = 4713;
    /** 导入格式举例：assets 里那一份是唯一来源，页面看的和下载的都是它。 */
    private static final String SAMPLE_ASSET = "import-sample.md";
    private static final String SAMPLE_NAME = "GIme导入格式举例.md";
    private WebView web;
    private File bankDir;
    private Updater updater;

    /** 一条待处理文件：直接导入 / 交给大肥鱼当附件 / 外部打开（由页面先问用户走哪条）。 */
    private static final class PendingFile {
        static final int KIND_IMPORT = 0, KIND_CHAT = 1, KIND_ASK = 2;
        final Uri uri;
        final int kind;
        PendingFile(Uri uri, int kind) { this.uri = uri; this.kind = kind; }
    }

    /** 页面报告 ready 之前收到的文件请求排队等它，ready 后按序消费，每条只消费一次。 */
    private final ArrayDeque<PendingFile> pendingImports = new ArrayDeque<PendingFile>();
    private volatile boolean pageReady;
    private boolean importing;
    /* 最后一次量到的系统栏高度（物理像素）。页面重载时 document 连同 window.__insets
       一起没掉，而 Activity 不会重新走一遍 onApplyWindowInsets——不在 pageReady 时
       补灌一次，整页安全区就归零：底部 tab 被导航栏盖住，整屏层的头部压进状态栏。 */
    private int insetTopPx, insetBottomPx;
    /** 用户刚去系统里改过通知权限，回来时通知页面刷新一次状态。 */
    private boolean remindCheckPending;

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

    /** 流式增量的合并出口；一次性回调不走它，仍然直接 js()。 */
    private ChatEmitter chatEmit;

    /** 方案库。提醒、MCP 计划工具、页面方案页读的都是这一份。 */
    private com.fan.shuatiku.agent.PlanStore planStore;

    /** 记忆库：长期画像 + 会话摘要。 */
    private com.fan.shuatiku.agent.MemoryStore memoryStore;

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
        CronetNet.install(this);
        DeepSeekWeb.setDevice(android.os.Build.MODEL, android.os.Build.VERSION.RELEASE);
        bankDir = new File(getFilesDir(), "banks");
        if (!bankDir.isDirectory()) bankDir.mkdirs();
        BankStore.cleanup(bankDir);      // 清掉上次被打断留下的 .tmp/.bak

        /* 方案库跟题库一样是纯文件，桌面单测能直接跑。放在 onCreate 最前面：
           提醒广播可能在 Activity 还没起来时就到（闹钟唤醒），那时也要读得到。 */
        File planDir = new File(getFilesDir(), "plans");
        if (!planDir.isDirectory()) planDir.mkdirs();
        planStore = new com.fan.shuatiku.agent.PlanStore(planDir);
        File memDir = new File(getFilesDir(), "memory");
        if (!memDir.isDirectory()) memDir.mkdirs();
        memoryStore = new com.fan.shuatiku.agent.MemoryStore(memDir);
        /* T6/A-5：历史明文凭据在启动时就地升级成 AES-GCM 密文，不再等下一次登录。 */
        SecurePrefs.migrate(getSharedPreferences("ds", MODE_PRIVATE),
                new String[]{"token", "mobile", "cookie", "device_id"});
        /* MCP 口令同样是凭据：历史明文一次性升级成密文。 */
        SecurePrefs.migrate(getSharedPreferences(PREFS_MCP, MODE_PRIVATE),
                new String[]{PREF_MCP_TOKEN});
        /* Agent trace 同时进 logcat（GImeAgent）与私有环形缓冲；导出入口在「我的」。 */
        com.fan.shuatiku.agent.RunTrace.setSink(new com.fan.shuatiku.agent.RunTrace.Sink() {
            @Override public void log(String line) {
                android.util.Log.i("GImeAgent", line);
            }
        });
        /* 每次启动重挂一遍闹钟：用户可能在系统设置里清过、或者被 ROM 省电策略杀了，
           这里补上比依赖「上次挂的那次还在」可靠。重复挂是幂等的。 */
        com.fan.shuatiku.agent.Reminder.arm(this);

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
        chatEmit = new ChatEmitter();
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
           用户只需要用官方手机号登录一次，不再需要 API Key 或外部反代服务。
           注意：内置通道的增量不经过 ChatEmitter。DeepSeekBridge 里的 Streamer
           已经按「40ms 或够 24 字」自己合并过了，而且 P0 明确不碰 DeepSeek*；
           在这里把它的脚本字符串再拆一次，等于用「解析 + 重新转义」换一点点
           调用次数，转义一错就会吃掉正文，不值得。 */
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
        insetTopPx = insets.getSystemWindowInsetTop();
        insetBottomPx = insets.getSystemWindowInsetBottom();
        pushInsets();
    }

    /** 把量到的高度灌给页面；页面还没跑起来时 window.__insets 不存在，那一次就白丢，靠 pageReady 补。 */
    private void pushInsets() {
        final int top = insetTopPx, bottom = insetBottomPx;
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

    /* 微信/文件管理器「用其他应用打开」递进来的文件：先让页面问一句怎么导。 */
    private void enqueueImport(final Uri uri) { enqueueFile(uri, PendingFile.KIND_ASK); }

    private void enqueueFile(final Uri uri, final int kind) {
        /* 从桌面图标启动时没有文件，intentOf 会返回 null；ArrayDeque 拒绝 null，
           直接 add 会在 onCreate 里把 App 炸掉。 */
        if (uri == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                pendingImports.add(new PendingFile(uri, kind));
                pumpImports();
            }
        });
    }

    /** 页面 ready 且没有正在跑的文件读取时才取下一条。 */
    private void pumpImports() {
        if (!pageReady || importing || pendingImports.isEmpty()) return;
        final PendingFile next = pendingImports.poll();
        if (next == null) return;
        importing = true;
        startImport(next);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != PICK && req != PICK_CHAT) || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        /* 附件是读一次就交给对话的临时文件，不申请持久授权，免得把授权列表越堆越长。 */
        if (req == PICK) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // 有些提供器不给持久授权，本次读取仍然可用
            }
        }
        enqueueFile(uri, req == PICK_CHAT ? PendingFile.KIND_CHAT : PendingFile.KIND_IMPORT);
    }

    private void startImport(final PendingFile file) {
        final Uri uri = file.uri;
        final int kind = file.kind;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = displayName(uri);
                try {
                    /* 只有库内导入有进度卡（读文件/抽文字/存盘）。附件与外部打开都只提示一下，
                       因为这时面板还没打开，硬调 importStage 会去改看不见的元素。 */
                    if (kind == PendingFile.KIND_IMPORT) {
                        js("window.importStage&&window.importStage(\"read\")");
                    } else if (kind == PendingFile.KIND_CHAT) {
                        js("window.toast&&window.toast(\"正在读取附件…\")");
                    } else {
                        js("window.toast&&window.toast(\"正在读取文件…\")");
                    }
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
                        if (kind == PendingFile.KIND_IMPORT) js("window.importStage&&window.importStage(\"extract\")");
                        text = TextExtract.extract(MainActivity.this, in, name);
                    } finally {
                        in.close();
                    }
                    if (text.trim().isEmpty()) throw new Exception("没读到文字内容");
                    if (kind == PendingFile.KIND_CHAT) {
                        js("window.onChatFile&&window.onChatFile(" + quote(name) + "," + quote(text) + ")");
                    } else if (kind == PendingFile.KIND_ASK) {
                        js("window.onExternalFile&&window.onExternalFile(" + quote(name) + "," + quote(text) + ")");
                    } else {
                        js("window.onImportText&&window.onImportText(" + quote(name) + "," + quote(text) + ")");
                    }
                } catch (final Exception e) {
                    if (kind == PendingFile.KIND_CHAT) js("window.onChatFileError&&window.onChatFileError(" + quote(readable(e)) + ")");
                    else js("window.onImportError&&window.onImportError(" + quote(readable(e)) + ")");
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

    /* ---------------- 流式批量上屏 ----------------
       上游一个 token 一帧地吐，逐帧 evaluateJavascript 会把 WebView 主线程
       打满：页面卡、字反而冒得更慢。这里把增量先攒在 StringBuilder 里，
       120ms 合并成一次 js()。120 是 agent 版实测值（MainActivity 的 Emitter）：
       再短省不下调用次数，再长肉眼就能看出成块。
       只接流式高频回调（chatApi 的 content/reasoning 增量）；pageReady、
       更新弹窗这类一次性 js() 不走这里，早发一帧晚发一帧无所谓。
       内置通道（DeepSeekBridge）有自己的 Streamer 按 40ms/24 字合并，
       属「不碰 DeepSeek*」的既有实现，这里不重复接管。 */
    private static final long EMIT_MS = 120;

    private final class ChatEmitter implements Runnable {
        private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        /* 同一个 (id, kind) 合并成一段：正文和思维链分开攒，别混在一起 */
        private final java.util.LinkedHashMap<String, StringBuilder> bufs =
                new java.util.LinkedHashMap<String, StringBuilder>();
        private boolean scheduled;

        void push(final String id, final String kind, final String delta) {
            if (delta == null || delta.isEmpty()) return;
            String key = id + "\u0000" + kind;
            synchronized (bufs) {
                StringBuilder b = bufs.get(key);
                if (b == null) { b = new StringBuilder(); bufs.put(key, b); }
                b.append(delta);
            }
            if (!scheduled) {
                scheduled = true;
                h.postDelayed(this, EMIT_MS);
            }
        }

        /** 丢掉这个 id 还没发出去的增量（取消时用：别让半句话挂在屏幕上）。 */
        void drop(final String id) {
            synchronized (bufs) {
                java.util.Iterator<String> it = bufs.keySet().iterator();
                while (it.hasNext()) {
                    if (it.next().startsWith(id + "\u0000")) it.remove();
                }
            }
        }

        /** 收尾前必须调，否则缓冲里的尾巴会丢。 */
        void flush() {
            h.removeCallbacks(this);
            scheduled = false;
            java.util.LinkedHashMap<String, StringBuilder> copy;
            synchronized (bufs) {
                if (bufs.isEmpty()) return;
                copy = new java.util.LinkedHashMap<String, StringBuilder>(bufs);
                bufs.clear();
            }
            for (java.util.Map.Entry<String, StringBuilder> e : copy.entrySet()) {
                String key = e.getKey();
                int cut = key.indexOf('\u0000');
                String chunk = e.getValue().toString();
                if (chunk.isEmpty()) continue;
                js("window.onChatStream(" + quote(key.substring(0, cut)) + ","
                        + quote(key.substring(cut + 1)) + "," + quote(chunk) + ")");
            }
        }

        @Override public void run() {
            scheduled = false;
            flush();
        }
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
        /* 口令落盘走 SecurePrefs（AES-256-GCM）；历史明文在 onCreate 里已就地升级。 */
        String token = SecurePrefs.dec(p.getString(PREF_MCP_TOKEN, ""));
        if (token == null || token.isEmpty()) {
            token = McpServer.randomHex(24);
            p.edit().putString(PREF_MCP_TOKEN, SecurePrefs.enc(token)).apply();
        }
        McpServer s = new McpServer(McpServer.PORT, token, new McpServer.Handler() {
            @Override public String handle(String requestJson) { return mcpHandle(requestJson); }
        });
        if (s.start()) {
            mcp = s;
            mcpError = "";
            /* 口令绝不能进 logcat：诊断包/日志会被导出、转发、贴到工单里。
               这行只留地址；要口令请在「我的」页复制（外部 tools/gime-mcp.mjs 用）。 */
            android.util.Log.i("GImeMCP", "http://127.0.0.1:" + McpServer.PORT
                    + "/mcp 已就绪（口令不在日志里，请在设置页复制）");
        } else {
            mcp = null;
            mcpError = s.error();
        }
    }

    /** 换一把新口令：旧的立即失效。设置页调用，返回新口令供复制。 */
    public String mcpResetToken() {
        try {
            String fresh = McpServer.randomHex(24);
            getSharedPreferences(PREFS_MCP, MODE_PRIVATE)
                    .edit().putString(PREF_MCP_TOKEN, SecurePrefs.enc(fresh)).apply();
            McpServer s = mcp;
            if (s != null) s.setToken(fresh);
            android.util.Log.i("GImeMCP", "口令已重置（旧口令立即失效）");
            return fresh;
        } catch (Throwable t) {
            android.util.Log.w("GImeMCP", "口令重置失败：" + t.getClass().getSimpleName());
            return "";
        }
    }

    private synchronized String mcpStatusJson() {
        mcpStart();
        String token = SecurePrefs.dec(getSharedPreferences(PREFS_MCP, MODE_PRIVATE)
                .getString(PREF_MCP_TOKEN, ""));
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
    protected void onResume() {
        super.onResume();
        if (remindCheckPending && pageReady) {
            remindCheckPending = false;
            js("window.onRemindPerm&&window.onRemindPerm();");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_NOTIFY) {
            remindCheckPending = false;
            if (pageReady) js("window.onRemindPerm&&window.onRemindPerm();");
        }
    }

    @Override
    protected void onDestroy() {
        synchronized (this) {
            if (mcp != null) { mcp.stop(); mcp = null; }
        }
        /* 页面马上就没了，缓冲里的增量再攒着也没人收；先推完再拆桥。 */
        if (chatEmit != null) chatEmit.flush();
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
                    pushInsets();          /* 页面（含重载后的）刚把 __insets 挂上，补灌一次安全区 */
                    pumpImports();
                    mcpStart();
                }
            });
        }

        /**
         * 仅 debug：用当前传输层（Cronet 或兜底）拉一次指纹体检页，
         * 把原始 JSON 塞进 window.__fp。发布包里不联网、直接返回。
         */
        @JavascriptInterface
        public void fpProbe(final String url) {
            if (!BuildConfig.DEBUG) return;
            /* 这个方法会照页面给的地址真发一次请求（还带 App 的 UA），
               不设闸等于给页面留了个任意 GET 的口子。发布包本来就 return 了，
               debug 包也别让它打非 http(s) 的东西。 */
            final String bad = BridgeGuard.probeUrl(url);
            if (bad != null) {
                android.util.Log.w("GImeBridge", "fpProbe 拒绝：" + bad + " url=" + url);
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    String out;
                    try {
                        java.util.Map<String, String> h = new java.util.HashMap<String, String>();
                        h.put("User-Agent", DeepSeekWeb.UA);
                        h.put("Accept", "*/*");
                        HttpNet.Conn c = HttpNet.open(new HttpNet.Request(
                                url == null || url.isEmpty() ? "https://tls.peet.ws/api/all" : url,
                                "GET", h, null, 10000, 30000));
                        out = HttpNet.readAll(c.body());
                        c.close();
                    } catch (Exception e) {
                        out = "ERR " + e;
                    }
                    js("window.__fp = " + quote(out));
                }
            }, "fp-probe").start();
        }

        /** MCP 服务的运行状态（没有开关：服务跟着进程生命周期走）。 */
        @JavascriptInterface
        public String mcpStatus() {
            return mcpStatusJson();
        }

        /** 换一把新口令：旧的立即失效；返回新口令供设置页复制。 */
        @JavascriptInterface
        public String mcpResetToken() {
            return MainActivity.this.mcpResetToken();
        }

        /** 页面把工具执行结果交回 HTTP 线程。 */
        @JavascriptInterface
        public void mcpReply(String id, String json) {
            /* 三个前提：id 长得像自己发出去的（防伪造注入）、确实在等、
               回包没超长。不满足就丢，并且留一行日志——静默丢弃最难查。 */
            if (!BridgeGuard.okId(id)) {
                android.util.Log.w("GImeBridge", "mcpReply 拒绝：id 非法 " + id);
                return;
            }
            String bad = BridgeGuard.reply(json);
            if (bad != null) {
                android.util.Log.w("GImeBridge", "mcpReply 拒绝：" + bad + " id=" + id);
                return;
            }
            McpWait w = mcpWait.get(id);
            if (w == null) {
                android.util.Log.w("GImeBridge", "mcpReply 丢弃：没有这个在等的请求 id=" + id);
                return;
            }
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
            /* 这是桥里权限最大的一个口子：body 原样发出去，key 也可能跟着走。
               id 必须是自己发出去的那种形状（页面靠它把增量对回气泡，
               伪造的 id 只会让 onChatApi 打到一条不存在的消息上），
               地址与体长也得先过闸，别让一次拼接失误变成对外发请求。 */
            if (!BridgeGuard.okId(id)) {
                android.util.Log.w("GImeBridge", "chatApi 拒绝：id 非法 " + id);
                return;
            }
            String badUrl = BridgeGuard.chatUrl(url);
            if (badUrl != null) {
                android.util.Log.w("GImeBridge", "chatApi 拒绝：" + badUrl + " url=" + url);
                js("window.onChatApi(" + quote(id) + ",400," + quote(
                        "{\"error\":{\"message\":" + quote("接口地址不合法：" + badUrl) + "}}") + ")");
                return;
            }
            String badBody = BridgeGuard.body(body);
            if (badBody != null) {
                android.util.Log.w("GImeBridge", "chatApi 拒绝：" + badBody + " id=" + id);
                js("window.onChatApi(" + quote(id) + ",400," + quote(
                        "{\"error\":{\"message\":" + quote(badBody) + "}}") + ")");
                return;
            }
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
                        /* 工具调用按 index 合并：上游把 arguments 拆成一截一截发，
                           不收口的话页面拿不到完整参数，只能看到「模型没回答」。 */
                        final OpenAiStream.ToolCalls calls = new OpenAiStream.ToolCalls();
                        try {
                            finish[0] = OpenAiStream.run(url, key, body, new OpenAiStream.Listener() {
                                @Override public void onContent(String d) {
                                    content.append(d);
                                    chatEmit.push(id, "text", d);
                                }
                                @Override public void onReasoning(String d) {
                                    reasoning.append(d);
                                    chatEmit.push(id, "think", d);
                                }
                                @Override public void onToolCall(int index, String cid, String name, String argsChunk) {
                                    calls.add(index, cid, name, argsChunk);
                                }
                                @Override public void onFinish(String reason) { finish[0] = reason; }
                            });
                        } catch (OpenAiStream.ApiException ae) {
                            /* 上游明确报错：按它的状态码回，页面能显示可读原因。
                               先把缓冲推出去，别让最后半句卡在 Java 侧。 */
                            chatEmit.flush();
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
                        if (!calls.isEmpty()) msg.put("tool_calls", calls.toArray());
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
                        /* 必须在 onChatApi 之前把这批增量推完：页面收到「结束」
                            就不会再往气泡里追加了，留在缓冲里等于丢字。 */
                        chatEmit.flush();
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

        /** 页面新开对话：让上游也立刻新开一个会话，保证两边一一对应。 */
        @JavascriptInterface
        public void dsNewChat(String convKey) {
            if (dsBridge != null) dsBridge.newChat(convKey);
        }

        /** 页面删除对话：把网页侧对应的会话也删掉。 */
        @JavascriptInterface
        public void dsDeleteChat(String convKey) {
            if (dsBridge != null) dsBridge.deleteChat(convKey);
        }

        /** 退出登录：清掉本地 token。 */
        @JavascriptInterface
        public void dsLogout() {
            DeepSeekBridge.clear(MainActivity.this);
        }

        /** 取消一个还在跑的大肥鱼请求（掐断连接，不用等到超时）。 */
        @JavascriptInterface
        public void dsCancel(String id) {
            /* id 必须是页面自己发出去的那种形状；乱填的 id 只会在
               chatEmit 的桶里乱删，或者去 cancel 一个不存在的会话。 */
            String bad = BridgeGuard.okId(id) ? null : "id 非法";
            if (bad != null) {
                android.util.Log.w("GImeBridge", "dsCancel 拒绝：" + bad + " id=" + id);
                return;
            }
            /* 先把还没发出去的增量丢掉：用户点了停止，屏幕上不该再多冒半句。
               顺序不能反——先 cancel 的话，收尾的 flush 可能把缓冲又推上去。 */
            chatEmit.drop(id);
            if (dsBridge != null) dsBridge.cancel(id);
        }

        /** 页面换肤时同步系统栏图标深浅和窗口底色。 */
        @JavascriptInterface
        public void themeHint(final boolean light) {
            applyBarAppearance(light);
            /* 只多存一份：底色本身 applyBarAppearance 里已经在 UI 线程上设过了。
               这份存的目的是下次冷启动——首帧之前 WebView 得知道用哪套纸色。 */
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_LIGHT, light).apply();
        }

        /** OpenUI 卡片上的按钮点了：P0 只留痕，不猜业务语义；动作路由留到 P1。
            页面的 console 进不了 logcat（没装 WebChromeClient），所以借这一口打一行便于现场验收。 */
        @JavascriptInterface
        public void ouiAction(String action, String payloadJson) {
            android.util.Log.i("GImeOui", "onOuiAction action=" + action + " payload=" + payloadJson);
        }

        /* ============ 方案库（P1） ============
           页面与 MCP 计划工具走同一份 plans.json。四个方法都是「同步返回 JSON 字符串」，
           不抛异常也不回 null —— 页面侧拿到的一定是能 JSON.parse 的串，
           错误也包成 {"error":"..."}，省得页面到处 try。 */

        /** 读全部方案：{rev, plans:[...]}。 */
        @JavascriptInterface
        public String planSnapshot() {
            try {
                return planStore == null ? "{\"rev\":0,\"plans\":[]}" : planStore.snapshot();
            } catch (Throwable t) {
                return "{\"rev\":0,\"plans\":[],\"error\":" + quote(String.valueOf(t.getMessage())) + "}";
            }
        }

        /** 新建方案。返回 {rev, plan} 或 {error}；rev 冲突时 rev = -1。 */
        @JavascriptInterface
        public String planCreate(String goal, int days, String title, String daysJson, int clientRev) {
            if (planStore == null) return "{\"error\":\"方案库还没准备好\"}";
            try {
                org.json.JSONArray dayList = null;
                if (daysJson != null && daysJson.length() > 0) dayList = new org.json.JSONArray(daysJson);
                com.fan.shuatiku.agent.PlanStore.Out out =
                        planStore.create(goal, days, title, dayList, clientRev);
                return planOutJson(out);
            } catch (Throwable t) {
                return "{\"error\":" + quote(String.valueOf(t.getMessage())) + "}";
            }
        }

        /** 改方案。action 见 PlanStore.ACTIONS；argsJson 是给该 action 的参数。 */
        @JavascriptInterface
        public String planUpdate(String id, String action, String argsJson, int clientRev) {
            if (planStore == null) return "{\"error\":\"方案库还没准备好\"}";
            try {
                org.json.JSONObject args = null;
                if (argsJson != null && argsJson.length() > 0) args = new org.json.JSONObject(argsJson);
                com.fan.shuatiku.agent.PlanStore.Out out =
                        planStore.update(id, action, args, clientRev);
                return planOutJson(out);
            } catch (Throwable t) {
                return "{\"error\":" + quote(String.valueOf(t.getMessage())) + "}";
            }
        }

        /** 删方案。 */
        @JavascriptInterface
        public String planDelete(String id, int clientRev) {
            if (planStore == null) return "{\"error\":\"方案库还没准备好\"}";
            try {
                return planOutJson(planStore.delete(id, clientRev));
            } catch (Throwable t) {
                return "{\"error\":" + quote(String.valueOf(t.getMessage())) + "}";
            }
        }

        /** 10 个工具的 schema（Java ToolRegistry 唯一真源）；MCP 与 AgentLoop 共用。 */
        @JavascriptInterface
        public String toolSchemas() {
            return com.fan.shuatiku.agent.ToolRegistry.schemasJson();
        }

        /** 记忆库：snapshot / save / clear。画像与会话摘要都由页面生成，这里只存取。 */
        @JavascriptInterface
        public String memoryCall(String op, String json) {
            if (memoryStore == null) return "{\"error\":\"记忆库还没准备好\"}";
            try {
                if ("snapshot".equals(op)) return memoryStore.snapshot();
                if ("clear".equals(op)) { memoryStore.clear(); return memoryStore.snapshot(); }
                if ("save".equals(op)) {
                    org.json.JSONObject o = new org.json.JSONObject(json == null ? "{}" : json);
                    boolean ok = memoryStore.save(o.optJSONObject("profile"), o.optJSONObject("session"));
                    return ok ? memoryStore.snapshot() : "{\"error\":\"记忆写盘失败\"}";
                }
                return "{\"error\":\"未知 op\"}";
            } catch (Throwable t) {
                return "{\"error\":" + quote(String.valueOf(t.getMessage())) + "}";
            }
        }

        /** Agent 护栏：begin / tick / check / toolResult / approve / deny / finish / trace。 */
        @JavascriptInterface
        public String agentCall(String op, String json) {
            return com.fan.shuatiku.agent.AgentLoop.handle(op, json);
        }

        /** 导出脱敏诊断包到系统「下载」目录；返回给用户看的文件名说明。 */
        @JavascriptInterface
        public String exportTrace() {
            try {
                byte[] data = com.fan.shuatiku.agent.RunTrace.exportJson().getBytes("UTF-8");
                String name = "GIme诊断包-"
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(new java.util.Date()) + ".json";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "";
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) return "";
                    try { os.write(data); } finally { os.close(); }
                    String shown = name;
                    Cursor c = getContentResolver().query(uri,
                            new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                    if (c != null) {
                        try { if (c.moveToFirst() && !c.isNull(0)) shown = c.getString(0); }
                        finally { c.close(); }
                    }
                    return "系统「下载」目录里的 " + shown;
                }
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return "";
                File f = new File(dir, name);
                FileOutputStream out = new FileOutputStream(f);
                try { out.write(data); } finally { out.close(); }
                return f.getAbsolutePath();
            } catch (Throwable t) {
                return "";
            }
        }

        /** MCP notify_phone / 建方案后的即时通知。返回 false 表示没发出去。 */
        @JavascriptInterface
        public boolean notifyPhone(String title, String body) {
            if (body == null || body.trim().isEmpty()) return false;
            return com.fan.shuatiku.agent.Notify.show(MainActivity.this, 200, title, body);
        }

        /** 读一份完整方案（详情页要逐日内容）。 */
        @JavascriptInterface
        public String planGet(String id) {
            if (planStore == null) return "{\"error\":\"方案库还没准备好\"}";
            try {
                org.json.JSONObject p = planStore.find(id);
                if (p == null) return "{\"error\":\"方案不存在\"}";
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("plan", p);
                return o.toString();
            } catch (Throwable t) {
                return "{\"error\":" + quote(String.valueOf(t.getMessage())) + "}";
            }
        }

        private String planOutJson(com.fan.shuatiku.agent.PlanStore.Out out) {
            if (out == null) return "{\"error\":\"方案库没有返回\"}";
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("rev", out.rev);
                if (out.plan != null) o.put("plan", out.plan);
                if (!out.ok()) o.put("error", "rev 冲突：方案已被别处改过，请刷新后再试");
                return o.toString();
            } catch (Throwable t) {
                return "{\"rev\":-1,\"error\":" + quote(String.valueOf(t.getMessage())) + "}";
            }
        }

        /* ---- 提醒的系统门槛 ----
           提醒不是代码挂了就一定响：Android 13+ 要运行时通知权限，
           国产 ROM 的省电策略还会把重复闹钟压掉。页面「我的」里给两个入口，
           这里只负责读现状与把用户送到该去的地方。 */

        /** {granted, battery, sdk}：通知是否放行、是否已豁免省电、系统版本。 */
        @JavascriptInterface
        public String planRemindState() {
            boolean granted = true, battery = false;
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);
                granted = nm != null && nm.areNotificationsEnabled();
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Throwable ignored) {
            }
            return "{\"granted\":" + granted + ",\"battery\":" + battery
                    + ",\"sdk\":" + Build.VERSION.SDK_INT + "}";
        }

        /** 申请通知权限；系统里已经关掉（或拒绝过）就送去设置页。 */
        @JavascriptInterface
        public void planRemindAsk() {
            remindCheckPending = true;
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
                return;
            }
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                    remindCheckPending = false;
                }
            }
        }

        /**
         * 仅 debug：在进程内投递一次提醒，用来验收「receiver → PlanStore → 通知」
         * 这条链。闹钟本身是否挂上由 dumpsys alarm 另验——非导出的 receiver
         * 收不到 adb 的广播，没有 root 又改不了系统时间，只能走这一口。
         *
         * <p>slot：1/2 投递早/晚提醒；0 模拟开机广播；-1 只取消闹钟（给测试做对照）。
         */
        @JavascriptInterface
        public void planRemindTest(int slot) {
            if (!BuildConfig.DEBUG) return;
            com.fan.shuatiku.agent.ReminderReceiver r =
                    new com.fan.shuatiku.agent.ReminderReceiver();
            if (slot < 0) {
                com.fan.shuatiku.agent.Reminder.disarm(MainActivity.this);
                return;
            }
            Intent i = slot == 0
                    ? new Intent(com.fan.shuatiku.agent.ReminderReceiver.ACTION_BOOT)
                    : new Intent();
            if (slot > 0) {
                i.putExtra(com.fan.shuatiku.agent.ReminderReceiver.EXTRA_SLOT, slot);
            }
            r.onReceive(MainActivity.this, i);
        }

        /** 把用户送到系统的省电豁免列表；不是每台 ROM 都有这一页，找不到就退到应用详情。 */
        @JavascriptInterface
        public void planBatteryAsk() {
            remindCheckPending = true;
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                    remindCheckPending = false;
                }
            }
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

        /**
         * 页面让下载某个地址的更新包。
         *
         * <p>进的是系统下载器，下完直接拉安装页——所以这个入口必须锁死来源：
         * https、发布源白名单、本仓库 release 目录下的 .apk。页面正常只会传
         * {@code onUpdateInfo.urls} 里那几条（由 {@link DownloadSources} 生成），
         * 这里拒掉的一律留日志，方便发现「谁在拼别的地址」。
         */
        @JavascriptInterface
        public void downloadUpdate(String url) {
            String bad = BridgeGuard.downloadUrl(url);
            if (bad != null) {
                android.util.Log.w("GImeBridge", "downloadUpdate 拒绝：" + bad + " url=" + url);
                js("window.onUpdateError&&window.onUpdateError("
                        + quote("更新包地址不可信，已拒绝。可以点「打开发布页」手动下载") + ")");
                return;
            }
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
                    if (u.isEmpty()) continue;
                    String bad = BridgeGuard.downloadUrl(u);
                    if (bad != null) {
                        android.util.Log.w("GImeBridge", "downloadUpdateFrom 跳过：" + bad + " url=" + u);
                        continue;
                    }
                    list.add(u);
                }
            } catch (Exception ignored) {
            }
            if (list.isEmpty()) {
                android.util.Log.w("GImeBridge", "downloadUpdateFrom 拒绝：没有一个可用源");
                js("window.onUpdateError&&window.onUpdateError("
                        + quote("更新包地址不可信，已拒绝。可以点「打开发布页」手动下载") + ")");
                return;
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
            /* 这个地址会原样进 Intent.ACTION_VIEW。放行前必须确认是 http(s)：
               javascript:/file:/intent: 之类送进去，轻则被系统解析成别的动作，
               重则借已安装的 App 做一次非预期的跳转。 */
            final String bad = BridgeGuard.navUrl(url);
            if (bad != null) {
                android.util.Log.w("GImeBridge", "openUrl 拒绝：" + bad + " url=" + url);
                js("window.toast&&window.toast(" + quote("这个链接不是网页地址，已拦下") + ")");
                return;
            }
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
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

        /** 大肥鱼的附件：同一个选择器，读出来先交给对话，由用户决定要不要入库。 */
        @JavascriptInterface
        public void pickChatFile() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    /* text/plain 也放进来：.md 在很多文件管理器里报的就是它。
                       收不收由页面按扩展名判，这里只负责让文件在选择器里可见。 */
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "text/markdown", "text/plain"});
                    try {
                        startActivityForResult(i, PICK_CHAT);
                    } catch (Exception e) {
                        js("window.onChatFileError&&window.onChatFileError(\"这台机器上没有可用的文件选择器\")");
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
