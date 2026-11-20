package com.fan.shuatiku;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * 把 MP4 里的音轨解成 16k 单声道 float PCM，给离线字幕生成用。
 *
 * <p>App 平时是 Web Audio 实时抓音的，只能在播放时拿到；要「提前生成整条字幕轨」
 * 就必须绕开播放器，直接解文件。这里用 MediaExtractor + MediaCodec 解 AAC，
 * 边解边做立体声下混和重采样，全程流式，不把整段原始 PCM 堆在内存里。
 */
public final class AudioPcm {

    public static final int OUT_RATE = 16000;

    public interface SegCb {
        /** 一段 PCM（16k 单声道）及其在整条音轨里的起点秒数。 */
        void onSegment(float[] pcm, double startSec) throws Exception;
    }

    /** 解码进度：一段音频在「解码阶段」每 ~1.2 秒报一次已解到的整轨秒数。
        没有它的话，解一整段（几百秒音频）期间上层拿不到任何回调，
        进度条会在段边界冻住十几秒，看起来像卡死。 */
    public interface DecodeCb {
        void onDecode(double sec);
    }

    public interface DlCb {
        /** 下载进度；total<=0 表示上游没给长度。 */
        void onBytes(long done, long total) throws Exception;
    }

    public static final class Result {
        public int srcRate, channels, samples;
        public float peak, rms;
        public long downloadMs, decodeMs;
        public float[] data;
        public int seconds() { return samples / OUT_RATE; }
    }

    private AudioPcm() { }

    /** 下载到本地文件（走 App 自己的 127.0.0.1 代理，不再走网络）。 */
    public static long download(String url, File out) throws Exception {
        return download(url, out, null);
    }

    /** 带进度回调的下载：读满 20 秒没有新字节就抛错，交给上层重试，避免「卡在 0%」。 */
    public static long download(String url, File out, DlCb cb) throws Exception {
        return download(url, out, cb, false);
    }

    /** 断点续传：已有半截文件时带 Range 从末尾接着下；服务端不支持 Range 就退回重下。 */
    public static long downloadResume(String url, File out, DlCb cb) throws Exception {
        return download(url, out, cb, true);
    }

    private static long download(String url, File out, DlCb cb, boolean resume) throws Exception {
        long t0 = System.currentTimeMillis();
        long startLen = resume && out.isFile() ? out.length() : 0;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "*/*");
        if (startLen > 0) c.setRequestProperty("Range", "bytes=" + startLen + "-");
        int code = c.getResponseCode();
        long contentLen = -1;
        try { contentLen = c.getContentLengthLong(); } catch (Throwable ignored) { }
        long fullTotal = -1;
        if (code == 206) {
            fullTotal = contentRangeTotal(c.getHeaderField("Content-Range"));
            if (fullTotal <= 0 && contentLen > 0) fullTotal = startLen + contentLen;
        } else if (code == 200) {
            /* 服务端忽略了 Range：从头覆盖写，不能接着拼 */
            startLen = 0;
            fullTotal = contentLen;
        } else if (code == 416 && startLen > 0) {
            /* 本地已经是完整文件（或至少不小于远端）：按成功返回 */
            android.util.Log.i("GIME_AUDIO", "download already complete bytes=" + startLen);
            try { c.disconnect(); } catch (Throwable ignored) { }
            return 0;
        } else {
            try { c.disconnect(); } catch (Throwable ignored) { }
            throw new java.io.IOException("HTTP " + code);
        }
        InputStream in = c.getInputStream();
        FileOutputStream fos = new FileOutputStream(out, startLen > 0);
        byte[] buf = new byte[65536];
        long total = startLen;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
                if (cb != null) cb.onBytes(total, fullTotal > 0 ? fullTotal : total + Math.max(0, contentLen));
            }
        } finally {
            try { fos.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
            try { c.disconnect(); } catch (Exception ignored) { }
        }
        android.util.Log.i("GIME_AUDIO", "download bytes=" + total + " start=" + startLen
                + " ms=" + (System.currentTimeMillis() - t0));
        return System.currentTimeMillis() - t0;
    }

    private static long contentRangeTotal(String cr) {
        if (cr == null) return -1;
        int i = cr.lastIndexOf('/');
        if (i < 0) return -1;
        try { return Long.parseLong(cr.substring(i + 1).trim()); } catch (Throwable t) { return -1; }
    }

    /** 音轨总时长（毫秒）；拿不到返回 0。 */
    public static long durationMs(File f) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Throwable t) {
            return 0;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /** 分段解码：长视频整条 PCM 会撑爆内存（92 分钟 ≈ 356MB float），按时间片解、解完就交出去。 */
    public static void decodeSegments(File f, int segSec, SegCb cb) throws Exception {
        decodeSegments(f, segSec, 0, cb);
    }

    /** 从 fromSec 开始分段解码；断点续识别用。 */
    public static void decodeSegments(File f, int segSec, double fromSec, SegCb cb) throws Exception {
        decodeSegments(f, segSec, fromSec, cb, null);
    }

    /** 同上，带解码阶段进度回调（治「段边界进度冻住」）。 */
    public static void decodeSegments(File f, int segSec, double fromSec, SegCb cb, DecodeCb dcb) throws Exception {
        if (segSec <= 0) segSec = 600;
        long durMs = durationMs(f);
        if (durMs <= 0) {
            if (fromSec > 0.05) return;   /* 拿不到时长时不猜断点，交给上层从头重来 */
            Result r = decode(f);
            if (r.data.length > 0) cb.onSegment(r.data, 0);
            return;
        }
        double dur = durMs / 1000.0;
        double start = Math.max(0, Math.min(fromSec, dur));
        for (; start < dur - 0.05; start += segSec) {
            double end = Math.min(dur, start + segSec);
            float[] pcm = decodeRange(f, start, end, dcb);
            if (pcm.length > 0) cb.onSegment(pcm, start);
        }
    }

    /** 解 [startSec, endSec) 这一段；起点对齐到 seek 落点的样本时间。 */
    private static float[] decodeRange(File f, double startSec, double endSec, DecodeCb dcb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat mf = ex.getTrackFormat(i);
            String mime = mf.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = mf; break; }
        }
        if (track < 0) { ex.release(); throw new IllegalStateException("没有音轨"); }
        ex.selectTrack(track);

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

        long startUs = (long) (startSec * 1e6), endUs = (long) (endSec * 1e6);
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long firstUs = ex.getSampleTime();
        if (firstUs < 0) { ex.release(); return new float[0]; }

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();

        Resampler rs = new Resampler(srcRate, channels);
        boolean inputDone = false, outputDone = false;
        int idle = 0;
        long lastDecReport = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(inIdx);
                    long sampleUs = ex.getSampleTime();
                    if (sampleUs < 0 || sampleUs >= endUs) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, sampleUs, 0);
                            ex.advance();
                            idle = 0;
                        }
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                rs = new Resampler(srcRate, channels);
                idle = 0;
            } else if (outIdx >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(outIdx);
                if (ob != null && info.size > 0) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    rs.push(ob);
                    /* 解码阶段心跳：按输出缓冲的时间戳报「解到第几秒」，
                       上层据此切到「解码音频」状态，进度条不再像冻住。 */
                    if (dcb != null && info.presentationTimeUs > 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastDecReport >= 1200) {
                            lastDecReport = now;
                            dcb.onDecode(info.presentationTimeUs / 1e6);
                        }
                    }
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outIdx, false);
                idle = 0;
                if (eos) outputDone = true;
            } else if (inputDone && ++idle > 200) break;
        }

        codec.stop();
        codec.release();
        ex.release();

        float[] all = rs.finish();
        double lead = Math.max(0.0, (startUs - firstUs) / 1e6);
        int skip = (int) Math.round(lead * OUT_RATE);
        if (skip <= 0) return all;
        if (skip >= all.length) return new float[0];
        float[] g = new float[all.length - skip];
        System.arraycopy(all, skip, g, 0, g.length);
        return g;
    }

    /** 解码整条音轨。返回 16k 单声道 [-1,1] 样本。 */
    public static Result decode(File f) throws Exception {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat mf = ex.getTrackFormat(i);
            String mime = mf.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = mf; break; }
        }
        if (track < 0) { ex.release(); throw new IllegalStateException("没有音轨"); }
        ex.selectTrack(track);

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        r.srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        r.channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();

        Resampler rs = new Resampler(r.srcRate, r.channels);
        boolean inputDone = false;
        boolean outputDone = false;
        int idle = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long feedTimeout = 10000;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(feedTimeout);
                if (inIdx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(inIdx);
                    int size = ex.readSampleData(ib, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                        idle = 0;
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, feedTimeout);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) r.srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) r.channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                rs = new Resampler(r.srcRate, r.channels);
                idle = 0;
            } else if (outIdx >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(outIdx);
                if (ob != null && info.size > 0) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    rs.push(ob);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outIdx, false);
                idle = 0;
                /* 只认输出侧的 EOS，别在还没排空的时候提前退出 */
                if (eos) outputDone = true;
            } else {
                /* 输入已结束又连续取不到输出：真的没东西了 */
                if (inputDone && ++idle > 200) break;
            }
        }

        codec.stop();
        codec.release();
        ex.release();

        r.data = rs.finish();
        r.samples = r.data.length;
        double sq = 0;
        float pk = 0;
        for (float v : r.data) { float a = Math.abs(v); if (a > pk) pk = a; sq += (double) v * v; }
        r.peak = pk;
        r.rms = r.data.length > 0 ? (float) Math.sqrt(sq / r.data.length) : 0;
        r.decodeMs = System.currentTimeMillis() - t0;
        android.util.Log.i("GIME_AUDIO", "decode sec=" + (r.samples / (float) OUT_RATE)
                + " srcRate=" + r.srcRate + " ch=" + r.channels + " ms=" + r.decodeMs
                + " peak=" + r.peak + " rms=" + r.rms);
        return r;
    }

    /** 立体声下混 + 线性重采样到 16k，边解边吐，内存里只留 16k 结果。 */
    private static final class Resampler {
        private final int ch;
        private final double step;
        private float[] out = new float[OUT_RATE * 60];
        private int n = 0;
        private double t = 0;        /* 下一个输出点在当前缓冲里的位置 */
        private float prev = 0f;     /* 上一个缓冲的最后一个下混样本 */
        private boolean hasPrev = false;

        Resampler(int srcRate, int channels) {
            this.ch = Math.max(1, channels);
            this.step = (double) Math.max(1, srcRate) / OUT_RATE;
        }

        void push(ByteBuffer buf) {
            ShortBuffer sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int frames = sb.remaining() / ch;
            if (frames <= 0) return;
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                int acc = 0;
                for (int c = 0; c < ch; c++) acc += sb.get();
                mono[i] = acc / (float) ch / 32768f;
            }
            /* 虚拟索引 -1 = prev，0..frames-1 = mono */
            while (true) {
                int i0 = (int) Math.floor(t);
                if (i0 + 1 >= frames) break;          /* 需要下一个样本，留到下个缓冲 */
                double frac = t - i0;
                float a = i0 < 0 ? prev : mono[i0];
                float b = mono[i0 + 1];
                add(a + (b - a) * (float) frac);
                t += step;
            }
            t -= frames;
            prev = mono[frames - 1];
            hasPrev = true;
        }

        private void add(float v) {
            if (n == out.length) {
                float[] g = new float[out.length + OUT_RATE * 60];
                System.arraycopy(out, 0, g, 0, out.length);
                out = g;
            }
            out[n++] = v;
        }

        float[] finish() {
            float[] r = new float[n];
            System.arraycopy(out, 0, r, 0, n);
            return r;
        }
    }
}
