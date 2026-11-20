package com.fan.shuatiku;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 按设备配置选离线识别的并行档位：核数少/内存小就少开线程、缩小 joiner 批，
 * 高端机保持实测最优的 4 线程 + 8 帧批处理，避免盲目加线程反而抢核。
 */
final class DeviceProfile {

    final int cores;
    final long ramMb;
    final int threads;
    final int batch;
    final boolean spin;
    final String tier;

    private static volatile DeviceProfile cached;

    private DeviceProfile(int cores, long ramMb, int threads, int batch, boolean spin, String tier) {
        this.cores = cores;
        this.ramMb = ramMb;
        this.threads = threads;
        this.batch = batch;
        this.spin = spin;
        this.tier = tier;
    }

    static DeviceProfile of(Context c) {
        DeviceProfile p = cached;
        if (p != null) return p;
        synchronized (DeviceProfile.class) {
            if (cached == null) cached = detect(c);
            return cached;
        }
    }

    private static DeviceProfile detect(Context c) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long ramMb = 4096;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.totalMem > 0) ramMb = mi.totalMem / 1048576L;
            }
        } catch (Throwable ignored) { }

        String tier;
        int threads, batch;
        boolean spin;
        if (cores <= 4 || ramMb < 3000) {
            tier = "low";
            threads = Math.min(2, cores);
            batch = 4;
            spin = false;
        } else if (cores <= 6 || ramMb < 6000) {
            tier = "mid";
            threads = 3;
            batch = 6;
            spin = true;
        } else {
            /* 4 线程/8 帧是在 8 核机上量到的最优；更高核数继续加线程会抢核，不采用 */
            tier = "high";
            threads = 4;
            batch = 8;
            spin = true;
        }

        String forced = BuildConfig.DEBUG ? forcedTier(c) : "";
        if ("low".equals(forced) || "mid".equals(forced) || "high".equals(forced)) {
            tier = forced;
            if ("low".equals(tier)) { threads = Math.min(2, cores); batch = 4; spin = false; }
            else if ("mid".equals(tier)) { threads = Math.min(3, cores); batch = 6; spin = true; }
            else { threads = Math.min(4, cores); batch = 8; spin = true; }
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.i("GIME_ASR", "profile tier=" + tier + " cores=" + cores
                    + " ram=" + ramMb + "MB threads=" + threads + " batch=" + batch
                    + " spin=" + spin + (forced.isEmpty() ? "" : " (forced)"));
        }
        return new DeviceProfile(cores, ramMb, threads, batch, spin, tier);
    }

    /** 仅 debug：files/asr/.profile 写 low|mid|high，用来在同一台机上验档位。 */
    private static String forcedTier(Context c) {
        File f = new File(new File(c.getFilesDir(), "asr"), ".profile");
        if (!f.isFile()) return "";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String s = br.readLine();
            return s == null ? "" : s.trim().toLowerCase();
        } catch (Throwable ignored) {
            return "";
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) { }
        }
    }
}