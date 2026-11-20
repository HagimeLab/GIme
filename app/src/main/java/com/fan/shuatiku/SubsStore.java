package com.fan.shuatiku;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * 字幕轨与生成队列的落盘。
 *
 * <p>轨：files/subs/&lt;bvid&gt;-&lt;page&gt;.json，形如
 * {"bvid":"BV...","page":1,"model":"xasr-480ms-int8","ver":1,
 *  "segs":[{"s":0.0,"e":5.3,"t":"..."}]}
 * 队列：files/subs/queue.json，每项 {bvid,page,title,vt,status,done,total}
 * status = queued | running | ready | failed
 *
 * <p>轨里带 model 字段：换模型/换参数后旧轨的文本口径就变了，读的时候对不上就当没有。
 */
public final class SubsStore {

    /** 轨格式/模型口径版本。改了识别配置就 +1，旧轨自动失效。 */
    public static final int TRACK_VER = 1;
    public static final String MODEL_TAG = "xasr-480ms-int8";

    private static final Object LOCK = new Object();

    /** 暂停开关的落盘位置：跨进程重启也要保持「用户按了暂停」这个意思 */
    private static final String PREFS = "gime_subs";
    private static final String K_PAUSED = "paused";

    private SubsStore() { }

    /** 字幕识别是否被用户暂停。暂停只停「继续往下跑」，队列与断点都留着。 */
    public static boolean paused(Context c) {
        try { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_PAUSED, false); }
        catch (Throwable t) { return false; }
    }

    public static void setPaused(Context c, boolean v) {
        try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_PAUSED, v).apply(); }
        catch (Throwable ignored) { }
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "subs");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static File trackFile(Context c, String bvid, int page) {
        return new File(dir(c), bvid + "-" + page + ".json");
    }

    /** 读到能用的轨才返回，否则 null（缺失 / 解析失败 / 版本或模型对不上）。 */
    public static String readTrack(Context c, String bvid, int page) {
        File f = trackFile(c, bvid, page);
        if (!f.isFile()) return null;
        try {
            String s = readAll(f);
            JSONObject o = new JSONObject(s);
            if (o.optInt("ver", 0) != TRACK_VER) return null;
            if (!MODEL_TAG.equals(o.optString("model", ""))) return null;
            JSONArray segs = o.optJSONArray("segs");
            if (segs == null || segs.length() == 0) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean hasTrack(Context c, String bvid, int page) {
        return readTrack(c, bvid, page) != null;
    }

    /** 这个视频已经落盘的轨对应的分P号（不解析内容，只看文件名），给选集列表标「已完成」。 */
    public static java.util.List<Integer> trackPages(Context c, String bvid) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        String pre = bvid + "-";
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith(pre) || !n.endsWith(".json")) continue;
            String mid = n.substring(pre.length(), n.length() - ".json".length());
            try { out.add(Integer.parseInt(mid)); } catch (Throwable ignored) { }
        }
        return out;
    }

    public static void deleteTrack(Context c, String bvid, int page) {
        File f = trackFile(c, bvid, page);
        if (f.isFile()) f.delete();
    }

    /**
     * 已落盘的字幕轨总数（跨所有视频）。
     *
     * <p>缓存目录要显示「已完成 N 集」——这个数不能从队列条目数推：完成一集后条目会
     * 留着标 ready，但用户清过队列、或者轨是旧版本直接生成的，队列里就没有对应条目。
     * 数文件名最实在：`&lt;bvid&gt;-&lt;page&gt;.json`，排除断点 `.part.json` 和官方字幕
     * `-official.json`（那两种不是我们生成的轨）。
     */
    public static int trackCount(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            if (name.endsWith(".part.json")) continue;
            if (name.endsWith("-official.json")) continue;
            n++;
        }
        return n;
    }

    /* ---------------- 分段断点 ----------------
       一段识别完就落一次盘；进程被杀/取消后重新入队，服务从这里接着认。 */

    private static File partFile(Context c, String bvid, int page) {
        return new File(dir(c), bvid + "-" + page + ".part.json");
    }

    /** 读到能用的断点才返回；版本/模型对不上或格式坏了都当没有。 */
    public static JSONObject readPart(Context c, String bvid, int page) {
        File f = partFile(c, bvid, page);
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(readAll(f));
            if (o.optInt("ver", 0) != TRACK_VER) return null;
            if (!MODEL_TAG.equals(o.optString("model", ""))) return null;
            JSONArray segs = o.optJSONArray("segs");
            if (segs == null) return null;
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void savePart(Context c, String bvid, int page, JSONObject o) throws Exception {
        o.put("ver", TRACK_VER).put("model", MODEL_TAG);
        File f = partFile(c, bvid, page);
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        fos.write(o.toString().getBytes("UTF-8"));
        fos.close();
        if (!tmp.renameTo(f)) {
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) throw new IllegalStateException("断点写入失败");
        }
    }

    public static void deletePart(Context c, String bvid, int page) {
        File f = partFile(c, bvid, page);
        if (f.isFile()) f.delete();
    }

    /** 先写 .tmp 再改名，被杀在半路也不会留下半个 JSON。 */
    public static void saveTrack(Context c, String bvid, int page, JSONArray segs) throws Exception {
        JSONObject o = new JSONObject()
                .put("bvid", bvid).put("page", page)
                .put("model", MODEL_TAG).put("ver", TRACK_VER)
                .put("segs", segs);
        File f = trackFile(c, bvid, page);
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        fos.write(o.toString().getBytes("UTF-8"));
        fos.close();
        /* 同盘 rename 本身会原子替换；先删旧文件会在进程被杀时留下空窗 */
        if (!tmp.renameTo(f)) {
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) throw new IllegalStateException("轨写入失败");
        }
    }

    /* ---------------- 队列 ---------------- */

    private static File queueFile(Context c) {
        return new File(dir(c), "queue.json");
    }

    public static JSONArray queue(Context c) {
        synchronized (LOCK) {
            File f = queueFile(c);
            if (!f.isFile()) return new JSONArray();
            try {
                return new JSONArray(readAll(f));
            } catch (Throwable t) {
                return new JSONArray();
            }
        }
    }

    private static void writeQueue(Context c, JSONArray a) {
        synchronized (LOCK) {
            File f = queueFile(c);
            File tmp = new File(f.getPath() + ".tmp");
            try {
                FileOutputStream fos = new FileOutputStream(tmp);
                fos.write(a.toString().getBytes("UTF-8"));
                fos.close();
                if (!tmp.renameTo(f)) {
                    if (f.exists()) f.delete();
                    if (!tmp.renameTo(f)) return;
                }
            } catch (Throwable ignored) { }
        }
    }

    public static JSONObject entry(Context c, String bvid, int page) {
        JSONArray a = queue(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && bvid.equals(o.optString("bvid")) && o.optInt("page") == page) return o;
        }
        return null;
    }

    /** 有就更新、没有就追加。返回合并后的条目。 */
    public static JSONObject put(Context c, String bvid, int page, String title, String status) {
        return put(c, bvid, page, title, status, null);
    }

    /**
     * 带视频标题的入队。
     *
     * <p>队列条目本来就只记分P标题（「16-dp和sp以及颜色的使用」），跨视频看就是一堆
     * 互不相干的句子。缓存目录要按视频分组，缺了这个字段只能拿 bvid 当标题。
     * 视频标题只在入队那一次拿得到（JS 侧当前视频的标题），所以存下来。
     */
    public static JSONObject put(Context c, String bvid, int page, String title, String status, String vtitle) {
        /* 读-改-写整体持锁：queue()/writeQueue() 各自也锁 LOCK（可重入），
           但整段必须原子，否则与 worker 的 setProgress 交错会丢更新。 */
        synchronized (LOCK) {
            JSONArray a = queue(c);
            JSONObject found = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && bvid.equals(o.optString("bvid")) && o.optInt("page") == page) { found = o; break; }
            }
            if (found == null) {
                found = new JSONObject();
                try {
                    found.put("bvid", bvid).put("page", page).put("title", title == null ? "" : title)
                            .put("vt", vtitle == null ? "" : vtitle)
                            .put("status", status).put("done", 0).put("total", 0);
                    a.put(found);
                } catch (Throwable ignored) { }
            } else {
                try {
                    found.put("status", status);
                    if (title != null && !title.isEmpty()) found.put("title", title);
                    /* 已经存在的条目别被后续 put（服务内部改状态）把 vt 清空 */
                    if (vtitle != null && !vtitle.isEmpty()) found.put("vt", vtitle);
                } catch (Throwable ignored) { }
            }
            writeQueue(c, a);
            return found;
        }
    }

    /**
     * 批量入队：把 pages 里还没缓存、也不在队列中的集一次性写成 queued，只落一次盘。
     *
     * <p>原来「缓存全集」是 JS 里一集一次调 subsStartV，等于每一集都发一次
     * startForegroundService、服务主线程再把整份 queue.json 读写两遍（159 集就是
     * 318 次全量 IO + 159 次通知重建）。点一下要等好几秒才真正进队列，
     * 用户看到的是「第一下没进去、第二下才进」。
     *
     * <p>已 queued/running/ready、或磁盘上已有轨的集直接跳过，返回真正新入队的数量，
     * 让前端能报准确数量而不是自己猜。
     */
    public static int queueAll(Context c, String bvid, int[] pages, String[] titles, String vtitle) {
        if (pages == null || pages.length == 0) return 0;
        synchronized (LOCK) {
            JSONArray a = queue(c);
            int added = 0;
            for (int pi = 0; pi < pages.length; pi++) {
                int page = pages[pi];
                if (page <= 0) continue;
                String title = (titles != null && pi < titles.length) ? titles[pi] : null;
                JSONObject found = null;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && bvid.equals(o.optString("bvid")) && o.optInt("page") == page) { found = o; break; }
                }
                String st = found == null ? "" : found.optString("status", "");
                if ("queued".equals(st) || "running".equals(st) || "ready".equals(st)) continue;
                if (found == null && hasTrack(c, bvid, page)) continue;
                try {
                    if (found == null) {
                        found = new JSONObject();
                        found.put("bvid", bvid).put("page", page)
                                .put("title", title == null ? "" : title)
                                .put("vt", vtitle == null ? "" : vtitle)
                                .put("status", "queued").put("done", 0).put("total", 0).put("hi", 0);
                        a.put(found);
                    } else {
                        /* 之前 failed 的条目：重置进度重新排队，hi 也要清，否则旧高水位会顶住新进度 */
                        found.put("status", "queued").put("done", 0).put("total", 0).put("hi", 0);
                        found.remove("stage");
                        if (title != null && !title.isEmpty()) found.put("title", title);
                        if (vtitle != null && !vtitle.isEmpty()) found.put("vt", vtitle);
                    }
                    added++;
                } catch (Throwable ignored) { }
            }
            if (added > 0) writeQueue(c, a);
            return added;
        }
    }

    public static void setProgress(Context c, String bvid, int page, int done, int total) {
        synchronized (LOCK) {
            JSONArray a = queue(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && bvid.equals(o.optString("bvid")) && o.optInt("page") == page) {
                    try {
                        /* 进度只前进不后退：暂停/续跑、失败重试都会先报一个比当前低的值
                           （断点落在段边界、重认同一段音频），不垫底的话进度条会肉眼回跳。
                           hi 记到过的最高点；total<=0（时长未知）时不垫。 */
                        if (total > 0) {
                            int hi = o.optInt("hi", 0);
                            if (done < hi) done = hi;
                            else if (done > hi) o.put("hi", done);
                        }
                        o.put("done", done).put("total", total);
                    } catch (Throwable ignored) { }
                    break;
                }
            }
            writeQueue(c, a);
        }
    }

    /** 运行阶段：decode=正在解码音频 / asr=模型识别中。前端据此换状态文案，
        让解码那几秒看起来是「在干活」而不是进度卡死。 */
    public static void setStage(Context c, String bvid, int page, String stage) {
        synchronized (LOCK) {
            JSONArray a = queue(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && bvid.equals(o.optString("bvid")) && o.optInt("page") == page) {
                    try { o.put("stage", stage); } catch (Throwable ignored) { }
                    break;
                }
            }
            writeQueue(c, a);
        }
    }

    public static void remove(Context c, String bvid, int page) {
        synchronized (LOCK) {
            JSONArray a = queue(c);
            JSONArray b = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (bvid.equals(o.optString("bvid")) && o.optInt("page") == page) continue;
                b.put(o);
            }
            writeQueue(c, b);
        }
    }

    /**
     * 插队：把这一集挪到队列最前、状态置回 queued，其余项顺序不变。
     *
     * <p>插队不改动任务本身（断点、已下好的音频都留着），只是「下一个先跑它」；
     * 正在跑的那一集由服务侧在段边界收尾后自行退回 queued。
     */
    public static boolean promote(Context c, String bvid, int page) {
        synchronized (LOCK) {
            JSONArray a = queue(c);
            JSONArray rest = new JSONArray();
            JSONObject hit = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (bvid.equals(o.optString("bvid")) && o.optInt("page") == page) hit = o;
                else rest.put(o);
            }
            if (hit == null) return false;
            try {
                String st = hit.optString("status");
                /* running：正在跑的那一份马上要让位；failed：用户点名要它，重试一次 */
                if ("running".equals(st) || "failed".equals(st)) hit.put("status", "queued");
            } catch (Throwable ignored) { }
            JSONArray out = new JSONArray();
            out.put(hit);
            for (int i = 0; i < rest.length(); i++) out.put(rest.opt(i));
            writeQueue(c, out);
            return true;
        }
    }

    /** 进程被杀/服务被停后，running 会变成僵尸状态；服务起来时先回收成 queued。 */
    public static void resetRunning(Context c) {
        synchronized (LOCK) {
            JSONArray a = queue(c);
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && "running".equals(o.optString("status"))) {
                    try { o.put("status", "queued"); changed = true; } catch (Throwable ignored) { }
                }
            }
            if (changed) writeQueue(c, a);
        }
    }

    /** 下一个待做项（queued 优先）。返回 null 表示队列空。 */
    public static JSONObject nextPending(Context c) {
        JSONArray a = queue(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && "queued".equals(o.optString("status"))) return o;
        }
        return null;
    }

    public static int pendingCount(Context c) {
        JSONArray a = queue(c);
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                String st = o.optString("status");
                if ("queued".equals(st) || "running".equals(st)) n++;
            }
        }
        return n;
    }

    /** 入队名额：不设上限。用户要求「排队等待解析的视频改为无限」——想排多少部、多少集
        都直接进队列，由服务按顺序一部一部跑。保留此方法只为兼容既有调用点。 */
    public static boolean videoSlotOk(Context c, String bvid) {
        return true;
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
