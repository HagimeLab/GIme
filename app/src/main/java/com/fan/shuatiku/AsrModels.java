package com.fan.shuatiku;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * X-ASR 字幕模型的下载与安装。模型不再打进 APK，而是挂在仓库 v1.1.8 测试标记的
 * Release 资产上；APP 通过 GitHub 的 release asset 端点按 Range 分块下载
 * （设备直连 github.com / raw / jsdelivr 均被重置，只有 api.github.com 可用，
 * 它 302 到 release-assets CDN 且支持 206）。
 *
 * <p>下载目录固定为 files/asr/&lt;id&gt;/，识别器用绝对路径从那里加载，
 * 不写外部存储、不申请权限。分块文件放在 files/asr/&lt;id&gt;.parts/，
 * 中断后重下会跳过已完成的块。
 */
public final class AsrModels {

    /* 复用 AsrEngine.Host：MainActivity 里的 asrHost 就是它，避免两个同签名接口
       在 Java 名义类型下互不兼容。 */
    private static final String REPO = "HagimeLab/GIme";
    private static final String PREF = "gime.asr.v1";
    private static final String KEY_SELECTED = "selected";

    /* 模型包直链（快模型 1920ms 包）。填上后优先走这条：单流 + Range 断点续传，
       不消耗 GitHub API 配额，也不依赖 release assetId。留空则回退上面的 assetId 老路径。
       换包只改这里 + MODELS 里的 size/sha256，别的不用动。 */
    static final String MODEL_URL =
            "https://github.com/HagimeLab/GIme/releases/download/v1.1.8/gime-asr-xasr-1920ms-int8.zip";
    /** 4MB 一块。注意：分块不直接打 API，先用一次请求换签名直链，见 {@link #cdn}。 */
    private static final long CHUNK = 4L * 1024 * 1024;
    /** 并发 3：匿名配额只有 60 次/小时，并发越高越容易被二级限流（429）。 */
    private static final int WORKERS = 3;
    /** 每块重试 2 次：重试是净消耗配额，宁可快速报错让用户稍后续传。 */
    private static final int RETRIES = 2;

    /* 资产签名直链（302 → objects.githubusercontent.com?token=…，不吃 API 配额、支持 Range）。
       一轮下载只解析一次，40 个分块共用；过期或被拒时丢缓存重新解析（再来 1 次请求）。 */
    private static final Object CDN_LOCK = new Object();
    private static final long CDN_TTL = 30L * 60 * 1000;   /* 签名有效期约 50 分钟，留余量 */
    private static String cdnUrl;
    private static long cdnAt;
    /** Gitee 镜像的单卷大小：169MB 按 20MB 切成 9 卷（超 100MB 单附件不行，单流太大也易断）。 */
    private static final long GPART = 20L * 1024 * 1024;

    /** 同一个模型同一时刻只允许一个下载线程，避免连点开出一堆重复任务。 */
    private static final java.util.Set<String> RUNNING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 配额用完后到恢复时间之前不再发请求，直接给出「几点后可续传」。 */
    private static volatile long quotaUntil;
    private static volatile String quotaMsg;
    /** 模型包是否先试 Gitee 镜像（国内首源）。169MB 包按 20MB 切成 9 卷传到 Gitee
     *  Release `v1.1.8-models`；断点按卷计（已下好的卷不再重下），
     *  任一卷失败/校验不过自动降级下一条源，不弹错。
     *  2026-11-20 起：Gitee 首源 → 直链（GitHub Release 资产直出）→ GitHub 分块 API。 */
    private static final boolean USE_GITEE_MODELS = true;

    /** 项目唯一的本地字幕模型。 */
    public static final String MODEL_ID = "xasr";
    /** X-ASR 是 icefall 新导出的 zipformer2；写错会让 native 走旧类并 SIGABRT。 */
    public static final String MODEL_TYPE = "zipformer2";

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();

    private static final class Model {
        final String id, label, desc, sha256, zip;
        final long assetId, size;
        final String[] files;
        /** 直链下载地址；非空时优先于 assetId 的 GitHub 分块路径。 */
        final String url;

        Model(String id, String label, String desc, long assetId, long size,
              String sha256, String zip, String[] files, String url) {
            this.id = id;
            this.label = label;
            this.desc = desc;
            this.assetId = assetId;
            this.size = size;
            this.sha256 = sha256;
            this.zip = zip;
            this.files = files;
            this.url = url;
        }

        Model(String id, String label, String desc, long assetId, long size,
              String sha256, String zip, String... files) {
            this(id, label, desc, assetId, size, sha256, zip, files, "");
        }
    }

    /* 现在发的是快模型包（1920ms 混合精度 + onnxsim，整轨比 480ms 全 int8 快约 3.5×）。
       包里只有 4 个精确名文件，Rust 加载器优先用它；sherpa 回退所需的 epoch-99 慢模型
       不再随包下发，缺失时由 TrackBuilder 明确报错而不是静默出空字幕。 */
    private static final Model[] MODELS = {
            new Model(MODEL_ID, "本地字幕模型",
                    "X-ASR 中英语音识别模型经过自主量化 MatMul、Conv，以及 onnxsim 折叠动态 Shape",
                    0L, 168586449L,
                    "ffb6733dfbf8f0dee3cab678c1e42caf33853edc61f7c180b968a05df370f7d4",
                    "gime-asr-xasr-1920ms-int8.zip",
                    new String[]{"encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"},
                    MODEL_URL)
    };

    /** 旧包（480ms 慢模型）的 4 个文件名；老用户本地已有这套，仍算「已安装」，
        直到他主动删掉模型再重下才会换成快模型。 */
    private static final String[] LEGACY_FILES = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"};

    private AsrModels() { }

    private static Model find(String id) {
        for (Model m : MODELS) if (m.id.equals(id)) return m;
        return null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String selectedId(Context c) {
        String s = prefs(c).getString(KEY_SELECTED, "");
        if (s != null && find(s) != null) return s;
        /* 旧版本可能把 selected 留成 ctc/bilingual/sensevoice；只保留 xasr，
           否则升级后模型明明已安装，start() 仍会提示未下载。 */
        String fallback = installed(c, MODEL_ID) ? MODEL_ID : "";
        String old = s == null ? "" : s;
        if (!old.equals(fallback)) {
            prefs(c).edit().putString(KEY_SELECTED, fallback).apply();
        }
        return fallback;
    }

    public static File modelDir(Context c, String id) {
        return new File(c.getFilesDir(), "asr/" + id);
    }

    private static File asrRoot(Context c) {
        return new File(c.getFilesDir(), "asr");
    }

    public static boolean installed(Context c, String id) {
        Model m = find(id);
        if (m == null) return false;
        File d = modelDir(c, id);
        if (complete(d, m.files)) return true;
        /* 老用户：只有 480ms 那一套，也认「已安装」，避免升级后提示重新下载却还在用慢模型。 */
        return complete(d, LEGACY_FILES);
    }

    private static boolean complete(File dir, String[] files) {
        for (String f : files) {
            File x = new File(dir, f);
            if (!x.isFile() || x.length() <= 0) return false;
        }
        return true;
    }

    /** 已落地的分块/卷/直链半截文件占比（1~99），用于中断后按钮上显示续传进度。 */
    private static int progressPct(Context c, Model m) {
        if (installed(c, m.id)) return 100;
        long got = partBytes(new File(asrRoot(c), m.id + ".parts"), m, CHUNK)
                + partBytes(new File(asrRoot(c), m.id + ".gparts"), m, GPART);
        /* 直链模式把未拼完的整包也算进进度，否则续传后进度会从 0 开始跳。 */
        File half = new File(asrRoot(c), m.id + ".zip.part");
        if (half.isFile() && half.length() > 0) got += Math.min(half.length(), m.size);
        if (got <= 0) return 0;
        int pct = (int) (got * 100 / m.size);
        if (pct < 1) return 1;
        return Math.min(99, pct);
    }

    private static long partBytes(File dir, Model m, long partSize) {
        if (!dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long sum = 0;
        for (File f : fs) {
            String n = f.getName();
            /* `<i>.bin` 是已完成的一卷；`<i>.bin.part` 是正在写的那一卷。
               只认完成卷会出「通知显示 6%、设置页却还是 0%」的错位——正在下的
               那 20MB 不在账上，用户会以为进度丢了。半截卷按已写字节算。 */
            boolean partial = n.endsWith(".bin.part");
            if (!n.endsWith(".bin") && !partial) continue;
            String stem = partial ? n.substring(0, n.length() - 9) : n.substring(0, n.length() - 4);
            int idx;
            try { idx = Integer.parseInt(stem); } catch (Throwable t) { continue; }
            if (idx < 0 || (long) idx * partSize >= m.size) continue;
            long want = Math.min(partSize, m.size - (long) idx * partSize);
            long len = f.length();
            if (partial) sum += Math.min(len, want);
            else if (len == want) sum += want;
        }
        return sum;
    }

    /** 设置页同步读取的清单。 */
    public static String listJson(Context c) {
        JSONArray a = new JSONArray();
        String sel = selectedId(c);
        for (Model m : MODELS) {
            try {
                a.put(new JSONObject()
                        .put("id", m.id)
                        .put("label", m.label)
                        .put("desc", m.desc)
                        .put("size", m.size)
                        .put("installed", installed(c, m.id))
                        .put("progress", progressPct(c, m))
                        .put("running", RUNNING.contains(m.id))
                        .put("selected", m.id.equals(sel)));
            } catch (Exception ignored) { }
        }
        return a.toString();
    }

    /** 下载 + 校验 + 解压；断点续传靠 .parts/ 里已完成的块。 */
    public static void download(final Context ctx, final AsrEngine.Host host, final String id) {
        EX.execute(new Runnable() {
            @Override public void run() { runDownload(ctx, host, id); }
        });
    }

    /**
     * 同步跑完一次模型下载（前台服务在后台线程直接调用它）。
     *
     * <p>拆出来的原因：{@link #download} 只管「丢进进程内线程池」，APP 一退进程被杀，
     * 下载就断了；{@link AsrModelService} 用前台服务托管这条同步链路，退到后台照样跑，
     * 通知栏能看进度，进程真被杀也还有 .parts/ 的分块可以从断点续。
     */
    public static void runDownload(final Context ctx, final AsrEngine.Host host, final String id) {
        final Model m = find(id);
        if (m == null) return;
        {
                if (!RUNNING.add(m.id)) {
                    /* 已经在下了：不要开出第二个线程（连点会重复拉同一批分块）。 */
                    emit(host, m.id, "downloading", progressPct(ctx, m), "下载中", false);
                    return;
                }
                try {
                    if (installed(ctx, m.id)) {
                        emit(host, m.id, "installed", 100, "已安装", true);
                        return;
                    }
                    asrRoot(ctx).mkdirs();

                    /* 国内首源：Gitee 分卷（附件不支持 Range，按卷续传）。失败静默降级，
                       不弹错、不打断用户，接着走直链。 */
                    if (USE_GITEE_MODELS) {
                        if (giteeSingle(ctx, host, m)) return;
                        /* 镜像和直链都没有、assetId 还是 0 时，继续往下只会打 GitHub 空号换来
                           一个 HTTP 404，用户看到的是天书。这里直接把「已下几卷、能续传」说清楚。 */
                        if (m.assetId <= 0 && effectiveUrl(ctx, m).length() == 0) {
                            int pct = (int) Math.min(99, giteeDone(ctx, m) * 100 / m.size);
                            emit(host, m.id, "error", pct,
                                    "镜像下载中断（已存 " + pct + "%，再点一次可续传）", false);
                            return;
                        }
                    }

                    /* 直链次之：一条连接 + Range 续传，不吃 GitHub API 配额。
                       失败已在内部报错并保留半截文件，这里直接收工，不再落到老 assetId 路径
                       （新包的 assetId 是 0，走下去只会白烧几轮请求）。 */
                    String direct = effectiveUrl(ctx, m);
                    if (direct.length() > 0) {
                        urlDownload(ctx, host, m, direct);
                        return;
                    }

                    /* 分块目录只给下面的 GitHub 分块路径用，直链分支不该建它。 */
                    final File pdir = new File(asrRoot(ctx), m.id + ".parts");
                    if (!pdir.isDirectory() && !pdir.mkdirs()) {
                        throw new IllegalStateException("无法创建下载目录");
                    }
                    final int parts = (int) ((m.size + CHUNK - 1) / CHUNK);
                    final AtomicLong done = new AtomicLong(0);
                    final AtomicReference<String> err = new AtomicReference<String>(null);
                    emit(host, m.id, "downloading", 0, "连接仓库…", false);

                    /* 方案：Gitee 首源已在上方试过，这里只剩 GitHub 分块（4MB + Range 续传）。 */

                    /* 先用 1 次 API 请求换出签名直链。拿不到就别开线程池：
                       3 个线程各打一次 API 只会把仅剩的配额烧光，还看不出原因。 */
                    cdn(m.assetId);

                    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
                    for (int i = 0; i < parts; i++) {
                        final int idx = i;
                        pool.execute(new Runnable() {
                            @Override public void run() {
                                if (err.get() != null) return;
                                long start = (long) idx * CHUNK;
                                long end = Math.min(m.size - 1, start + CHUNK - 1);
                                long expect = end - start + 1;
                                File f = new File(pdir, idx + ".bin");
                                if (f.isFile() && f.length() == expect) {
                                    bump(host, m, done, expect);
                                    return;
                                }
                                for (int a = 0; a < RETRIES; a++) {
                                    HttpURLConnection c = null;
                                    try {
                                        /* 有直链就打直链（不吃配额）；没有才退回逐块 API。 */
                                        String direct = currentCdn();
                                        c = openRange(direct != null ? direct : apiAssetUrl(m.assetId),
                                                start, end);
                                        int code = c.getResponseCode();
                                        if (direct != null && (code == 403 || code == 404)) {
                                            /* 签名链接过期（约 5 分钟）或被 CDN 拒：丢缓存，下一次重试重新解析。 */
                                            invalidateCdn();
                                            throw new IllegalStateException("直链过期，正在重取");
                                        }
                                        if (code == 403 || code == 429) {
                                            throw new IllegalStateException(quotaText(c, code));
                                        }
                                        if (code != 206) throw new IllegalStateException("HTTP " + code);
                                        InputStream in = c.getInputStream();
                                        FileOutputStream out = new FileOutputStream(f);
                                        byte[] buf = new byte[64 * 1024];
                                        int n;
                                        long got = 0;
                                        while ((n = in.read(buf)) > 0) {
                                            out.write(buf, 0, n);
                                            got += n;
                                        }
                                        out.close();
                                        in.close();
                                        if (got != expect) throw new IllegalStateException("分块长度不符");
                                        bump(host, m, done, expect);
                                        return;
                                    } catch (Throwable t) {
                                        if (f.exists()) f.delete();
                                        if (a == RETRIES - 1) {
                                            err.compareAndSet(null, friendly(t));
                                        }
                                    } finally {
                                        if (c != null) c.disconnect();
                                    }
                                }
                            }
                        });
                    }
                    pool.shutdown();
                    pool.awaitTermination(30, TimeUnit.MINUTES);
                    if (err.get() != null) {
                        /* 已完成的块保留在 .parts/，再点一次下载即可续传。 */
                        emit(host, m.id, "error", 0,
                                "下载中断：" + err.get() + "（再点一次可续传）", false);
                        return;
                    }

                    File part = new File(asrRoot(ctx), m.id + ".zip.part");
                    FileOutputStream out = new FileOutputStream(part);
                    for (int i = 0; i < parts; i++) {
                        File f = new File(pdir, i + ".bin");
                        if (!f.isFile()) throw new IllegalStateException("缺少分块 " + i);
                        FileInputStream in = new FileInputStream(f);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                    }
                    out.close();
                    if (part.length() != m.size) throw new IllegalStateException("文件大小不符，请重试");
                    emit(host, m.id, "unzipping", 100, "校验并解压…", false);
                    if (!sha256(part).equalsIgnoreCase(m.sha256)) {
                        part.delete();
                        throw new IllegalStateException("校验失败，请重试");
                    }
                    unzip(ctx, m, part);
                    part.delete();
                    deleteRec(pdir);
                    /* 第一次下载完直接设为当前，省掉用户再点一次「设为当前」。 */
                    if (selectedId(ctx).isEmpty()) {
                        prefs(ctx).edit().putString(KEY_SELECTED, m.id).apply();
                    }
                    emit(host, m.id, "installed", 100, "已安装，可开 CC 使用", true);
                } catch (Throwable t) {
                    emit(host, m.id, "error", 0,
                            t.getMessage() == null ? "安装失败" : t.getMessage(), false);
                } finally {
                    RUNNING.remove(m.id);
                }
        }
    }

    /** 服务侧查询：这个模型此刻是否正在下载（跨进程时以磁盘分块进度为准）。 */
    public static boolean isRunning(String id) {
        return RUNNING.contains(id);
    }

    public static void select(final Context ctx, final AsrEngine.Host host, final String id) {
        if (!installed(ctx, id)) {
            emit(host, id, "error", 0, "请先下载该模型", false);
            return;
        }
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply();
        /* 重新启用：彻底释放常驻的 native 识别器，下次 start 重建。 */
        AsrEngine.release();
        TrackBuilder.releaseShared();
        emit(host, id, "installed", 100, "已设为当前模型", true);
    }

    public static void delete(final Context ctx, final AsrEngine.Host host, final String id) {
        final Model m = find(id);
        if (m == null) return;
        EX.execute(new Runnable() {
            @Override public void run() {
                boolean was = m.id.equals(selectedId(ctx));
                deleteRec(modelDir(ctx, m.id));
                deleteRec(new File(asrRoot(ctx), m.id + ".tmp"));
                deleteRec(new File(asrRoot(ctx), m.id + ".parts"));
                new File(asrRoot(ctx), m.id + ".zip.part").delete();
                new File(asrRoot(ctx), m.id + ".zip").delete();
                if (was) {
                    prefs(ctx).edit().remove(KEY_SELECTED).apply();
                    /* 删掉的正是当前模型：释放 native 识别器，避免残留指向已删文件。 */
                    AsrEngine.release();
                    TrackBuilder.releaseShared();
                }
                emit(host, m.id, "idle", 0, "已删除", true);
            }
        });
    }

    /**
     * 实际使用的下载地址。debug 构建可被 {@code files/asr/.model_url} 覆盖
     * （一行 URL），用于本地起 HTTP 服务做整条「下载→校验→解压→识别」回归，
     * 免得每次验证都要先传一遍公网资产。Release 构建只认编译期常量。
     */
    private static String effectiveUrl(Context c, Model m) {
        String u = m.url == null ? "" : m.url;
        if (!BuildConfig.DEBUG) return u;
        File f = new File(new File(c.getFilesDir(), "asr"), ".model_url");
        if (!f.isFile()) return u;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            String s = br.readLine();
            br.close();
            return s == null ? u : s.trim();
        } catch (Throwable t) {
            return u;
        }
    }

    /**
     * 直链下载整包：单连接 + Range 断点续传；服务端不认 Range（回 200）时整包重下。
     * 落盘到 {@code <id>.zip.part}，长度与 SHA-256 双校验通过才解压。
     */
    private static boolean urlDownload(Context ctx, AsrEngine.Host host, Model m, String url) {
        File part = new File(asrRoot(ctx), m.id + ".zip.part");
        long have = part.isFile() ? part.length() : 0;
        if (have > m.size) {
            part.delete();
            have = 0;
        }
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("User-Agent", "GIme/" + BuildConfig.VERSION_NAME);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code == 206) {
                int pct = (int) (have * 100 / m.size);
                emit(host, m.id, "downloading", Math.min(99, pct),
                        "续传中 " + Math.min(99, pct) + "%", false);
            } else if (code == 200) {
                /* 不支持 Range：半截作废，从头下。 */
                have = 0;
                if (part.exists() && !part.delete()) {
                    throw new IllegalStateException("无法清理旧的半截文件");
                }
                emit(host, m.id, "downloading", 0, "下载中 0%", false);
            } else {
                throw new IllegalStateException("HTTP " + code);
            }
            in = c.getInputStream();
            out = new FileOutputStream(part, have > 0);
            byte[] buf = new byte[64 * 1024];
            long seg = have, lastEmit = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                seg += n;
                if (seg - lastEmit >= 1024 * 1024) {
                    lastEmit = seg;
                    int pct = (int) Math.min(99, seg * 100 / m.size);
                    emit(host, m.id, "downloading", pct, "下载中 " + pct + "%", false);
                }
            }
            out.close();
            out = null;
            in.close();
            in = null;
            if (seg != m.size) throw new IllegalStateException("文件大小不符，请重试");

            emit(host, m.id, "unzipping", 100, "校验并解压…", false);
            if (!sha256(part).equalsIgnoreCase(m.sha256)) {
                part.delete();
                throw new IllegalStateException("校验失败，请重试");
            }
            unzip(ctx, m, part);
            part.delete();
            if (selectedId(ctx).isEmpty()) {
                prefs(ctx).edit().putString(KEY_SELECTED, m.id).apply();
            }
            emit(host, m.id, "installed", 100, "已安装，可开 CC 使用", true);
            return true;
        } catch (Throwable t) {
            /* 已下字节留在 .zip.part，再点一次下载即从断点续。 */
            emit(host, m.id, "error", 0,
                    (t.getMessage() == null ? "下载失败" : t.getMessage()) + "（再点一次可续传）", false);
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (c != null) c.disconnect();
        }
    }

    /** Gitee Release 里的模型包地址（国内中转，二进制只能走 Release 附件）。 */
    private static String giteeUrl(Model m, String suffix) {
        return "https://gitee.com/hagemilab/gi/releases/download/v1.1.8-models/" + m.zip + suffix;
    }

    /** 超过 Gitee 单附件 100MB 上限的包拆成多卷；返回 null 表示单包。
     *  Gitee 附件下载不支持 Range、且单连接 30MB 以上容易提前断流，故按 20MB 一卷。
     *  169MB 实际是 9 卷（前 8 卷各 20MB + 末卷 795KB），与 tools/split-model.py 的切法一致。 */
    private static String[] giteeParts(Model m) {
        if ("xasr".equals(m.id)) {
            return new String[]{".p1", ".p2", ".p3", ".p4", ".p5",
                    ".p6", ".p7", ".p8", ".p9"};
        }
        return null;
    }

    /** 分卷目录里已落盘的字节数：中断后给用户一个「存了多少、能不能续」的实数。 */
    private static long giteeDone(Context ctx, Model m) {
        File gdir = new File(asrRoot(ctx), m.id + ".gparts");
        long got = 0;
        if (gdir.isDirectory()) {
            for (int i = 0; ; i++) {
                File f = new File(gdir, i + ".bin");
                if (!f.isFile()) break;
                got += f.length();
            }
        }
        return Math.min(got, m.size);
    }

    /**
     * 从 Gitee 分卷下载（该入口不支持 Range，只能按整卷重下）。
     * 断点按「卷」记在 {@code <id>.gparts/}：下好一卷就落盘，中断后再点一次只补没下完的卷。
     * 成功（拼接后长度 + SHA-256 校验通过并解压）返回 true；任一卷失败返回 false（已下的卷留着）。
     */
    private static boolean giteeSingle(Context ctx, AsrEngine.Host host, Model m) {
        String[] parts = giteeParts(m);
        if (parts == null) parts = new String[]{""};
        File gdir = new File(asrRoot(ctx), m.id + ".gparts");
        if (!gdir.isDirectory() && !gdir.mkdirs()) return false;
        File zip = new File(asrRoot(ctx), m.id + ".zip.part");
        try {
            long got = 0;
            for (int i = 0; i < parts.length; i++) {
                long want = Math.min(GPART, m.size - (long) i * GPART);
                File f = new File(gdir, i + ".bin");
                if (f.isFile() && f.length() == want) { got += want; continue; }
                if (f.exists()) f.delete();
                int pct = (int) (got * 100 / m.size);
                emit(host, m.id, "downloading", pct, "下载中 " + pct + "%（镜像）", false);
                if (!fetchPart(host, m, giteeUrl(m, parts[i]), f, want, got)) return false;
                got += want;
            }

            /* 卷都齐了再拼成整包，长度和 SHA-256 都过了才算数。 */
            FileOutputStream out = new FileOutputStream(zip);
            byte[] buf = new byte[64 * 1024];
            try {
                for (int i = 0; i < parts.length; i++) {
                    FileInputStream in = new FileInputStream(new File(gdir, i + ".bin"));
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                }
            } finally {
                out.close();
            }
            if (zip.length() != m.size) { zip.delete(); return false; }
            emit(host, m.id, "unzipping", 100, "校验并解压…", false);
            if (!sha256(zip).equalsIgnoreCase(m.sha256)) {
                zip.delete();
                deleteRec(gdir);   /* 内容已经不对，重下更省事 */
                return false;
            }
            unzip(ctx, m, zip);
            zip.delete();
            deleteRec(gdir);
            if (selectedId(ctx).isEmpty()) {
                prefs(ctx).edit().putString(KEY_SELECTED, m.id).apply();
            }
            emit(host, m.id, "installed", 100, "已安装，可开 CC 使用", true);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (zip.exists() && zip.length() != m.size) zip.delete();
        }
    }

    /** 单卷下载：先写 .part 再改名，半截文件不会冒充已完成。 */
    private static boolean fetchPart(AsrEngine.Host host, Model m, String url, File dest,
                                     long want, long baseGot) {
        HttpURLConnection c = null;
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        FileOutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "GIme/" + BuildConfig.VERSION_NAME);
            if (c.getResponseCode() != 200) return false;
            long len = c.getContentLength();
            InputStream in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            long seg = 0, lastEmit = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                seg += n;
                if (seg - lastEmit > 512 * 1024) {
                    lastEmit = seg;
                    int pct = (int) ((baseGot + seg) * 100 / m.size);
                    emit(host, m.id, "downloading", pct, "下载中 " + pct + "%（镜像）", false);
                }
            }
            out.close();
            out = null;
            in.close();
            if (len > 0 && seg != len) return false;
            if (seg != want) return false;
            return tmp.renameTo(dest);
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            if (c != null) c.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    private static String apiAssetUrl(long assetId) {
        return "https://api.github.com/repos/" + REPO + "/releases/assets/" + assetId;
    }

    private static HttpURLConnection openRange(String url, long start, long end) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setRequestProperty("User-Agent", "GIme-ASR/1.0");
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        return c;
    }

    /**
     * 用 1 次 API 请求问出资产签名直链（302 的 Location）。整轮下载只调用一次，
     * 40 个分块全部打这条直链，配额消耗从 40 次降到 1 次。
     *
     * <p>配额用完（403/429）时抛异常并把恢复时间写进消息；解析不出直链返回 null，
     * 由调用方退回「逐块走 API」的老路径。
     */
    private static String cdn(long assetId) {
        synchronized (CDN_LOCK) {
            if (quotaUntil > 0 && System.currentTimeMillis() / 1000 < quotaUntil) {
                throw new IllegalStateException(quotaMsg == null ? "仓库配额用完，稍后可续传" : quotaMsg);
            }
            if (cdnUrl != null && System.currentTimeMillis() - cdnAt < CDN_TTL) return cdnUrl;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(apiAssetUrl(assetId)).openConnection();
                c.setInstanceFollowRedirects(false);      /* 只要 Location，不跟过去，省一次下载 */
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept", "application/octet-stream");
                c.setRequestProperty("User-Agent", "GIme-ASR/1.0");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc != null && loc.length() > 0) {
                        cdnUrl = loc;
                        cdnAt = System.currentTimeMillis();
                        return loc;
                    }
                    return null;
                }
                if (code == 403 || code == 429) throw new IllegalStateException(quotaText(c, code));
                return null;   /* 200 或其它码：没有直链，退回逐块 API 去暴露具体状态 */
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable t) {
                return null;   /* 网络抖动：别把整轮判死，交给逐块请求去判断 */
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private static String currentCdn() {
        synchronized (CDN_LOCK) {
            if (cdnUrl != null && System.currentTimeMillis() - cdnAt < CDN_TTL) return cdnUrl;
            return null;
        }
    }

    private static void invalidateCdn() {
        synchronized (CDN_LOCK) {
            cdnUrl = null;
            cdnAt = 0;
        }
    }

    /** 把 403/429 翻译成人话：仓库配额没用 token，按出口 IP 只有 60 次/小时。 */
    private static String quotaText(HttpURLConnection c, int code) {
        long until = 0;
        String reset = c.getHeaderField("X-RateLimit-Reset");
        if (reset != null && reset.trim().length() > 0) {
            try { until = Long.parseLong(reset.trim()); } catch (Throwable ignored) { }
        }
        if (until <= 0) {
            String retry = c.getHeaderField("Retry-After");
            if (retry != null && retry.trim().length() > 0) {
                try { until = System.currentTimeMillis() / 1000 + Long.parseLong(retry.trim()); }
                catch (Throwable ignored) { }
            }
        }
        String when = "";
        if (until > 0) {
            when = "，" + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(until * 1000L)) + " 后";
        }
        String msg = code == 429
                ? "仓库限流（请求过快）" + when + "可续传"
                : "仓库配额用完（未加令牌时 60 次/小时）" + when + "可续传";
        if (until > 0) {
            quotaUntil = until;
            quotaMsg = msg;
        }
        return msg;
    }

    private static void bump(AsrEngine.Host host, Model m, AtomicLong done, long add) {
        long d = done.addAndGet(add);
        int pct = (int) (d * 100 / m.size);
        if (pct > 100) pct = 100;
        emit(host, m.id, "downloading", pct, "下载中 " + pct + "%", false);
    }

    private static void unzip(Context ctx, Model m, File zip) throws Exception {
        File target = modelDir(ctx, m.id);
        File tmp = new File(asrRoot(ctx), m.id + ".tmp");
        deleteRec(tmp);
        if (!tmp.mkdirs()) throw new IllegalStateException("无法创建解压目录");
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    /* 只取文件名，挡掉 ../ 这类 Zip Slip。 */
                    String name = new File(e.getName()).getName();
                    if (name.length() > 0) {
                        FileOutputStream fo = new FileOutputStream(new File(tmp, name));
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = zis.read(buf)) > 0) fo.write(buf, 0, n);
                        fo.close();
                    }
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        for (String f : m.files) {
            File x = new File(tmp, f);
            if (!x.isFile() || x.length() <= 0) throw new IllegalStateException("模型文件缺失：" + f);
        }
        deleteRec(target);
        if (!tmp.renameTo(target)) throw new IllegalStateException("无法保存模型");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ks = f.listFiles();
            if (ks != null) for (File k : ks) deleteRec(k);
        }
        f.delete();
    }

    /** 把底层异常翻成用户能看懂的一句，别把 UnknownHost 原文糊到界面上。 */
    private static String friendly(Throwable t) {
        if (t == null) return "网络失败";
        Throwable c = t;
        for (int i = 0; i < 3 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause();
        String m = c.getMessage() == null ? "" : c.getMessage();
        String cls = c.getClass().getSimpleName();
        if (c instanceof UnknownHostException || m.contains("No address associated")) return "网络未连接";
        if (c instanceof SocketTimeoutException || m.contains("timed out")) return "网络超时";
        if (c instanceof ConnectException) return "连不上服务器";
        if (cls.contains("SSL") || m.contains("Connection reset")) return "网络连接被重置";
        if (c instanceof EOFException) return "连接被中断";
        if (c instanceof IOException) return "网络中断";
        return m.isEmpty() ? "网络失败" : m;
    }

    private static void emit(AsrEngine.Host host, String id, String state, int pct, String msg, boolean refresh) {
        try {
            host.js("window.giModelStatus&&giModelStatus(" + new JSONObject()
                    .put("id", id).put("state", state).put("pct", pct)
                    .put("msg", msg).put("refresh", refresh).toString() + ")");
        } catch (Exception ignored) { }
    }
}
