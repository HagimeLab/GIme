package com.fan.shuatiku;

import android.content.Context;
import android.util.Base64;

import java.io.File;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig;
import com.k2fsa.sherpa.onnx.OnlineLMConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * X-ASR 的实时识别引擎与 sherpa 回退。
 *
 * <p>模型不随 APK 打包，由「我的 → 字幕模型」从仓库 v1.1.8 测试标记下载到
 * files/asr/xasr/，这里按绝对路径用 newFromFile 加载；实现固定为
 * 在线流式 Zipformer2 transducer。
 *
 * <p>页面用 Web Audio 抓 16k/16bit/单声道 PCM，经 Bridge.asrPcm 把 Base64 送进来；
 * 这里转成 float32 [-1,1] 喂识别器，结果回主线程 evaluateJavascript 到页面的 #biliCap。
 * 识别全程离线，不上传任何音频（只有下载模型时联网）。
 */
public final class AsrEngine {

    public interface Host {
        /** 在主线程执行一段 JS。 */
        void js(String code);
    }

    private static final int SAMPLE_RATE = 16000;
    /** 单线程解码队列的积压上限：超过就丢新块，宁可少几个字也不让字幕越拖越滞后。
     *  X-ASR 153MB 在这台手机上 RTF 接近 1，放宽到 10 会让队列长期顶满（实测 q 恒 10），
     *  端到端延迟堆到 2~4 秒且大量丢块；保持 3 才能把延迟压在 ~0.4 秒。 */
    private static final int MAX_PENDING = 3;

    /** Rust 加速框架开关：true 走 xasr-jni，false 走 sherpa。
     *  可用 files/asr/.engine 文件写 "rust" / "sherpa" 覆盖（debug 生效），便于 A/B。 */
    private static volatile boolean useRust = false;
    /** Rust 会话句柄（0 = 未创建）。 */
    private static volatile long rustHandle = 0L;

    private static final AtomicInteger PEND = new AtomicInteger(0);
    private static volatile OnlineRecognizer recognizer;
    private static volatile OnlineStream stream;
    /** 当前常驻的识别器加载的是哪套模型；换模型时据此失效缓存。 */
    private static volatile String cachedId = null;
    /** 同一段 partial 会连续多帧重复，跳过重复回调，省掉无意义的 JS 往返。 */
    private static volatile String lastText = "";

    private static final ExecutorService EX = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(final Runnable r) {
            return new Thread(new Runnable() {
                @Override public void run() {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                    r.run();
                }
            }, "gime-asr");
        }
    });

    private AsrEngine() { }

    /** 幂等 + 识别器缓存：模型没换就复用 native 资源，避免每次开 CC 重加载大模型。 */
    public static void start(final Context ctx, final Host host) {
        EX.execute(new Runnable() {
            @Override public void run() {
                String id = AsrModels.selectedId(ctx);
                /* 缓存命中：关 CC 时只 reset 不 release，模型常驻，
                   再开 CC 直接复用，首字延迟从 ~11 秒降到 ~0.4 秒。 */
                if (id.equals(cachedId)) {
                    boolean ready = rustHandle != 0L || (recognizer != null && stream != null);
                    if (ready) {
                        synchronized (AsrEngine.class) { lastText = ""; }
                        status(host, "字幕已就绪");
                        return;
                    }
                }
                /* 首次加载：先彻底释放旧的，避免两份大模型同时占内存。 */
                releaseLocked();
                try {
                    status(host, "正在初始化识别器…");
                    if (!AsrModels.installed(ctx, id)) {
                        status(host, "请先在「我的 → 字幕模型」里下载模型");
                        return;
                    }
                    File dir = AsrModels.modelDir(ctx, id);
                    if (shouldUseRust(ctx, dir)) {
                        if (!startRust(ctx, dir, id, host)) {
                            startOnline(dir, id, host);
                        }
                    } else {
                        startOnline(dir, id, host);
                    }
                } catch (Throwable t) {
                    status(host, "识别器初始化失败：" + t.getMessage());
                }
            }
        });
    }

    /** 在线流式：X-ASR 480ms Zipformer2 transducer。 */
    private static void startOnline(File dir, String id, Host host) {
        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(SAMPLE_RATE);
        feat.setFeatureDim(80);
        feat.setDither(0f);

        OnlineModelConfig model = new OnlineModelConfig();
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(new File(dir, "encoder-epoch-99-avg-1.int8.onnx").getAbsolutePath());
        /* 官方 int8 组合刻意用 fp32 decoder（int8 decoder 精度损失明显）。 */
        transducer.setDecoder(new File(dir, "decoder-epoch-99-avg-1.onnx").getAbsolutePath());
        transducer.setJoiner(new File(dir, "joiner-epoch-99-avg-1.int8.onnx").getAbsolutePath());
        model.setTransducer(transducer);
        model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
        model.setModelType(AsrModels.MODEL_TYPE);
        model.setModelingUnit("");
        int threads = asrThreads(dir);
        model.setNumThreads(threads);
        model.setDebug(false);
        model.setProvider("cpu");
        model.setBpeVocab("");
        if (BuildConfig.DEBUG) android.util.Log.i("GIME_ASR", "startOnline id=" + id + " threads=" + threads);

        EndpointConfig endpoint = new EndpointConfig();
        endpoint.setRule1(new EndpointRule(false, 2.4f, 0f));
        endpoint.setRule2(new EndpointRule(true, 1.4f, 0f));
        endpoint.setRule3(new EndpointRule(false, 0f, 20f));

        OnlineRecognizerConfig cfg = new OnlineRecognizerConfig();
        cfg.setFeatConfig(feat);
        cfg.setModelConfig(model);
        cfg.setLmConfig(new OnlineLMConfig());
        cfg.setCtcFstDecoderConfig(new OnlineCtcFstDecoderConfig());
        cfg.setHr(new HomophoneReplacerConfig());
        cfg.setEndpointConfig(endpoint);
        cfg.setEnableEndpoint(true);
        cfg.setDecodingMethod("greedy_search");

        /* AssetManager 传 null → native 走 newFromFile，读上面的绝对路径。 */
        OnlineRecognizer rec = new OnlineRecognizer(null, cfg);
        OnlineStream s = rec.createStream("");
        synchronized (AsrEngine.class) {
            recognizer = rec;
            stream = s;
            cachedId = id;
            lastText = "";
        }
        status(host, "字幕就绪，开始识别");
    }

    /** 收一段 Base64 的 16k/16bit 小端 PCM；解码放后台线程，不卡 WebView。 */
    public static void feed(final String b64, final Host host) {
        if (b64 == null || b64.length() == 0) return;
        if (PEND.get() >= MAX_PENDING) return;   /* 背压：解码跟不上时丢新块，保实时 */
        PEND.incrementAndGet();
        EX.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] pcm = Base64.decode(b64, Base64.DEFAULT);
                    int n = pcm.length / 2;
                    float[] f = new float[n];
                    for (int i = 0; i < n; i++) {
                        int lo = pcm[2 * i] & 0xff;
                        int hi = pcm[2 * i + 1];
                        f[i] = ((short) ((hi << 8) | lo)) / 32768.0f;
                    }
                    if (rustHandle != 0L) {
                        feedRust(f, host);
                    } else {
                        feedOnline(f, host);
                    }
                } catch (Throwable t) {
                    status(host, "识别异常：" + t.getMessage());
                } finally {
                    PEND.decrementAndGet();
                }
            }
        });
    }

    /** 解码线程数。默认 2 = 这颗 SoC 的大核个数（cpu6/7），不是拍的：
     *  2026-11-08 同机同 X-ASR 480ms 逐档实测，单次解码均值
     *  1→122.7ms / 2→121.3ms / 3→172.5ms / 4→186.5ms / 8→264.8ms。
     *  第 3 条线程必然落到小核当 straggler，ORT 的 barrier 等它，整批被拖慢——
     *  悬崖在 2→3。改大之前先重测，别照抄官方 demo 的 4。
     *
     *  <p>调试期可用 files/asr/.threads 写 1..8 覆盖（只 debug 生效）。 */
    private static int asrThreads(File dir) {
        int n = 2;
        if (!BuildConfig.DEBUG || dir == null) return n;
        File f = new File(dir.getParentFile(), ".threads");
        if (!f.isFile()) return n;
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            int v = Integer.parseInt(br.readLine().trim());
            if (v >= 1 && v <= 8) n = v;
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) { }
        }
        return n;
    }

    /** 仅 debug：用 10 分钟 PCM 测整段字幕生成耗时（TrackBuilder 全链路）。 */
    public static void benchTrack(Context ctx, Host host, String pcmName) {
        String tag = "XASR_SELFTEST";
        try {
            File f = new File(ctx.getFilesDir(), "asr/" + pcmName);
            if (!f.isFile()) { android.util.Log.e(tag, "bench pcm missing: " + f); return; }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) { int r = in.read(b, off, b.length - off); if (r <= 0) break; off += r; }
            in.close();
            int n = b.length / 2;
            float[] pcm = new float[n];
            for (int i = 0; i < n; i++) {
                int lo = b[2*i] & 0xff, hi = b[2*i+1];
                pcm[i] = ((short) ((hi << 8) | lo)) / 32768.0f;
            }
            double audio = n / 16000.0;
            android.util.Log.i(tag, "BENCH start pcm=" + pcmName + " audio=" + audio + "s samples=" + n);
            long t0 = System.nanoTime();
            org.json.JSONArray segs = TrackBuilder.build(ctx, pcm, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            android.util.Log.i(tag, "BENCH done segs=" + segs.length()
                    + " audio=" + audio + "s ms=" + ms
                    + " rtf=" + String.format(java.util.Locale.US, "%.3f", (ms/1000.0)/audio)
                    + " 每10分钟≈" + Math.round(ms/1000.0) + "s");
            if (segs.length() > 0) {
                org.json.JSONObject last = segs.getJSONObject(segs.length()-1);
                android.util.Log.i(tag, "BENCH tail=[" + last.optDouble("s") + "-" + last.optDouble("e") + "] "
                        + last.optString("t"));
            }
            status(host, "10分钟测试完成：" + segs.length() + " 条 / " + (ms/1000.0) + " 秒");
        } catch (Throwable t) {
            android.util.Log.e(tag, "bench failed", t);
        } finally {
            TrackBuilder.releaseShared();
        }
    }

    /** 仅 debug：跑一遍整段字幕轨生成（TrackBuilder 路径），验证 Rust 批处理链路。 */
    public static void selfTestTrack(Context ctx, Host host) {
        String tag = "XASR_SELFTEST";
        TrackBuilder.dbg(ctx, "selftest enter");
        try {
            float[] pcm = loadTestPcm(ctx);
            if (pcm == null) {
                android.util.Log.e(tag, "test pcm missing");
                return;
            }
            long t0 = System.nanoTime();
            org.json.JSONArray segs = TrackBuilder.build(ctx, pcm, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            double audio = pcm.length / 16000.0;
            StringBuilder sb = new StringBuilder();
            int n = Math.min(3, segs.length());
            for (int i = 0; i < n; i++) {
                org.json.JSONObject o = segs.getJSONObject(i);
                sb.append(String.format(java.util.Locale.US, "[%.1f-%.1f]%s ",
                        o.optDouble("s"), o.optDouble("e"), o.optString("t")));
            }
            android.util.Log.i(tag, "TRACK segs=" + segs.length()
                    + " audio=" + audio + "s ms=" + ms
                    + " rtf=" + String.format(java.util.Locale.US, "%.3f", (ms / 1000.0) / audio)
                    + " head=" + sb);
            status(host, "整段字幕自检：segs=" + segs.length() + " ms=" + ms);
        } catch (Throwable t) {
            android.util.Log.e(tag, "track selftest failed", t);
        } finally {
            TrackBuilder.releaseShared();
        }
    }

    /** 仅 debug：用内置 PCM 直接跑 Rust 引擎，验证 JNI 与流式解码链路。
     *  不依赖视频播放与 B 站登录。结果写 logcat XASR_SELFTEST。 */
    public static void selfTest(Context ctx, Host host) {
        selfTest(ctx, host, "rust");
    }

    /** engine = "rust" 或 "sherpa"，用同一段真实语音做 A/B。 */
    public static void selfTest(Context ctx, Host host, String engine) {
        String tag = "XASR_SELFTEST";
        if ("sherpa".equals(engine)) {
            selfTestSherpa(ctx, host, tag);
            return;
        }
        try {
            String err = XasrNative.ensureLoaded(ctx);
            if (err != null) {
                android.util.Log.e(tag, "load failed: " + err);
                status(host, "自检失败：" + err);
                return;
            }
            android.util.Log.i(tag, "lib loaded ok");

            File dir = AsrModels.modelDir(ctx, AsrModels.MODEL_ID);
            if (!new File(dir, "encoder-epoch-99-avg-1.int8.onnx").isFile()
                    && !new File(dir, "encoder.onnx").isFile()) {
                android.util.Log.e(tag, "model missing: " + dir);
                status(host, "自检失败：模型缺失");
                return;
            }
            String abs = ctx.getApplicationInfo().nativeLibraryDir + "/libonnxruntime.so";
            String ort = new File(abs).isFile() ? abs : "libonnxruntime.so";

            long t0 = System.nanoTime();
            DeviceProfile dp0 = DeviceProfile.of(ctx);
            long h = XasrNative.create(dir.getAbsolutePath(), ort, dp0.threads, dp0.batch, dp0.spin);
            long loadMs = (System.nanoTime() - t0) / 1_000_000;
            if (h == 0L) {
                android.util.Log.e(tag, "create returned 0");
                status(host, "自检失败：create 返回 0");
                return;
            }
            android.util.Log.i(tag, "create ok load=" + loadMs + "ms");

            float[] pcm = loadTestPcm(ctx);
            if (pcm == null) {
                android.util.Log.e(tag, "test pcm missing (files/asr/test60.pcm)");
                status(host, "自检失败：缺测试音频");
                return;
            }
            int n = pcm.length;
            long t1 = System.nanoTime();
            int feeds = 0;
            StringBuilder last = new StringBuilder();
            for (int off = 0; off < n; off += 2080) {   /* 130ms 块，与 GIme 一致 */
                int end = Math.min(n, off + 2080);
                float[] chunk = new float[end - off];
                System.arraycopy(pcm, off, chunk, 0, end - off);
                String t = XasrNative.feed(h, chunk);
                feeds++;
                last.setLength(0);
                if (t != null) last.append(t);
            }
            long feedMs = (System.nanoTime() - t1) / 1_000_000;
            String fin = XasrNative.finish(h);
            XasrNative.destroy(h);

            android.util.Log.i(tag, "RUST feeds=" + feeds + " feedMs=" + feedMs
                    + " rtf=" + String.format(java.util.Locale.US, "%.3f", (feedMs / 1000.0) / (n / 16000.0))
                    + " text=" + (fin == null ? "" : fin));
            status(host, "自检通过：load=" + loadMs + "ms feed=" + feedMs + "ms");
        } catch (Throwable t) {
            android.util.Log.e(tag, "exception", t);
            status(host, "自检异常：" + t);
        }
    }

    /** 读 files/asr/test60.pcm（16k/16bit 小端单声道）。 */
    private static float[] loadTestPcm(Context ctx) {
        File f = new File(ctx.getFilesDir(), "asr/test60.pcm");
        if (!f.isFile()) return null;
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r <= 0) break;
                off += r;
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
        int n = b.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = b[2 * i] & 0xff;
            int hi = b[2 * i + 1];
            out[i] = ((short) ((hi << 8) | lo)) / 32768.0f;
        }
        return out;
    }

    /** sherpa 对照：同段音频走 OnlineRecognizer，量纯解码耗时。 */
    private static void selfTestSherpa(Context ctx, Host host, String tag) {
        try {
            File dir = AsrModels.modelDir(ctx, AsrModels.MODEL_ID);
            float[] pcm = loadTestPcm(ctx);
            if (pcm == null) { android.util.Log.e(tag, "pcm missing"); return; }
            long t0 = System.nanoTime();
            startOnline(dir, AsrModels.MODEL_ID, host);
            long loadMs = (System.nanoTime() - t0) / 1_000_000;
            OnlineRecognizer rec = recognizer;
            OnlineStream s = stream;
            if (rec == null || s == null) { android.util.Log.e(tag, "sherpa init failed"); return; }
            long t1 = System.nanoTime();
            for (int off = 0; off < pcm.length; off += 2080) {
                int end = Math.min(pcm.length, off + 2080);
                float[] chunk = new float[end - off];
                System.arraycopy(pcm, off, chunk, 0, end - off);
                s.acceptWaveform(chunk, SAMPLE_RATE);
                while (rec.isReady(s)) rec.decode(s);
            }
            long feedMs = (System.nanoTime() - t1) / 1_000_000;
            String text = rec.getResult(s).getText();
            android.util.Log.i(tag, "SHERPA load=" + loadMs + "ms feedMs=" + feedMs
                    + " rtf=" + String.format(java.util.Locale.US, "%.3f", (feedMs / 1000.0) / (pcm.length / 16000.0))
                    + " text=" + text);
            releaseLocked();
        } catch (Throwable t) {
            android.util.Log.e(tag, "sherpa selftest failed", t);
        }
    }

    /** 是否走 Rust 引擎。默认关闭；files/asr/.engine 写 rust 才开（debug 生效）。 */
    private static boolean shouldUseRust(Context ctx, File dir) {
        boolean on = false;
        if (BuildConfig.DEBUG && dir != null) {
            File f = new File(dir.getParentFile(), ".engine");
            if (f.isFile()) {
                java.io.BufferedReader br = null;
                try {
                    br = new java.io.BufferedReader(new java.io.FileReader(f));
                    String v = br.readLine();
                    on = v != null && v.trim().equalsIgnoreCase("rust");
                } catch (Throwable ignored) {
                } finally {
                    try { if (br != null) br.close(); } catch (Throwable ignored) { }
                }
            }
        }
        if (!on) return false;
        String err = XasrNative.ensureLoaded(ctx);
        if (err != null) {
            android.util.Log.w("GIME_ASR", "Rust 引擎不可用，回退 sherpa：" + err);
            return false;
        }
        return true;
    }

    /** 创建 Rust 会话；失败返回 false，调用方回退 sherpa。 */
    private static boolean startRust(Context ctx, File dir, String id, Host host) {
        releaseRust();
        int threads = asrThreads(dir);
        String abs = ctx.getApplicationInfo().nativeLibraryDir + "/libonnxruntime.so";
        String ort = new java.io.File(abs).isFile() ? abs : "libonnxruntime.so";
        long t0 = System.nanoTime();
        DeviceProfile dp = DeviceProfile.of(ctx);
        long h = XasrNative.create(dir.getAbsolutePath(), ort, threads, dp.batch, dp.spin);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (h == 0L) {
            status(host, "Rust 引擎初始化失败，已回退 sherpa");
            return false;
        }
        synchronized (AsrEngine.class) {
            rustHandle = h;
            cachedId = id;
            lastText = "";
        }
        android.util.Log.i("GIME_ASR", "startRust id=" + id + " threads=" + threads
                + " load=" + ms + "ms");
        status(host, "字幕就绪（Rust 加速）");
        return true;
    }

    private static void feedRust(float[] f, Host host) {
        long h = rustHandle;
        if (h == 0L) return;
        long t0 = System.nanoTime();
        String text = XasrNative.feed(h, f);
        long t1 = System.nanoTime();
        if (BuildConfig.DEBUG) {
            android.util.Log.i("GIME_ASR", "rust smp=" + f.length
                    + " dec=" + (t1 - t0) / 1000 + "us q=" + PEND.get());
        }
        if (text == null) text = "";
        text = text.replace("<unk>", "").trim();
        /* 与 sherpa 路径保持一致的定稿规则：18 字主动切行。 */
        boolean endpoint = text.length() >= 18;
        if (!text.isEmpty() && !text.equals(lastText)) {
            lastText = text;
            emit(host, text, endpoint, PEND.get());
        } else if (endpoint && !text.isEmpty()) {
            emit(host, text, true, PEND.get());
        }
        if (endpoint) {
            /* 只推进"当前句"起点，不清 encoder 状态 —— 清了下一句要重新等一个窗口。 */
            XasrNative.cut(h);
            lastText = "";
        }
    }

    private static void releaseRust() {
        long h = rustHandle;
        if (h != 0L) {
            rustHandle = 0L;
            try { XasrNative.destroy(h); } catch (Throwable ignored) { }
        }
    }

    private static void feedOnline(float[] f, Host host) {
        OnlineRecognizer rec = recognizer;
        OnlineStream s = stream;
        if (rec == null || s == null) return;

        long t0 = System.nanoTime();
        s.acceptWaveform(f, SAMPLE_RATE);
        long t1 = System.nanoTime();
        int nd = 0;
        while (rec.isReady(s)) { rec.decode(s); nd++; }
        long t2 = System.nanoTime();

        String text = rec.getResult(s).getText();
        long t3 = System.nanoTime();
        if (BuildConfig.DEBUG) {
            android.util.Log.i("GIME_ASR", "smp=" + f.length
                    + " acc=" + (t1 - t0) / 1000 + "us"
                    + " dec=" + (t2 - t1) / 1000 + "us n=" + nd
                    + " res=" + (t3 - t2) / 1000 + "us q=" + PEND.get());
        }
        if (text == null) text = "";
        /* byte-BPE 把没学过的字解成 <unk>，直接显示很难看，去掉即可。 */
        text = text.replace("<unk>", "").trim();
        boolean endpoint = rec.isEndpoint(s);
        /* 说话不停顿时 isEndpoint 可能要等 90 字才触发，一行根本放不下整句。
           超过 18 字就主动定稿并 reset，把长句切成若干「一行一句」的片段。 */
        if (!endpoint && text.length() >= 18) endpoint = true;
        if (!text.isEmpty() && !text.equals(lastText)) {
            lastText = text;
            emit(host, text, endpoint, PEND.get());
        } else if (endpoint && !text.isEmpty()) {
            emit(host, text, true, PEND.get());
        }
        if (endpoint) {
            rec.reset(s);
            lastText = "";
        }
    }

    /** 关字幕：只把最后一句收尾 + reset 流状态，识别器与模型**保持常驻**。
     *  下次 start 命中 cachedId 直接复用，不再花 ~10 秒重新加载。 */
    public static void stop(final Host host) {
        EX.execute(new Runnable() {
            @Override public void run() {
                try {
                    long h = rustHandle;
                    if (h != 0L) {
                        /* Rust 路径也要收尾：finish 补静音把句尾几个字吐出来（否则末句少字），
                           再 cut 推进句起点 —— 保留 encoder 状态，下次开 CC 不用重新等一个窗口。
                           与 sherpa 的「补静音 + reset」对齐，只是 Rust 用 cut 而非清状态。 */
                        String text = XasrNative.finish(h);
                        if (text != null) {
                            text = text.replace("<unk>", "").trim();
                            if (!text.isEmpty()) emit(host, text, true, 0);
                        }
                        XasrNative.cut(h);
                    } else {
                        OnlineRecognizer rec = recognizer;
                        OnlineStream s = stream;
                        if (rec != null && s != null) {
                            /* 收尾前补 0.5 秒静音再取最终结果：流式解码器的最后几个字
                               要再喂几帧才会吐出来，直接取会整句末尾少一个字。
                               合成集实测 CER 1.94% → 0.97%，两个错字正好都是句尾。 */
                            s.acceptWaveform(new float[SAMPLE_RATE / 2], SAMPLE_RATE);
                            while (rec.isReady(s)) rec.decode(s);
                            String text = rec.getResult(s).getText();
                            if (text != null && !text.trim().isEmpty()) {
                                emit(host, text.trim(), true, 0);
                            }
                            /* reset 清掉这一段的解码/端点状态，留下 stream 供下次 CC 接着用。 */
                            rec.reset(s);
                        }
                    }
                } catch (Throwable ignored) { }
                synchronized (AsrEngine.class) { lastText = ""; }
            }
        });
    }

    /** 删除模型 / 重置识别器时调用：真正释放 native 资源并让缓存失效，下次 start 重建。 */
    public static void release() {
        EX.execute(new Runnable() {
            @Override public void run() { releaseLocked(); }
        });
    }

    /** 调用方必须已在 EX 线程上（start / release 都排进 EX），保证与 feed 串行，
     *  不会在解码中途把 rec 释放掉。 */
    private static void releaseLocked() {
        releaseRust();
        try {
            OnlineStream s = stream;
            OnlineRecognizer rec = recognizer;
            if (s != null) s.release();
            if (rec != null) rec.release();
        } catch (Throwable ignored) { }
        synchronized (AsrEngine.class) {
            recognizer = null;
            stream = null;
            cachedId = null;
            lastText = "";
        }
    }

    private static void emit(Host host, String text, boolean fin, int q) {
        host.js("window.giCaption&&giCaption({text:" + JSONObject.quote(text)
                + ",final:" + fin + ",lang:\"zh\",q:" + q + "})");
    }

    private static void status(Host host, String msg) {
        host.js("window.giAsrStatus&&giAsrStatus({msg:" + JSONObject.quote(msg) + "})");
    }
}
