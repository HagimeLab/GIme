package com.fan.shuatiku;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * WebView 外壳 + 导入通道。
 * 选文件、读文件、存题库文件都在这一层；把正文切成题目在页面 JS 里做。
 */
public class MainActivity extends Activity {

    private static final int PICK = 4711;
    private WebView web;
    private File bankDir;
    private Updater updater;

    /** 页面报告 ready 之前收到的导入请求排队等它，ready 后按序消费，每条只消费一次。 */
    private final ArrayDeque<Uri> pendingImports = new ArrayDeque<Uri>();
    private boolean pageReady;
    private boolean importing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        bankDir = new File(getFilesDir(), "banks");
        if (!bankDir.isDirectory()) bankDir.mkdirs();

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
        web.setBackgroundColor(0xFFEDEEF1);
        web.addJavascriptInterface(new Bridge(), "Android");
        updater = new Updater(new Updater.Sink() {
            @Override public void toJs(String script) { js(script); }
            @Override public Activity activity() { return MainActivity.this; }
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

    private class Bridge {

        /** 页面把脚本跑完、事件函数都挂上之后调这里，之后才开始放行排队的导入。 */
        @JavascriptInterface
        public void pageReady() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    pageReady = true;
                    pumpImports();
                }
            });
        }

        /** 页面换肤时同步系统栏图标深浅和窗口底色。 */
        @JavascriptInterface
        public void themeHint(final boolean light) {
            applyBarAppearance(light);
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

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
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
            File target = new File(bankDir, safe(id) + ".json");
            File tmp = new File(bankDir, safe(id) + ".json.tmp");
            try {
                /* 先整份写进临时文件，落盘并校验长度之后才替换正式文件：
                   中途失败（空间不足、进程被杀）时旧题库还在，不会变成半截 JSON。 */
                byte[] bytes = json.getBytes("UTF-8");
                FileOutputStream os = new FileOutputStream(tmp);
                try {
                    os.write(bytes);
                    os.flush();
                    os.getFD().sync();
                } finally {
                    os.close();
                }
                if (tmp.length() != bytes.length) return false;
                if (target.exists() && !target.delete()) return false;
                if (!tmp.renameTo(target)) return false;
                return target.length() == bytes.length;
            } catch (Exception e) {
                return false;
            } finally {
                if (tmp.exists() && !target.exists()) tmp.delete();
            }
        }

        @JavascriptInterface
        public boolean deleteBank(String id) {
            File target = new File(bankDir, safe(id) + ".json");
            return !target.exists() || target.delete();
        }

        private String safe(String id) {
            return id == null ? "bad" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
        }
    }
}
