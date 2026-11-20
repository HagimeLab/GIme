package com.fan.shuatiku;

import android.content.Context;

/**
 * X-ASR Rust 加速框架的 JNI 桥（xasr-jni）。
 *
 * <p>与 {@link AsrEngine} 的 sherpa 路径并列：由 {@link AsrEngine#useRust()} 开关决定走哪条。
 * Rust 侧负责 fbank / 流式 encoder / greedy 解码，模型用混合精度导出
 * （encoder: MatMul int8 + Conv fp32），比 sherpa + 全 int8 快约 2×。
 *
 * <p>句柄语义：{@link #create} 返回非 0 即成功，必须配对 {@link #destroy}。
 * 所有方法都是同步阻塞的，调用方负责放到后台线程。
 */
final class XasrNative {

    private static volatile boolean sLoaded = false;
    private static volatile String sLoadError = null;

    private XasrNative() { }

    /** 加载 Rust 库与 ORT。失败时返回原因，成功后返回 null。 */
    static synchronized String ensureLoaded(Context ctx) {
        if (sLoaded) return null;
        if (sLoadError != null) return sLoadError;
        try {
            System.loadLibrary("xasr_jni");
            /* extractNativeLibs=false 时 nativeLibraryDir 可能为空目录，
               此时退回库名让动态加载器从 APK 内解析。 */
            String abs = ctx.getApplicationInfo().nativeLibraryDir + "/libonnxruntime.so";
            String ort = new java.io.File(abs).isFile() ? abs : "libonnxruntime.so";
            String r = initOrt(ort);
            if (r == null || !"ok".equals(r)) {
                sLoadError = "ORT 初始化失败: " + r;
                return sLoadError;
            }
            sLoaded = true;
            return null;
        } catch (Throwable t) {
            sLoadError = "加载 libxasr_jni 失败: " + t;
            return sLoadError;
        }
    }

    static boolean available() {
        return sLoaded;
    }

    /* ---- 复用会话：长视频按 10 分钟分段，每段都 create/destroy 会白付一次加载
       （实测 3.5s/段，92 分钟视频约 35 秒）。build_track 每次开头都会 reset()，
       所以同一个 handle 可以连着跑多段。模型/线程变了才重建。 ---- */
    private static long sShared = 0;
    private static String sSharedKey = "";

    /** 取（必要时建）可复用的会话；返回 0 表示建失败。 */
    static synchronized long shared(Context ctx, String dir, int threads, int batch, boolean spin) {
        if (ensureLoaded(ctx) != null) return 0;
        String abs = ctx.getApplicationInfo().nativeLibraryDir + "/libonnxruntime.so";
        String ort = new java.io.File(abs).isFile() ? abs : "libonnxruntime.so";
        /* key 必须看「Rust 实际加载的那个 encoder」：model_file 优先精确名 encoder.onnx，
           缺失才回退 epoch-99 int8。原来只盯 epoch-99，进程内换了 encoder.onnx 也不会失效会话。 */
        java.io.File enc = new java.io.File(dir, "encoder.onnx");
        if (!enc.isFile()) enc = new java.io.File(dir, "encoder-epoch-99-avg-1.int8.onnx");
        String key = dir + "|" + threads + "|" + batch + "|" + spin + "|" + enc.getName()
                + "|" + enc.length() + "|" + enc.lastModified();
        if (sShared != 0 && key.equals(sSharedKey)) {
            if (BuildConfig.DEBUG) android.util.Log.i("GIME_ASR", "rust session reused");
            return sShared;
        }
        releaseSharedLocked();
        long t0 = System.nanoTime();
        long h = create(dir, ort, threads, batch, spin);
        if (h != 0) { sShared = h; sSharedKey = key; }
        if (BuildConfig.DEBUG) {
            android.util.Log.i("GIME_ASR", "rust session created load="
                    + ((System.nanoTime() - t0) / 1_000_000) + "ms h=" + h);
        }
        return h;
    }

    /** 队列/服务结束再释放：连续任务复用同一会话，避免反复读模型；常驻约 275MB。 */
    static synchronized void releaseShared() {
        releaseSharedLocked();
    }

    private static void releaseSharedLocked() {
        if (sShared != 0) {
            try { destroy(sShared); } catch (Throwable ignored) { }
            sShared = 0;
        }
        sSharedKey = "";
    }

    private static native String initOrt(String ortLibPath);

    static native long create(String modelDir, String ortLibPath, int threads, int batch, boolean spin);

    static native String feed(long handle, float[] pcm);

    static native String finish(long handle);

    /** 整段生成字幕轨，返回 JSON: [{"s":秒,"e":秒,"t":文本}, ...] */
    static native String buildTrack(long handle, float[] pcm, int maxChars);

    /** 进度回调：每推进约 2 秒音频调一次；返回 false 立即中止识别（取消用）。 */
    interface ProgressCb {
        boolean onProgress(int done, int total);
    }

    /** 同上，但按实际解码进度回调，用于前台进度条。 */
    static native String buildTrackCb(long handle, float[] pcm, int maxChars, ProgressCb cb);

    /** 字幕换行：推进当前句起点，保留 encoder/decoder 状态。 */
    static native void cut(long handle);

    static native void reset(long handle);

    static native void destroy(long handle);
}
