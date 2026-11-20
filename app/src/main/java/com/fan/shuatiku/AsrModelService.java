package com.fan.shuatiku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * 字幕模型下载的前台服务。
 *
 * <p>为什么要有它：模型包 167MB，原来 {@link AsrModels#download} 只是丢进进程内线程池，
 * 用户一按返回键退出 APP，进程被回收，下载当场断掉，进度也只剩磁盘上已完成的分块。
 * 现在整条下载链路由前台服务托管：
 *
 * <ul>
 *   <li>退到后台 / 熄屏继续下，通知栏有实时百分比；</li>
 *   <li>服务被系统回收后 START_STICKY 拉起，pending id 存在 SharedPreferences 里，
 *       重启后自动接着下（{@link AsrModels} 的分块断点会跳过已完成的块）；</li>
 *   <li>APP 回到前台时页面读 {@code asrModels()} 照样能看到 progress，不会归零。</li>
 * </ul>
 */
public class AsrModelService extends Service {

    private static final String CHANNEL = "gime-asr-model";
    private static final int NOTIF = 2;

    public static final String ACTION_START = "com.fan.shuatiku.ASR_MODEL_START";
    public static final String EXTRA_ID = "id";

    private static final String PREFS = "asr_model_svc";
    private static final String KEY_PENDING = "pending";

    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    /** 启动下载。页面点「下载模型」就走这里（前台服务，退后台继续）。 */
    public static void start(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        Intent i = new Intent(c, AsrModelService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_ID, id);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        channel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String id = null;
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            id = intent.getStringExtra(EXTRA_ID);
        }
        if (id == null || id.isEmpty()) {
            /* START_STICKY 重启（intent 为 null）：接着上次没下完的那个模型 */
            id = prefs().getString(KEY_PENDING, "");
        }
        if (id == null || id.isEmpty() || AsrModels.installed(this, id)) {
            prefs().edit().remove(KEY_PENDING).apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs().edit().putString(KEY_PENDING, id).apply();
        startForeground(NOTIF, note("模型下载", "正在准备下载…", -1));
        acquire();
        ensureWorker(id);
        return START_STICKY;
    }

    private void ensureWorker(final String id) {
        if (worker != null && worker.isAlive()) {
            /* 已经在跑同一个模型：只刷新一次通知，不重开线程 */
            return;
        }
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AsrModels.runDownload(getApplicationContext(), new Host(), id);
                } catch (Throwable ignored) {
                } finally {
                    /* runDownload 内部会 emit error/installed；这里兜底收尾 */
                    finishSoon();
                }
            }
        }, "gime-asr-model");
        worker.start();
    }

    /** 给模型下载的进度回调：更新通知 + 转给页面（页面不在前台就不转）。 */
    private class Host implements AsrEngine.Host {
        @Override public void js(final String code) {
            JSONObject o = null;
            try {
                if (code != null) {
                    String body = code;
                    int at = body.indexOf("window.giModelStatus&&giModelStatus(");
                    if (at >= 0) body = body.substring(at + "window.giModelStatus&&giModelStatus(".length());
                    if (body.endsWith(")")) body = body.substring(0, body.length() - 1);
                    o = new JSONObject(body);
                }
            } catch (Throwable ignored) { o = null; }

            String state = o == null ? "" : o.optString("state", "");
            int pct = o == null ? 0 : (int) Math.round(o.optDouble("pct", 0));
            String msg = o == null ? "" : o.optString("msg", "");

            if ("downloading".equals(state)) {
                notifyProgress("模型下载中 " + pct + "%", "退出 APP 也会继续下载，进度不会丢", pct);
            } else if ("unzipping".equals(state)) {
                notifyProgress("正在校验并解压模型", msg, -1);
            } else if ("installed".equals(state)) {
                prefs().edit().remove(KEY_PENDING).apply();
                notifyProgress("字幕模型已就绪", msg.isEmpty() ? "可以开 CC 使用了" : msg, 100);
                finishSoon();
            } else if ("error".equals(state)) {
                prefs().edit().remove(KEY_PENDING).apply();
                notifyProgress("模型下载中断", msg.isEmpty() ? "再点一次可续传" : msg, -1);
                finishSoon();
            } else if ("idle".equals(state)) {
                prefs().edit().remove(KEY_PENDING).apply();
                finishSoon();
            }

            /* 页面还活着就把同一份状态喂给它，按钮文案跟着变 */
            MainActivity.pushJs(code);
        }
    }

    /** 结束态通知让用户看得见一秒，再收掉前台与自身，避免「刚下完通知就消失」。 */
    private void finishSoon() {
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
                release();
                stopForeground(true);
                stopSelf();
            }
        }, "gime-asr-model-finish").start();
    }

    private void acquire() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gime:asr-model");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void release() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
    }

    @Override public void onDestroy() {
        release();
        super.onDestroy();
    }

    /* ---------------- 通知 ---------------- */

    private void channel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "字幕模型下载",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("后台下载字幕模型与解压进度");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** pct < 0 走不确定进度条；否则 0..100 的确定态。 */
    private Notification note(String title, String body, int pct) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_gime)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setOngoing(pct != 100)
                .setOnlyAlertOnce(true);
        if (pct >= 0) b.setProgress(100, pct, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void notifyProgress(String title, String body, int pct) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIF, note(title, body, pct));
    }
}