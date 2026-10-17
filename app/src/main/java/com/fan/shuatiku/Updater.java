package com.fan.shuatiku;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

/**
 * 检查 GitHub Releases 上的最新版并下载安装。
 *
 * 只认 Releases（tag 用 vX.Y.Z，资产里放一个 .apk），不再额外维护一份 version.json，
 * 免得两处版本号各说各话。
 */
public class Updater {

    private static final String TAG = "GImeUpdate";
    public static final String REPO = "HagimeLab/GIme";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    public interface Sink {
        void toJs(String script);
        Activity activity();
    }

    private final Sink sink;
    private long dmId = -1;
    private DownloadManager dm;
    private BroadcastReceiver receiver;

    public Updater(Sink sink) {
        this.sink = sink;
    }

    /** 后台查一次版本，结果以 JSON 交给页面。 */
    public void check(final String current) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String body = fetch(API);
                    org.json.JSONObject rel = new org.json.JSONObject(body);
                    String tag = rel.optString("tag_name", "");
                    String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    String notes = rel.optString("body", "");
                    String published = rel.optString("published_at", "");
                    // 2026-10-17T12:34:56Z → 2026-10-17；取不到就留空，界面自己隐藏这一行
                    String date = published.length() >= 10 ? published.substring(0, 10) : "";
                    org.json.JSONArray log = splitNotes(notes);
                    String apk = null;
                    org.json.JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            org.json.JSONObject a = assets.getJSONObject(i);
                            String name = a.optString("name", "");
                            if (name.toLowerCase().endsWith(".apk")) {
                                apk = a.optString("browser_download_url", null);
                                break;
                            }
                        }
                    }
                    final String info = new org.json.JSONObject()
                            .put("ok", true)
                            .put("current", current)
                            .put("latest", latest)
                            .put("hasUpdate", Ver.isNewer(latest, current))
                            .put("url", apk == null ? "" : apk)
                            .put("date", date)
                            .put("notes", notes.trim())
                            .put("log", log)
                            .put("prerelease", rel.optBoolean("prerelease", false))
                            .toString();
                    sink.toJs("window.onUpdateInfo&&window.onUpdateInfo(" + quote(info) + ")");
                } catch (Exception e) {
                    Log.w(TAG, "check failed", e);
                    String msg = e instanceof NotFound ? "仓库还没有发布版本" : "连不上更新服务器，检查网络后重试";
                    // catch 里不再碰 JSONObject.put（它抛受检异常），手拼一份就够
                    sink.toJs("window.onUpdateInfo&&window.onUpdateInfo(" + quote(
                            "{\"ok\":false,\"error\":" + quote(msg) + "}") + ")");
                }
            }
        }).start();
    }

    /** 下载完直接拉起系统安装页。 */
    public void download(final String url) {
        final Activity act = sink.activity();
        if (act == null || url == null || url.isEmpty()) return;
        if (dmId > 0) return;      // 已经在下了，连点两次不该开两条下载
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            // 没给「安装未知应用」授权，装了也装不上，先把人带到那个开关前
            sink.toJs("window.onUpdateNeedPermission&&window.onUpdateNeedPermission()");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
            } catch (Exception ignored) {
                try { act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)); } catch (Exception x) { }
            }
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setTitle("GIme 更新");
                    r.setDescription("正在下载新版本");
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "GIme-latest.apk");
                    r.setMimeType("application/vnd.android.package-archive");
                    dmId = dm.enqueue(r);
                    watch();
                    sink.toJs("window.onUpdateDownloading&&window.onUpdateDownloading()");
                } catch (Exception e) {
                    Log.w(TAG, "download failed", e);
                    sink.toJs("window.onUpdateError&&window.onUpdateError(" + quote("下载失败：" + e.getMessage()) + ")");
                }
            }
        });
    }

    /** 轮询下载进度喂给页面，完成后拉起安装。 */
    private void watch() {
        final Activity act = sink.activity();
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                poll(act);
            }
        };
        act.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        new Thread(new Runnable() {
            @Override public void run() {
                while (dmId > 0) {
                    try { Thread.sleep(600); } catch (InterruptedException e) { return; }
                    if (!poll(act)) break;
                }
            }
        }).start();
    }

    /** @return false 表示下载已结束（成功已拉起安装，或失败）。 */
    private boolean poll(final Activity act) {
        if (dm == null || dmId <= 0) return false;
        Cursor cu = null;
        try {
            cu = dm.query(new DownloadManager.Query().setFilterById(dmId));
            if (cu == null || !cu.moveToFirst()) return true;
            int status = cu.getInt(cu.getColumnIndex(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                long got = cu.getLong(cu.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long tot = cu.getLong(cu.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                final int pct = tot > 0 ? (int) (got * 100 / tot) : 0;
                sink.toJs("window.onUpdateProgress&&window.onUpdateProgress(" + pct + ")");
                return true;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                sink.toJs("window.onUpdateError&&window.onUpdateError(" + quote("下载中断，请稍后重试") + ")");
                done();
                return false;
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                final Uri local = dm.getUriForDownloadedFile(dmId);
                done();
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { install(local); }
                });
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "poll failed", e);
            return false;
        } finally {
            if (cu != null) cu.close();
        }
    }

    private void done() {
        dmId = -1;
        final Activity act = sink.activity();
        if (receiver != null && act != null) {
            try { act.unregisterReceiver(receiver); } catch (Exception ignored) { }
            receiver = null;
        }
    }

    private void install(Uri apk) {
        Activity act = sink.activity();
        if (act == null || apk == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(apk, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            act.startActivity(i);
            sink.toJs("window.onUpdateInstalled&&window.onUpdateInstalled()");
        } catch (Exception e) {
            sink.toJs("window.onUpdateError&&window.onUpdateError(" + quote("打不开安装包：" + e.getMessage()) + ")");
        }
    }

    private static class NotFound extends Exception { }

    /**
     * 把 Release 正文切成逐条日志。GitHub 的 body 是 Markdown，
     * 页面按纯文本渲染，所以这里先摘掉标题、列表符号和行内强调记号，
     * 免得弹窗里出现一堆 "## " 和 "**"。
     */
    static org.json.JSONArray splitNotes(String body) {
        org.json.JSONArray out = new org.json.JSONArray();
        if (body == null || body.trim().isEmpty()) return out;
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String raw : lines) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("```") || s.matches("^[-=*_]{3,}$")) continue;   // 分隔线
            s = s.replaceFirst("^#{1,6}\\s*", "");                            // 标题井号
            s = s.replaceFirst("^[-*+]\\s+", "");                             // 列表符号
            s = s.replaceFirst("^\\d+[.)]\\s+", "");                          // 有序列表
            s = s.replaceAll("\\*\\*|__|`", "");                              // 行内强调
            s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");             // 链接只留文字
            s = s.trim();
            if (!s.isEmpty()) out.put(s);
        }
        return out;
    }

    private static String fetch(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GIme-Android");     // GitHub 没有 UA 会直接拒
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) { c.disconnect(); throw new NotFound(); }
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code); }
        InputStream in = c.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        br.close();
        c.disconnect();
        return sb.toString();
    }

    public static String versionOf(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static String quote(String s) {
        try { return org.json.JSONObject.quote(s); } catch (Exception e) { return "\"\""; }
    }
}
