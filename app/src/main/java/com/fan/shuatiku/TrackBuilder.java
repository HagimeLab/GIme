package com.fan.shuatiku;

import android.content.Context;

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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 把一段 PCM 离线跑成字幕轨：切句规则和实时链路完全一致（端点或满 18 字），
 * 这样「生成的字幕」和「直播时看到的字幕」口径一致，不会因为换路径而变味。
 *
 * <p>和实时路径的唯一区别：这里不做背压丢块——离线必须一个字都不能少。
 */
public final class TrackBuilder {

    public interface Ctl {
        boolean canceled();
        /** 硬取消（用户点取消）：原生引擎可以立即中止、丢掉这段结果。
            暂停/让位是软停：不中止原生，让它认完这一段、落好断点再停——
            Rust 中止时只交空轨（实测 json=[]），半段结果拿不回来，
            软停若也中止就会丢整段、续跑从头重认。 */
        boolean hardCancel();
        /** 每推进约 2 秒回调一次，用来刷通知进度。 */
        void progress(int doneSamples, int totalSamples);
    }

    private static final int SR = 16000;
    private static OnlineRecognizer sSherpaRec;
    private static OnlineStream sSherpaStream;
    private static String sSherpaKey = "";

    private TrackBuilder() { }

    /* 仅 debug：把「这次到底走 Rust 还是回退 sherpa、为什么回退」写到 files/asr/.track_debug，
       以备该设备 logcat 抓不到任何输出时仍能定位问题。 */
    static void dbg(Context c, String s) {
        if (!BuildConfig.DEBUG) return;
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new File(new File(c.getFilesDir(), "asr"), ".track_debug"), true);
            fos.write((new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                    + " " + s + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
    }

    public static synchronized JSONArray build(Context c, float[] pcm, Ctl ctl) throws Exception {
        if (ctl != null && ctl.canceled()) return new JSONArray();
        /* 优先走 Rust 引擎（1920ms 混合精度）：整段口径下比 480ms 全 int8 快约 2×，
           且精度更好。任何环节失败都回退到下面的 sherpa 路径。 */
        JSONArray fast = buildWithRust(c, pcm, ctl);
        if (fast != null) {
            dbg(c, "engine=rust segs=" + fast.length() + " audio=" + (pcm.length / SR) + "s");
            /* Rust 已经接管时，不让上一次 sherpa 回退的模型继续占内存。 */
            releaseSherpaLocked();
            return TrackPost.refine(fast);
        }

        File dir = AsrModels.modelDir(c, AsrModels.MODEL_ID);
        /* 快模型包里不带 sherpa 回退所需的 epoch-99 三件套。缺了就明确报错：
           静默交空轨会让用户以为「字幕坏了」，而队列还会把空轨当成功落盘。 */
        if (!sherpaReady(dir)) {
            dbg(c, "sherpa(回退) 模型缺失 audio=" + (pcm.length / SR) + "s");
            throw new IllegalStateException("字幕引擎不可用：本地模型加载失败，且包内不含回退模型");
        }
        dbg(c, "engine=sherpa(回退) audio=" + (pcm.length / SR) + "s dir=" + dir.getName());
        ensureSherpa(dir, threadsOf(c));
        OnlineRecognizer rec = sSherpaRec;
        OnlineStream s = sSherpaStream;
        JSONArray segs = new JSONArray();
        int ch = 2048, pos = 0, segStart = 0, lastReport = 0;
        try {
            while (pos < pcm.length) {
                if (ctl != null && ctl.canceled()) break;
                int n = Math.min(ch, pcm.length - pos);
                float[] buf = new float[n];
                System.arraycopy(pcm, pos, buf, 0, n);
                s.acceptWaveform(buf, SR);
                pos += n;
                while (rec.isReady(s)) rec.decode(s);
                String t = rec.getResult(s).getText();
                if (t == null) t = "";
                t = t.replace("<unk>", "").trim();
                boolean done = rec.isEndpoint(s);
                if (!done && t.length() >= 18) done = true;
                if (done) {
                    if (!t.isEmpty()) segs.put(seg(segStart, pos, t));
                    rec.reset(s);
                    segStart = pos;
                }
                if (ctl != null && pos - lastReport >= SR * 2) {
                    lastReport = pos;
                    ctl.progress(pos, pcm.length);
                }
            }
            String tail = rec.getResult(s).getText();
            if (tail != null) {
                tail = tail.replace("<unk>", "").trim();
                if (!tail.isEmpty()) segs.put(seg(segStart, pcm.length, tail));
            }
        } catch (Throwable t) {
            /* 会话状态可能已经不可信；下段重新建，避免把坏状态带下去。 */
            releaseSherpaLocked();
            throw t;
        }
        /* 碎片 → 整句 → 按显示宽度分行，避免「…我是黑马刚 / 哥。今天…」这种拦腰断句 */
        return TrackPost.refine(segs);
    }

    /** sherpa 回退所需的 4 个文件是否齐备（快模型包里不含这套）。 */
    private static boolean sherpaReady(File dir) {
        return new File(dir, "encoder-epoch-99-avg-1.int8.onnx").isFile()
                && new File(dir, "decoder-epoch-99-avg-1.onnx").isFile()
                && new File(dir, "joiner-epoch-99-avg-1.int8.onnx").isFile()
                && new File(dir, "tokens.txt").isFile();
    }

    /** sherpa 回退也复用同一份识别器；段与段、集与集之间不再反复读 154MB 模型。 */
    private static void ensureSherpa(File dir, int threads) {
        File enc = new File(dir, "encoder-epoch-99-avg-1.int8.onnx");
        String key = dir.getAbsolutePath() + "|" + threads + "|" + enc.length() + "|" + enc.lastModified();
        if (sSherpaRec != null && sSherpaStream != null && key.equals(sSherpaKey)) {
            sSherpaRec.reset(sSherpaStream);
            if (BuildConfig.DEBUG) android.util.Log.i("GIME_ASR", "sherpa session reused");
            return;
        }
        releaseSherpaLocked();

        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(SR);
        feat.setFeatureDim(80);
        feat.setDither(0f);

        OnlineTransducerModelConfig tr = new OnlineTransducerModelConfig();
        tr.setEncoder(enc.getAbsolutePath());
        tr.setDecoder(new File(dir, "decoder-epoch-99-avg-1.onnx").getAbsolutePath());
        tr.setJoiner(new File(dir, "joiner-epoch-99-avg-1.int8.onnx").getAbsolutePath());

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(tr);
        model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
        model.setModelType(AsrModels.MODEL_TYPE);
        model.setModelingUnit("");
        model.setBpeVocab("");
        model.setNumThreads(threads);
        model.setDebug(false);
        model.setProvider("cpu");

        EndpointConfig ep = new EndpointConfig();
        ep.setRule1(new EndpointRule(false, 2.4f, 0f));
        ep.setRule2(new EndpointRule(true, 1.4f, 0f));
        ep.setRule3(new EndpointRule(false, 0f, 20f));

        OnlineRecognizerConfig cfg = new OnlineRecognizerConfig();
        cfg.setFeatConfig(feat);
        cfg.setModelConfig(model);
        cfg.setLmConfig(new OnlineLMConfig());
        cfg.setCtcFstDecoderConfig(new OnlineCtcFstDecoderConfig());
        cfg.setHr(new HomophoneReplacerConfig());
        cfg.setEndpointConfig(ep);
        cfg.setEnableEndpoint(true);
        cfg.setDecodingMethod("greedy_search");

        sSherpaRec = new OnlineRecognizer(null, cfg);
        sSherpaStream = sSherpaRec.createStream("");
        sSherpaKey = key;
        if (BuildConfig.DEBUG) android.util.Log.i("GIME_ASR", "sherpa session created");
    }

    private static void releaseSherpaLocked() {
        try { if (sSherpaStream != null) sSherpaStream.release(); } catch (Throwable ignored) { }
        try { if (sSherpaRec != null) sSherpaRec.release(); } catch (Throwable ignored) { }
        sSherpaStream = null;
        sSherpaRec = null;
        sSherpaKey = "";
    }

    /** Rust 引擎整段出字幕轨；不可用/失败返回 null 让调用方回退 sherpa。
        会话可复用：同一次任务的多个分段共用一个 handle，省掉每段 3.5s 的加载。 */
    private static synchronized JSONArray buildWithRust(Context c, float[] pcm, Ctl ctl) {
        try {
            File dir = AsrModels.modelDir(c, AsrModels.MODEL_ID);
            /* 新模型包只含快模型（encoder.onnx）；旧包是 epoch-99 慢模型。
               两者任一在位就让 Rust 试一次，具体加载哪个由 XasrNative.modelFile 决定。 */
            File fastEnc = new File(dir, "encoder.onnx");
            File slowEnc = new File(dir, "encoder-epoch-99-avg-1.int8.onnx");
            if (!fastEnc.isFile() && !slowEnc.isFile()) {
                dbg(c, "rust: 模型缺失 " + dir.getAbsolutePath());
                return null;
            }

            DeviceProfile dp = DeviceProfile.of(c);
            long tLoad = System.nanoTime();
            long h = XasrNative.shared(c, dir.getAbsolutePath(), threadsOf(c), dp.batch, dp.spin);
            if (h == 0L) {
                dbg(c, "rust: shared 返回 0 threads=" + threadsOf(c) + " batch=" + dp.batch);
                return null;
            }
            dbg(c, "rust: shared ok threads=" + threadsOf(c) + " batch=" + dp.batch
                    + " spin=" + dp.spin + " load=" + (System.nanoTime() - tLoad) / 1_000_000 + "ms");
            long t0 = System.nanoTime();
            XasrNative.ProgressCb pcb = ctl == null ? null : new XasrNative.ProgressCb() {
                @Override public boolean onProgress(int done, int total) {
                    if (ctl.hardCancel()) return false;
                    ctl.progress(done, total);
                    return !ctl.hardCancel();
                }
            };
            String json = XasrNative.buildTrackCb(h, pcm, 18, pcb);
            /* 硬取消被原生提前停了：不是失败。Rust 流式累积出句，中止时返回的
               json 里若已带句子就当 partial 轨交上去落半段断点；实测多数情况
               原生交空（[]），那就退空轨让上层重认这一段（显示层 hi 垫底不回退）。 */
            if (ctl != null && ctl.hardCancel()) {
                JSONArray partial = null;
                try {
                    if (json != null && json.trim().startsWith("[")) partial = new JSONArray(json);
                } catch (Throwable ignored) { }
                dbg(c, "rust: aborted json=" + (json == null ? "null" : json.length() + "ch")
                        + " partial=" + (partial == null ? 0 : partial.length()));
                if (partial != null && partial.length() > 0) return TrackPost.refine(partial);
                return new JSONArray();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            JSONArray segs = new JSONArray(json == null ? "[]" : json);
            if (BuildConfig.DEBUG) {
                android.util.Log.i("GIME_ASR", "rust track segs=" + segs.length()
                        + " audio=" + (pcm.length / SR) + "s ms=" + ms
                        + " rtf=" + String.format(java.util.Locale.US, "%.3f",
                            (ms / 1000.0) / Math.max(1, pcm.length / SR)));
            }
            if (ctl != null) ctl.progress(pcm.length, pcm.length);
            dbg(c, "rust: track segs=" + segs.length() + " ms=" + ms
                    + " rtf=" + String.format(java.util.Locale.US, "%.3f",
                        (ms / 1000.0) / Math.max(1, pcm.length / SR)));
            return segs;
        } catch (Throwable t) {
            if (ctl != null && ctl.canceled()) return new JSONArray();
            /* 出错就别留着可疑会话，下一段重新建 */
            try { XasrNative.releaseShared(); } catch (Throwable ignored) { }
            dbg(c, "rust: 异常 " + t);
            android.util.Log.w("GIME_ASR", "rust track failed, fallback sherpa: " + t);
            return null;
        }
    }

    /** 队列/服务结束（成功/失败/取消）后释放会话，把内存还回去。 */
    public static synchronized void releaseShared() {
        try { XasrNative.releaseShared(); } catch (Throwable ignored) { }
        releaseSherpaLocked();
    }

    /** 生成线程数：按核数/内存分档；debug 构建读 files/asr/.threads 覆盖（1..8）。 */
    private static int threadsOf(Context c) {
        int n = DeviceProfile.of(c).threads;
        if (!BuildConfig.DEBUG) return n;
        java.io.File f = new java.io.File(new java.io.File(c.getFilesDir(), "asr"), ".threads");
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

    private static JSONObject seg(int from, int to, String text) {
        JSONObject o = new JSONObject();
        try {
            o.put("s", Math.round(from * 100.0 / SR) / 100.0);
            o.put("e", Math.round(to * 100.0 / SR) / 100.0);
            o.put("t", text);
        } catch (Throwable ignored) { }
        return o;
    }
}
