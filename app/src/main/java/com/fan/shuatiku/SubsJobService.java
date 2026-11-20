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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 字幕生成的前台服务：退到桌面/锁屏也继续跑，通知栏给进度和取消入口。
 *
 * <p>一次只跑一个任务（这台机器同时跑两个识别器会抢内存），队列里排着当前集和下一集。
 * 每完成一集就落一条轨道；用户取消只影响正在跑的那个，已完成的保留。
 */
public class SubsJobService extends Service {

    public static final String ACTION_ENQUEUE = "com.fan.shuatiku.subs.ENQUEUE";
    public static final String ACTION_CANCEL = "com.fan.shuatiku.subs.CANCEL";
    public static final String ACTION_RESUME = "com.fan.shuatiku.subs.RESUME";
    public static final String ACTION_PAUSE = "com.fan.shuatiku.subs.PAUSE";
    public static final String ACTION_STOP_ALL = "com.fan.shuatiku.subs.STOP_ALL";
    /** 插队：让当前这一集收尾让位，立刻改跑指定的那一集。 */
    public static final String ACTION_JUMP = "com.fan.shuatiku.subs.JUMP";
    /** 空踢一脚：批量入队已经直接写好队列，这里只负责把服务和 worker 拉起来。 */
    public static final String ACTION_KICK = "com.fan.shuatiku.subs.KICK";
    public static final String EXTRA_BVID = "bvid";
    public static final String EXTRA_PAGE = "page";
    public static final String EXTRA_TITLE = "title";
    /** 视频标题（不是分P标题）：缓存目录按视频分组时要靠它区分。 */
    public static final String EXTRA_VTITLE = "vtitle";

    private static final String CHANNEL = "gime-subs";
    private static final int NOTIF_PROGRESS = 8801;
    private static final int NOTIF_DONE_BASE = 8900;
    /* 断点粒度：2 分钟一段。原来 5 分钟一段有两个代价：段间解码整段音频期间
       进度条冻住十几秒；段中暂停时半段结果落不了盘、续跑回退一大截。
       缩到 2 分钟后解码冻窗缩到 1/2.5，配合半段断点，暂停几乎零损失。
       120s PCM ≈ 7.7MB float，内存安全；断点写盘频率 ×2.5 可接受。 */
    private static final int SEG_SEC = 120;

    private volatile boolean canceled = false;
    /** 暂停：不是取消。跑到段边界就停，队列项退回 queued，断点与已下好的 mp4 都保留。 */
    private volatile boolean paused = false;
    /**
     * 插队让位：和暂停一样在段边界停手，但**不退服务**——当前这一集退回排队后
     * 立刻去取队首（被插到最前的那一集），继续跑。
     */
    private volatile boolean yielding = false;
    private static volatile String sLastRunText = "";
    private volatile String runningKey = "";
    private Thread worker;
    private final Object stateLock = new Object();
    private static volatile boolean sAlive = false;
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /** 取消沿下载/解码/识别链路向上抛出，避免被普通失败重试逻辑吞掉。 */
    private static final class CanceledRun extends Exception { }

    /** 暂停退出：与取消区别对待——取消要删任务，暂停只退回排队。 */
    private static final class PausedRun extends Exception { }

    /** 点「暂停」：立刻落盘标志，worker 在下一个检查点停手。 */
    public static void pause(Context c) {
        SubsStore.setPaused(c, true);
        Intent i = new Intent(c, SubsJobService.class);
        i.setAction(ACTION_PAUSE);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    /** 点「继续」：清标志并把 worker 拉起来，任务从断点接着跑。 */
    public static void resume(Context c) {
        SubsStore.setPaused(c, false);
        Intent i = new Intent(c, SubsJobService.class);
        i.setAction(ACTION_RESUME);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void enqueue(Context c, String bvid, int page, String title) {
        enqueue(c, bvid, page, title, null);
    }

    /** 带视频标题的入队（缓存目录分组用）；老调用点不必关心这个参数。 */
    public static void enqueue(Context c, String bvid, int page, String title, String vtitle) {
        Intent i = new Intent(c, SubsJobService.class);
        i.setAction(ACTION_ENQUEUE);
        i.putExtra(EXTRA_BVID, bvid);
        i.putExtra(EXTRA_PAGE, page);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_VTITLE, vtitle);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    /**
     * 一次入队一整个分P的缺口（「缓存全集」）：队列只写一次、服务只拉一次。
     *
     * <p>返回真正新排进队的集数，前端据此报数并重绘——不做「先画缓存中再看结果」的
     * 乐观 UI 猜测，避免按钮状态和真实队列错位。
     */
    public static int enqueueBatch(Context c, String bvid, int[] pages, String[] titles, String vtitle) {
        int added = 0;
        try { added = SubsStore.queueAll(c, bvid, pages, titles, vtitle); } catch (Throwable ignored) { }
        Intent i = new Intent(c, SubsJobService.class);
        i.setAction(ACTION_KICK);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        return added;
    }

    public static void cancel(Context c, String bvid, int page) {
        Intent i = new Intent(c, SubsJobService.class);
        i.setAction(ACTION_CANCEL);
        i.putExtra(EXTRA_BVID, bvid);
        i.putExtra(EXTRA_PAGE, page);
        /* 后台被系统限制 startService 时取消会悄悄丢失；走前台服务入口保证能送达 */
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    /**
     * 插队识别：把这一集提到队首，并让正在识别的那一集在段边界收尾让位。
     *
     * <p>当前任务不是被丢弃：它退回 queued，断点与已下好的音频都在，稍后自己会轮到。
     * 服务不退出、worker 不拆会话（模型常驻），所以插队项几乎立刻开跑。
     */
    public static void jump(Context c, String bvid, int page) {
        try { SubsStore.promote(c, bvid, page); } catch (Throwable ignored) { }
        Intent i = new Intent(c, SubsJobService.class);
        i.setAction(ACTION_JUMP);
        i.putExtra(EXTRA_BVID, bvid);
        i.putExtra(EXTRA_PAGE, page);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    /** App 回到前台时调用：上次进程被杀留下的 running/queued 记录要重新跑，
        否则进度会永远停在被杀那一刻的百分比。 */
    public static void resumeIfPending(Context c) {
        if (sAlive) return;
        /* 用户按了暂停：回到前台也不要自作主张接着跑，等他自己点「继续」 */
        if (SubsStore.paused(c)) return;
        try { if (SubsStore.pendingCount(c) <= 0) return; } catch (Throwable ignored) { return; }
        Intent i = new Intent(c, SubsJobService.class);
        i.setAction(ACTION_RESUME);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        sAlive = true;
        paused = SubsStore.paused(this);
        BiliStream.setCookieSource(new BiliStream.CookieSource() {
            @Override public String cookie() { return SubsJobService.this.cookie(); }
        });
        channel();
    }

    @Override public void onDestroy() {
        sAlive = false;
        releaseLocks();
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        boolean stopAll = ACTION_STOP_ALL.equals(action);
        boolean pauseAct = ACTION_PAUSE.equals(action);
        if (pauseAct) {
            paused = true;
            SubsStore.setPaused(this, true);
        } else if (ACTION_RESUME.equals(action)) {
            paused = false;
            SubsStore.setPaused(this, false);
            /* 上个 worker 可能正因为暂停在退场（PausedRun 分支会 break 出 loop）：
               补一拍再确认有没有 worker，否则「刚暂停又马上继续」会停在没人干活的状态 */
            ensureWorkerLater();
        }
        if (stopAll) {
            /* 用户明确「全部停止」：暂停标志一并复位，否则之后新入队会一直不跑 */
            paused = false;
            SubsStore.setPaused(this, false);
            synchronized (stateLock) {
                canceled = true;
                JSONArray a = SubsStore.queue(this);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String st = o.optString("status");
                    if (!"running".equals(st) && !"queued".equals(st)) continue;
                    String b = o.optString("bvid");
                    int p = o.optInt("page");
                    SubsStore.remove(this, b, p);
                    SubsStore.deleteTrack(this, b, p);
                }
            }
        } else if (ACTION_CANCEL.equals(action) && intent != null) {
            String bvid = intent.getStringExtra(EXTRA_BVID);
            int page = intent.getIntExtra(EXTRA_PAGE, 0);
            synchronized (stateLock) {
                if (bvid != null && bvid.equals(keyBvid(runningKey)) && page == keyPage(runningKey)) canceled = true;
                SubsStore.remove(this, bvid, page);
                SubsStore.deleteTrack(this, bvid, page);
            }
        } else if (ACTION_ENQUEUE.equals(action) && intent != null) {
            String bvid = intent.getStringExtra(EXTRA_BVID);
            int page = intent.getIntExtra(EXTRA_PAGE, 0);
            String title = intent.getStringExtra(EXTRA_TITLE);
            String vtitle = intent.getStringExtra(EXTRA_VTITLE);
            if (bvid != null && page > 0 && !SubsStore.hasTrack(this, bvid, page)) {
                /* 已经在队列里/正在跑就别再插一份：put("queued") 会把 running 降级，
                   跑完那一份之后 worker 可能又把同一集当新任务重跑一遍。 */
                JSONObject e = SubsStore.entry(this, bvid, page);
                String st = e == null ? "" : e.optString("status", "");
                if (!"queued".equals(st) && !"running".equals(st)) {
                    /* 排队不设上限（用户要求「排队等待解析的视频改为无限」），直接入队 */
                    SubsStore.put(this, bvid, page, title, "queued", vtitle);
                } else if (vtitle != null && !vtitle.isEmpty()) {
                    /* 老条目没有视频标题：顺手补上，缓存目录就能分组了 */
                    SubsStore.put(this, bvid, page, null, st, vtitle);
                }
            }
        } else if (ACTION_KICK.equals(action)) {
            /* 队列已经由 SubsStore.queueAll 一次性写好，这里只补一拍 worker：
               暂停状态下不入队也不自作主张开跑，保持用户的暂停意图。 */
            ensureWorkerLater();
        } else if (ACTION_JUMP.equals(action)) {
            /* 插队：这一集提到队首，正在跑的那一集在段边界收尾让位。
               顺带把暂停解掉——用户点「插队识别」，意思就是要现在开始跑。
               服务不退出，worker 复用同一会话，模型不用重新加载。 */
            paused = false;
            SubsStore.setPaused(this, false);
            yielding = true;
            if (intent != null) {
                String bvid = intent.getStringExtra(EXTRA_BVID);
                int page = intent.getIntExtra(EXTRA_PAGE, 0);
                if (bvid != null && page > 0) {
                    try { SubsStore.promote(this, bvid, page); } catch (Throwable ignored) { }
                }
            }
            ensureWorkerLater();
        }
        startForeground(NOTIF_PROGRESS, paused ? buildPaused() : buildProgress(stopAll ? "已停止全部字幕任务" : "字幕君准备中…", 0, 0));
        ensureWorker();
        /* 被系统回收后重启会把 running 重新当 queued 接着跑；主动 stopSelf 不会被拉起 */
        return START_STICKY;
    }

    private void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        acquireLocks();
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "gime-subs");
        worker.start();
    }

    /** 等旧 worker 退场后再补一个：暂停/继续挨得太近时不会漏掉任务。 */
    private void ensureWorkerLater() {
        try {
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    if (paused) return;
                    try { if (SubsStore.pendingCount(SubsJobService.this) <= 0) return; } catch (Throwable ignored) { return; }
                    ensureWorker();
                }
            }, 700);
        } catch (Throwable ignored) { }
    }

    /** 熄屏后 CPU/Wi-Fi 会休眠：识别期间用前台服务 + 两把锁把任务撑住，结束即释放。 */
    private void acquireLocks() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "gime:subs");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) wakeLock.acquire();
            }
        } catch (Throwable ignored) { }
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                if (wifiLock == null) {
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gime:subs");
                    wifiLock.setReferenceCounted(false);
                }
                if (!wifiLock.isHeld()) wifiLock.acquire();
            }
        } catch (Throwable ignored) { }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
    }

    private void loop() {
        try {
            /* 只在这个 worker 真正起来时回收僵尸状态：服务还活着的时候不要动 running，
               否则用户再入队一集就会把正在跑的那集降级成 queued。 */
            SubsStore.resetRunning(this);
            while (true) {
                /* 按了暂停：不再取下一集，当前这一集的状态由下面的 PausedRun 分支退回排队 */
                if (paused) break;
                /* 走到「取新任务」这一步说明上一集已经让位完毕，清掉插队标志；
                   否则插队项刚取到手，runOne 入口就会又把它抛回去，来回空转 */
                yielding = false;
                JSONObject job = SubsStore.nextPending(this);
                if (job == null) break;
                String bvid = job.optString("bvid");
                int page = job.optInt("page");
                String title = job.optString("title");
                StringBuilder key = new StringBuilder(bvid).append(':').append(page);
                boolean skip = false, ready = false;
                synchronized (stateLock) {
                    canceled = false;
                    runningKey = key.toString();
                    /* nextPending 与 cancel 可能撞车：已被移除的任务不能再复活 */
                    if (SubsStore.entry(this, bvid, page) == null) {
                        runningKey = "";
                        skip = true;
                    } else {
                        SubsStore.put(this, bvid, page, title, "running");
                        if (canceled) {
                            SubsStore.remove(this, bvid, page);
                            SubsStore.deleteTrack(this, bvid, page);
                            runningKey = "";
                            skip = true;
                        }
                    }
                }
                if (skip) continue;

                boolean ok;
                try {
                    ok = runOne(bvid, page, title);
                } catch (PausedRun pe) {
                    /* 暂停/插队：这一集退回排队（断点、已下好的 mp4 都留着）。
                       暂停 = 收工退场；插队 = 让位后立刻去取队首那一集，服务不退出。 */
                    boolean jumpMode;
                    synchronized (stateLock) {
                        JSONObject e0 = SubsStore.entry(this, bvid, page);
                        if (e0 != null && "running".equals(e0.optString("status"))) {
                            SubsStore.put(this, bvid, page, title, "queued");
                        }
                        runningKey = "";
                        jumpMode = yielding && !paused;
                        yielding = false;
                    }
                    if (jumpMode) continue;
                    break;
                }
                /* 结果落盘与取消用同一把锁：避免「取消已删、这里又标 ready」把任务复活。
                   队列里还有下一集时不要拆会话：冷加载要读约 154MB encoder，
                   连续任务复用同一会话，避免反复读 eMMC。服务结束由 finally 统一释放。 */
                synchronized (stateLock) {
                    if (canceled) {
                        /* 取消后立刻转下一集。只清掉这次运行的队列项：
                           用户取消后又重新入队的同集（status=queued）不能误删；
                           但本次可能刚落盘的轨一定要删。 */
                        JSONObject e = SubsStore.entry(this, bvid, page);
                        if (e == null || "running".equals(e.optString("status"))) {
                            SubsStore.remove(this, bvid, page);
                        }
                        SubsStore.deleteTrack(this, bvid, page);
                    } else if (ok) {
                        SubsStore.put(this, bvid, page, title, "ready");
                        ready = true;
                    } else {
                        SubsStore.put(this, bvid, page, title, "failed");
                    }
                    runningKey = "";
                }
                if (ready) {
                    JSONObject e = SubsStore.entry(this, bvid, page);
                    if (e != null && "ready".equals(e.optString("status"))) notifyDone(title, page);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("GIME_SUBS", "worker: " + t);
        } finally {
            try { TrackBuilder.releaseShared(); } catch (Throwable ignored) { }
            releaseLocks();
            stopForeground(true);
            /* 暂停收尾：留一条常驻「已暂停」通知，用户可以从通知栏直接点继续 */
            if (paused) notifyPaused();
            stopSelf();
        }
    }

    private boolean runOne(String bvid, int page, String title) throws PausedRun {
        /* 媒体缓存：优先 .m4a（DASH 纯音频），兼容旧 .mp4（整段视频）；变量名沿用 mp4 */
        File mp4 = null;
        File doneMark = null;
        boolean keepFiles = true;
        try {
            if (canceled) return false;
            if (paused || yielding) throw new PausedRun();
            /* 已经完成过：断点/半截文件都清掉，别重复识别 */
            if (SubsStore.hasTrack(this, bvid, page)) {
                SubsStore.deletePart(this, bvid, page);
                return true;
            }
            String name = title == null || title.isEmpty() ? ("P" + page) : title;
            notifyProgress("字幕君正在生成 " + name + " · " + fmtPct(0.0), 0, 100, bvid, page);

            String ck = cookie();
            if (ck.isEmpty()) return false;
            JSONObject v = BiliWeb.view(ck, bvid);
            JSONArray pages = v.optJSONArray("pages");
            if (pages == null || page > pages.length()) return false;
            long cid = pages.getJSONObject(page - 1).getLong("cid");
            /* 字幕只要音频：优先 DASH 纯音频（~1/4 体积，高动态视频差距更大）。
               拿不到 DASH 时回退 360P mp4——音轨码率与 720P 相同，视频字节少一半。 */
            JSONObject play = BiliWeb.playAudio(ck, bvid, cid);
            String url = play.optString("url", "");
            boolean audioOnly = !url.isEmpty();
            if (!audioOnly) {
                play = BiliWeb.play(ck, bvid, cid, 16);
                url = play.optString("url", "");
            }
            if (url.isEmpty()) return false;
            if (canceled) return false;
            if (paused || yielding) throw new PausedRun();
            String local = BiliStream.issue(url);

            File dir = new File(getCacheDir(), "subs");
            if (!dir.isDirectory()) dir.mkdirs();
            /* 缓存按内容口径分文件：.m4a = DASH 纯音频，.mp4 = 整段视频（旧 720P 或回退 360P）。
               两种完整缓存都直接可用（MediaExtractor 按内容识别，不看扩展名）。
               半截文件绝不能跨口径续传：旧 720P 的半截接上音频字节会拼出坏文件，
               所以 .mp4 无完成标记时一律清掉（旧 720P 与回退 360P 无法区分）；
               .m4a 半截永远是同一条音频流，Range 续传安全。 */
            File legacy = new File(dir, bvid + "-" + page + ".mp4");
            File audioF = new File(dir, bvid + "-" + page + ".m4a");
            if (cacheComplete(audioF)) {
                mp4 = audioF;
                /* 同一集两份完整缓存只会出现在升级窗口期：音频版更小，留它删旧视频版 */
                if (cacheComplete(legacy)) {
                    legacy.delete();
                    new File(legacy.getPath() + ".done").delete();
                }
            } else if (cacheComplete(legacy)) {
                mp4 = legacy;
            } else {
                mp4 = audioOnly ? audioF : legacy;
                dropPartial(audioOnly ? legacy : audioF);
                if (!audioOnly) dropPartial(legacy);
            }
            doneMark = new File(mp4.getPath() + ".done");
            final String fb = bvid;
            final int fp = page;
            final String fname = name;

            boolean mp4Ready = cacheComplete(mp4);
            if (!mp4Ready) {
                /* 有完成标记但长度对不上：旧文件不可信，清掉重下；
                   只有半截文件、没有标记时走 Range 续传。 */
                if (doneMark.isFile() && (!mp4.isFile() || markerLen(doneMark) != mp4.length())) {
                    mp4.delete();
                    doneMark.delete();
                }
                /* 网络波动：断流/卡住（20 秒没新字节）都抛错重试；半截文件保留，下次带 Range 接着下 */
                for (int attempt = 0; ; attempt++) {
                    try {
                        if (canceled) throw new CanceledRun();
                        if (paused || yielding) throw new PausedRun();
                        AudioPcm.downloadResume(local, mp4, new AudioPcm.DlCb() {
                            @Override public void onBytes(long done, long total) throws Exception {
                                if (canceled) throw new CanceledRun();
                                /* 下载中按暂停：半截 mp4 留着，下次带 Range 续传 */
                                if (paused || yielding) throw new PausedRun();
                                /* 下载阶段占 1–30%：长视频的 mp4 有两三百 MB、要下好几分钟。
                                   上游不给长度时不要报固定 3%（会看起来卡死），交不确定态。 */
                                if (total > 0) {
                                    double pct = Math.min(30.0, 1.0 + 29.0 * done / total);
                                    SubsStore.setProgress(SubsJobService.this, fb, fp,
                                            (int) Math.round(pct * 100), 10000);
                                    notifyProgress("字幕君正在下载 " + fname + " · " + fmtPct(pct),
                                            pct, 100, fb, fp);
                                } else {
                                    SubsStore.setProgress(SubsJobService.this, fb, fp, 0, 0);
                                    notifyProgress("字幕君正在下载 " + fname, 0, 0, fb, fp);
                                }
                            }
                        });
                        break;
                    } catch (CanceledRun ce) {
                        return false;
                    } catch (PausedRun pe) {
                        throw pe;          /* 暂停不是失败：交给 loop 退回排队 */
                    } catch (Exception de) {
                        if (attempt >= 4) throw de;
                        SubsStore.setProgress(SubsJobService.this, fb, fp, 100, 10000);
                        notifyProgress("网络波动，重试下载（" + (attempt + 2) + "/5）", 1.0, 100, fb, fp);
                    }
                }
                if (mp4.isFile()) writeMarker(doneMark, mp4.length());
            } else {
                SubsStore.setProgress(this, bvid, page, 3000, 10000);
                notifyProgress("字幕君继续生成 " + name + " · 已缓存音频", 30.0, 100, bvid, page);
            }
            if (canceled) return false;
            if (paused || yielding) throw new PausedRun();

            notifyProgress("字幕君正在生成 " + name + " · 解码音频", 31.0, 100, bvid, page);
            SubsStore.setProgress(this, bvid, page, 3100, 10000);
            /* 长视频整条 PCM 会 OOM（92 分钟≈356MB float），按 10 分钟一段解一段识别，
               每段的时间戳加上段起点，最后拼成整条轨。断点也按段落盘。 */
            JSONObject part = SubsStore.readPart(this, bvid, page);
            long dur0 = AudioPcm.durationMs(mp4);
            if (dur0 <= 0 && part != null) dur0 = part.optLong("durMs", 0);
            if (dur0 <= 0) {
                /* 个别 mp4 容器拿不到时长：退回上游给的分P时长，避免进度按 1ms 总分算成假 99% */
                try { dur0 = pages.getJSONObject(page - 1).optLong("duration", 0) * 1000L; } catch (Throwable ignored) { }
            }
            final long durMs = dur0;
            final JSONArray segs = new JSONArray();
            double resumeSec0 = 0;
            if (part != null) {
                long pcid = part.optLong("cid", -1);
                long pdur = part.optLong("durMs", 0);
                boolean bad = (pcid > 0 && cid > 0 && pcid != cid)
                        || (pdur > 0 && durMs > 0 && Math.abs(pdur - durMs) > 5000)
                        || (durMs <= 0 && part.optDouble("nextSec", 0) > 0.05);
                JSONArray ps = part.optJSONArray("segs");
                double ns = part.optDouble("nextSec", 0);
                if (durMs > 0 && ns > durMs / 1000.0) ns = durMs / 1000.0;
                JSONArray loaded = new JSONArray();
                if (!bad && ps != null) {
                    for (int i = 0; i < ps.length(); i++) {
                        JSONObject o = ps.optJSONObject(i);
                        if (o != null) loaded.put(o);
                    }
                }
                if (bad || ns <= 0.05 || loaded.length() == 0) {
                    SubsStore.deletePart(this, bvid, page);
                } else {
                    for (int i = 0; i < loaded.length(); i++) segs.put(loaded.optJSONObject(i));
                    resumeSec0 = ns;
                }
            }
            final double resumeSec = resumeSec0;
            final long cidF = cid;
            final long[] lastNotify = {0};

            if (resumeSec > 0.05 && durMs > 0) {
                double rp = Math.min(95.0, 32.0 + 63.0 * resumeSec * 1000.0 / durMs);
                SubsStore.setProgress(this, bvid, page, (int) Math.round(rp * 100), 10000);
                notifyProgress("字幕君继续生成 " + name + " · " + fmtPct(rp), rp, 100, bvid, page);
            } else {
                notifyProgress("字幕君正在生成 " + name + " · 解码音频", 31.0, 100, bvid, page);
                SubsStore.setProgress(this, bvid, page, 3100, 10000);
            }

            if (durMs > 0 && resumeSec >= durMs / 1000.0 - 0.05 && segs.length() > 0) {
                /* 上次已经认完最后一段，只是没来得及落轨：直接定稿 */
                SubsStore.saveTrack(this, bvid, page, segs);
                SubsStore.deletePart(this, bvid, page);
                keepFiles = false;
                notifyProgress("字幕君生成好啦： " + name, 100, 100, bvid, page);
                autoNext(ck, bvid, pages, page);
                return true;
            }

            AudioPcm.decodeSegments(mp4, SEG_SEC, resumeSec, new AudioPcm.SegCb() {
                @Override public void onSegment(float[] pcm, double startSec) throws Exception {
                    if (canceled) throw new CanceledRun();
                    SubsStore.setStage(SubsJobService.this, fb, fp, "asr");
                    final long segMs = Math.round(pcm.length * 1000.0 / AudioPcm.OUT_RATE);
                    JSONArray part = TrackBuilder.build(SubsJobService.this, pcm, new TrackBuilder.Ctl() {
                        @Override public boolean canceled() { return canceled || paused || yielding; }
                        /* 暂停/让位不中止原生：认完这一段落断点再停，续跑零重认 */
                        @Override public boolean hardCancel() { return canceled; }
                        @Override public void progress(int done, int total) {
                            if (total <= 0) return;
                            long now = System.currentTimeMillis();
                            boolean finalTick = done >= total;
                            /* 回调约 0.4 秒一次：写盘/通知节流到 ~1 秒，但每段结束一定写一次 */
                            if (!finalTick && now - lastNotify[0] < 900) return;
                            lastNotify[0] = now;
                            if (durMs > 0) {
                                long ms = (long) (startSec * 1000) + segMs * done / total;
                                double pct = Math.min(95.0, 32.0 + 63.0 * ms / durMs);
                                /* 队列里统一存「百分点 ×100」，UI 才能显示两位小数 */
                                SubsStore.setProgress(SubsJobService.this, fb, fp,
                                        (int) Math.round(pct * 100), 10000);
                                notifyProgress("字幕君正在生成 " + fname + " · " + fmtPct(pct),
                                        pct, 100, fb, fp);
                            } else {
                                /* 总时长完全拿不到：不报假百分比，交「处理中」 */
                                SubsStore.setProgress(SubsJobService.this, fb, fp, 0, 0);
                                notifyProgress("字幕君正在生成 " + fname + " · 处理中", 0, 0, fb, fp);
                            }
                        }
                    });
                    boolean interrupted = paused || yielding;
                    /* 段中被暂停且一句都没认出来：没断点可落，下次重认这一段。
                       有半段结果（partial 轨）就必须落盘——否则续跑会退回上一段
                       边界重认，进度条肉眼回跳（用户报的 52%→31% 就是这个）。 */
                    if (interrupted && (part == null || part.length() == 0)) throw new PausedRun();
                    double partEnd = 0;
                    for (int i = 0; i < part.length(); i++) {
                        JSONObject o = part.optJSONObject(i);
                        if (o == null) continue;
                        double s = o.optDouble("s", 0) + startSec;
                        double e = o.optDouble("e", s) + startSec;
                        JSONObject q = new JSONObject();
                        q.put("s", Math.round(s * 100) / 100.0);
                        q.put("e", Math.round(e * 100) / 100.0);
                        q.put("t", o.optString("t", ""));
                        segs.put(q);
                        if (e > partEnd) partEnd = e;
                    }
                    /* 这一段被取消后别再解下一段（decodeSegments 每段先解码再回调） */
                    if (canceled) throw new CanceledRun();
                    /* 断点：整段认完用段尾；半段（暂停）用最后一句认完的时刻，
                       续跑从段中接着认，时间轴正好衔接，最多重认几秒音频。 */
                    try {
                        double endSec = interrupted ? Math.max(startSec, partEnd)
                                : startSec + pcm.length / (double) AudioPcm.OUT_RATE;
                        JSONObject cp = new JSONObject();
                        cp.put("cid", cidF).put("durMs", durMs).put("nextSec", endSec);
                        cp.put("segs", segs);
                        SubsStore.savePart(SubsJobService.this, bvid, page, cp);
                        if (durMs > 0) {
                            double pct = Math.min(95.0, 32.0 + 63.0 * endSec * 1000.0 / durMs);
                            SubsStore.setProgress(SubsJobService.this, bvid, page,
                                    (int) Math.round(pct * 100), 10000);
                        }
                    } catch (Throwable ignored) { }
                    /* 断点落好了再停：半段结果和进度都保住，续跑不回退 */
                    if (interrupted) throw new PausedRun();
                }
            }, new AudioPcm.DecodeCb() {
                @Override public void onDecode(double sec) {
                    /* 解码阶段心跳：只切状态和通知（不确定态进度条会自己动），
                       不写应用内进度值——解码位置领先识别位置，报上去会让
                       进度条先跳前再原地等识别追上来，更怪。 */
                    SubsStore.setStage(SubsJobService.this, fb, fp, "decode");
                    notifyProgress("字幕君正在生成 " + fname + " · 解码音频", 0, 0, fb, fp);
                }
            });
            if (canceled) return false;
            if (paused || yielding) throw new PausedRun();
            if (segs.length() == 0) return false;
            SubsStore.saveTrack(this, bvid, page, segs);
            if (canceled) {
                SubsStore.deleteTrack(this, bvid, page);
                return false;
            }
            SubsStore.deletePart(this, bvid, page);
            keepFiles = false;
            notifyProgress("字幕君生成好啦： " + name, 100, 100, bvid, page);
            autoNext(ck, bvid, pages, page);
            return true;
        } catch (CanceledRun ce) {
            return false;
        } catch (PausedRun pe) {
            throw pe;
        } catch (Throwable t) {
            android.util.Log.w("GIME_SUBS", "job " + bvid + ":" + page + " -> " + t);
            return false;
        } finally {
            if (!keepFiles && mp4 != null) {
                mp4.delete();
                if (doneMark != null) doneMark.delete();
            }
        }
    }

    /** 已完成下载的长度标记；缺失/长度对不上都当作没下完。 */
    /** 完整缓存 = 文件在 + 完成标记在 + 标记长度与文件一致。 */
    private static boolean cacheComplete(File f) {
        File mark = new File(f.getPath() + ".done");
        return f.isFile() && mark.isFile() && markerLen(mark) == f.length();
    }

    /** 清掉不可信的半截缓存（无有效完成标记的文件连同标记一起删）。 */
    private static void dropPartial(File f) {
        if (cacheComplete(f)) return;
        if (f.isFile()) f.delete();
        File mark = new File(f.getPath() + ".done");
        if (mark.isFile()) mark.delete();
    }

    private static long markerLen(File f) {
        if (!f.isFile()) return -1;
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            return Long.parseLong(br.readLine().trim());
        } catch (Throwable t) {
            return -1;
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) { }
        }
    }

    private static void writeMarker(File f, long len) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(f);
            fos.write(String.valueOf(len).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
        }
    }

    /** 当前集成功后，把下一集排进队列——用户不用手动点。 */
    private void autoNext(String ck, String bvid, JSONArray pages, int page) {
        try {
            int next = page + 1;
            if (pages == null || next > pages.length()) return;
            if (SubsStore.hasTrack(this, bvid, next)) return;
            if (SubsStore.entry(this, bvid, next) != null) return;
            /* 队列不设上限：下一集直接排进去，服务按顺序继续跑 */
            JSONObject pg = pages.getJSONObject(next - 1);
            /* 同一部视频的每一集都继承当前条目的视频标题，缓存目录才能把后续集归到正确的组 */
            String vt = "";
            try {
                JSONObject cur = SubsStore.entry(this, bvid, page);
                if (cur != null) vt = cur.optString("vt", "");
            } catch (Throwable ignored) { }
            SubsStore.put(this, bvid, next, pg.optString("part", "P" + next), "queued", vt);
        } catch (Throwable ignored) { }
    }

    private String cookie() {
        SharedPreferences p = getSharedPreferences(BiliLoginActivity.PREFS, MODE_PRIVATE);
        try {
            return SecurePrefs.dec(p.getString(BiliLoginActivity.PREF_COOKIE, ""));
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------------- 通知 ---------------- */

    private void channel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "字幕生成",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("后台生成视频字幕的进度");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification.Builder base(String title, String body) {
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
                /* 不给展开视图留位置的话，部分 ROM 会把 addAction 的动作行整个吞掉 */
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b;
    }

    private Notification buildProgress(String text, int pct, int total) {
        Notification.Builder b = base("字幕君", text);
        if (total > 0) b.setProgress(100, pct, false);
        return b.build();
    }

    /** 两位小数；正好 100 时不带小数。 */
    private static String fmtPct(double p) {
        if (p >= 99.995) return "100%";
        return String.format(java.util.Locale.US, "%.2f%%", p);
    }

    private void notifyProgress(String text, double pct, int total, String bvid, int page) {
        /* 批量缓存时队列可能几十集：通知里带一句剩余量，退到后台也知道还要等多久 */
        String body = text;
        try {
            int left = SubsStore.pendingCount(this) - 1;
            if (left > 0) body = text + "（队列还有 " + left + " 集）";
        } catch (Throwable ignored) { }
        Notification.Builder b = base("字幕君", body);
        if (total > 0) b.setProgress(10000, (int) Math.round(pct * 100), false);
        else b.setProgress(0, 0, true);   /* 总长未知：走不确定态，别让旧百分比停在那 */
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent c = new Intent(this, SubsJobService.class);
        c.setAction(ACTION_CANCEL);
        c.putExtra(EXTRA_BVID, bvid);
        c.putExtra(EXTRA_PAGE, page);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "取消",
                PendingIntent.getService(this, 7, c, flags)).build());
        Intent s = new Intent(this, SubsJobService.class);
        s.setAction(ACTION_STOP_ALL);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "全部停止",
                PendingIntent.getService(this, 8, s, flags)).build());
        /* 后台跑着的时候也能直接暂停，不用回到 App */
        Intent pz = new Intent(this, SubsJobService.class);
        pz.setAction(ACTION_PAUSE);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "暂停",
                PendingIntent.getService(this, 9, pz, flags)).build());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_PROGRESS, b.build());
    }

    /** 暂停态：一条可点的「继续」，队列与进度都还在。 */
    private Notification buildPaused() {
        int left = 0;
        try { left = SubsStore.pendingCount(this); } catch (Throwable ignored) { }
        String body = left > 0 ? ("已暂停 · 还剩 " + left + " 集，点「继续」接着识别")
                               : "已暂停，没有待识别的视频";
        Notification.Builder b = base("字幕君已暂停", body);
        b.setProgress(0, 0, false);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent r = new Intent(this, SubsJobService.class);
        r.setAction(ACTION_RESUME);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_play, "继续",
                PendingIntent.getService(this, 10, r, flags)).build());
        return b.build();
    }

    /** 暂停后服务要退了，用一条普通通知留在通知栏上，随时可以点继续。 */
    private void notifyPaused() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_PROGRESS, buildPaused());
    }

    /** 完成通知：不自动消失，留一条「已就绪」让用户点进来。 */
    private void notifyDone(String title, int page) {
        String name = title == null || title.isEmpty() ? ("P" + page) : title;
        Notification.Builder b = base("字幕君生成好啦！", "《" + name + "》的字幕已经可以看了");
        b.setOngoing(false).setAutoCancel(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_DONE_BASE + (page % 90), b.build());
    }

    private static String keyBvid(String k) {
        int i = k == null ? -1 : k.indexOf(':');
        return i < 0 ? "" : k.substring(0, i);
    }

    private static int keyPage(String k) {
        int i = k == null ? -1 : k.indexOf(':');
        try { return i < 0 ? 0 : Integer.parseInt(k.substring(i + 1)); } catch (Exception e) { return 0; }
    }
}
