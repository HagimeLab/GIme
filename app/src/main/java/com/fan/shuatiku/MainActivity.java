package com.fan.shuatiku;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

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

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        bankDir = new File(getFilesDir(), "banks");
        if (!bankDir.isDirectory()) bankDir.mkdirs();

        web = new WebView(this);
        try { WebView.setWebContentsDebuggingEnabled(true); } catch (Exception ignored) {}
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

        takeIn(getIntent());
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
        if (intent == null) return;
        String act = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(act) && !Intent.ACTION_SEND.equals(act)) return;
        Uri uri = intent.getData();
        if (uri == null) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
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
        startImport(uri);
    }

    private void startImport(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = displayName(uri);
                try {
                    js("window.importStage&&window.importStage(\"read\")");
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("打不开这个文件");
                    js("window.importStage&&window.importStage(\"extract\")");
                    String text = TextExtract.extract(MainActivity.this, in, name);
                    in.close();
                    if (text.trim().isEmpty()) throw new Exception("没读到文字内容");
                    js("window.onImportText&&window.onImportText(" + quote(name) + "," + quote(text) + ")");
                } catch (final Exception e) {
                    js("window.onImportError&&window.onImportError(" + quote(String.valueOf(e.getMessage())) + ")");
                }
            }
        }).start();
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
                            "application/msword", "text/plain", "text/markdown", "application/octet-stream"});
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
                FileOutputStream os = new FileOutputStream(tmp);
                try {
                    os.write(json.getBytes("UTF-8"));
                } finally {
                    os.close();
                }
                if (target.exists() && !target.delete()) return false;
                return tmp.renameTo(target);
            } catch (Exception e) {
                return false;
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
