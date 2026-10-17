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
    /** 下载多久没有任何字节增长就判定卡死。20s 与系统的连接超时同量级。 */
    private static final long STALL_MS = 25000;
    /** DownloadManager.STATUS_WAITING_TO_RETRY，框架里是 @hide 常量。 */
    private static final int STATUS_WAITING_TO_RETRY = 194;
    /** 排查下载状态时打开，会逐次打印 poll 读到的 status/reason。 */
    private static final boolean DEBUG_STATUS = false;
    private long lastBytes = -1;
    private long lastProgressAt = 0;

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
                    org.json.JSONArray urls = new org.json.JSONArray();
                    /* 下载地址不再只认 GitHub 的 Release 资产：
                       那个地址会 302 到 release-assets.githubusercontent.com，国内常不通。
                       改为按实测可用的 CDN 顺序给出多个地址，原生层逐个试。 */
                    for (String u : DownloadSources.forVersion(REPO, latest)) {
                        urls.put(u);
                    }
                    final String info = new org.json.JSONObject()
                            .put("ok", true)
                            .put("current", current)
                            .put("latest", latest)
                            .put("hasUpdate", Ver.isNewer(latest, current))
                            .put("urls", urls)
                            /* 兼容老字段：取第一个源，老版本页面读这个也能用 */
                            .put("url", urls.length() > 0 ? urls.optString(0, "") : "")
                            .put("page", DownloadSources.releasePage(REPO, latest))
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
        download(java.util.Collections.singletonList(url));
    }

    /**
     * 按顺序尝试多个下载源，第一个连得上的交给系统下载器。
     * 页面传过来的是数组；老版本页面只传一个字符串时走上面的重载。
     */
    public void download(final java.util.List<String> urls) {
        final Activity act = sink.activity();
        if (act == null || urls == null || urls.isEmpty()) return;
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
        /* 先把「连不连得上」问清楚，再决定要不要交给 DownloadManager。
           国内网络对 GitHub 的下载域名常常直接不通，而系统的下载器会重试到天荒地老，
           用户只会看到一个不动的 0%。这里逐个源试，第一个通的就开始下。 */
        sink.toJs("window.onUpdateChecking&&window.onUpdateChecking()");
        new Thread(new Runnable() {
            @Override public void run() {
                String picked = null;
                for (String u : urls) {
                    if (u == null || u.isEmpty()) continue;
                    if (probe(u) == null) { picked = u; break; }
                }
                if (picked == null) {
                    sink.toJs("window.onUpdateError&&window.onUpdateError(" +
                            quote("所有下载源都连不上（网络可能屏蔽了这些地址）。可以点「打开发布页」手动下载安装包") + ")");
                    return;
                }
                final String target = picked;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { enqueueDownload(act, target); }
                });
            }
        }).start();
    }

    /**
     * 探测下载地址是否可达。返回 null 表示可以下，否则返回给用户看的原因。
     *
     * 关键点：**不跟随重定向**。GitHub 的下载地址会 302 到 release-assets 域名，
     * 跟随下去就等于把真正的下载连接建起来了，探测本身会卡住几十秒——
     * 那就失去了"先快速判断"的意义。这里只看第一跳能不能连上。
     */
    static String probe(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "GIme-Android");
            int code = c.getResponseCode();
            /* 2xx 直接能下；3xx 说明第一跳通了（真正的下载由系统下载器跟随重定向）；4xx/5xx 是真错 */
            if (code >= 200 && code < 400) return null;
            return "更新服务器返回 HTTP " + code + "，请稍后重试或点「打开发布页」手动下载";
        } catch (Exception e) {
            Log.w(TAG, "probe failed", e);
            return "连不上更新服务器（网络可能屏蔽了下载地址）。可以点「打开发布页」手动下载安装包";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 真正把任务交给系统下载器。 */
    private void enqueueDownload(final Activity act, final String url) {
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
        lastBytes = -1;
        lastProgressAt = System.currentTimeMillis();
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

    /**
     * 下载器状态 → 界面该做什么。实现在 DownloadOutcome 里，
     * 那边不依赖 Android，桌面测试可以直接钉住这条规则。
     */
    static String outcomeOf(int status) {
        return DownloadOutcome.of(status);
    }

    /** @return false 表示下载已结束（成功已拉起安装，或失败）。 */
    private boolean poll(final Activity act) {
        if (dm == null || dmId <= 0) return false;
        Cursor cu = null;
        try {
            cu = dm.query(new DownloadManager.Query().setFilterById(dmId));
            if (cu == null || !cu.moveToFirst()) return true;
            int status = cu.getInt(cu.getColumnIndex(DownloadManager.COLUMN_STATUS));
            if (DEBUG_STATUS) {
                int rs = -1;
                try { rs = cu.getInt(cu.getColumnIndex(DownloadManager.COLUMN_REASON)); } catch (Exception ignored) { }
                Log.d(TAG, "poll status=" + status + " reason=" + rs);
            }

            /* 判定规则集中在 outcomeOf()，测试直接钉住那个函数；
               这里只按结论执行，避免两处各写一份判断而慢慢走样。 */
            String outcome = outcomeOf(status);

            /* WAITING_TO_RETRY 是「连不上、过一会儿再试」。原来它落进末尾的 return true
               被当成「还在下」，界面就永远停在 0%——用户看到的正是「点了更新一直转圈」。
               DownloadManager.STATUS_WAITING_TO_RETRY 是 @hide 常量，只能写字面量 194。 */
            if ("error".equals(outcome)) {
                int reason = -1;
                try { reason = cu.getInt(cu.getColumnIndex(DownloadManager.COLUMN_REASON)); } catch (Exception ignored) { }
                fail(status == STATUS_WAITING_TO_RETRY
                        ? "连不上更新服务器，下载已停止。请检查网络后重试，或点「打开发布页」手动下载安装包"
                        : status == DownloadManager.STATUS_PAUSED
                        ? "下载已暂停，请到通知栏恢复，或重新点一次更新"
                        : "下载中断，请检查网络后重试，或点「打开发布页」手动下载安装包");
                Log.w(TAG, "download ended with status=" + status + " reason=" + reason);
                return false;
            }

            if ("progress".equals(outcome)) {
                long got = cu.getLong(cu.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long tot = cu.getLong(cu.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                final int pct = tot > 0 ? (int) (got * 100 / tot) : 0;
                if (got != lastBytes) {
                    lastBytes = got;
                    lastProgressAt = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastProgressAt > STALL_MS) {
                    /* 状态还是 RUNNING 但一个字节都不再涨：连接被卡死。
                       不设这条闸，用户会盯着一个不动的百分比等到没耐心为止。 */
                    fail("下载长时间没有进展，已停止。请换个网络后重试，" +
                         "或点「打开发布页」手动下载安装包");
                    Log.w(TAG, "download stalled at " + got + "/" + tot);
                    return false;
                }
                sink.toJs("window.onUpdateProgress&&window.onUpdateProgress(" + pct + ")");
                return true;
            }
            if ("install".equals(outcome)) {
            }
            if ("install".equals(outcome)) {
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

    /** 上报失败并彻底收尾：移除系统里的那条下载，免得它在后台一直重试。 */
    private void fail(String msg) {
        final long id = dmId;
        done();
        if (dm != null && id > 0) {
            try { dm.remove(id); } catch (Exception ignored) { }
        }
        sink.toJs("window.onUpdateError&&window.onUpdateError(" + quote(msg) + ")");
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
