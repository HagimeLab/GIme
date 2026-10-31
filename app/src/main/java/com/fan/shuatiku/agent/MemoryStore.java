package com.fan.shuatiku.agent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 记忆库：长期画像 + 会话摘要，落在 {@code filesDir/memory/memory.json}。
 *
 * <p>和 PlanStore 一样是纯文件、不依赖 Android，桌面可测；落盘走 tmp/bak 原子替换。
 * 画像由页面按题库统计生成（最弱题型、近 7 天正确率、进行中方案、偏好），
 * 会话摘要由页面在历史超过 30 条时做本地滚动压缩；这里只负责存与取。
 */
public final class MemoryStore {

    public static final String FILE = "memory.json";

    private final File file;
    private final File tmp;
    private final File bak;
    private JSONObject profile = new JSONObject();
    private JSONObject session = new JSONObject();

    public MemoryStore(File dir) {
        file = new File(dir, FILE);
        tmp = new File(dir, FILE + ".tmp");
        bak = new File(dir, FILE + ".bak");
        load();
    }

    /** {profile:{...}, session:{...}}。 */
    public synchronized String snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("profile", profile);
            o.put("session", session);
        } catch (Exception ignored) { }
        return o.toString();
    }

    public synchronized boolean save(JSONObject p, JSONObject s) {
        JSONObject op = profile, os = session;
        if (p != null) profile = p;
        if (s != null) session = s;
        if (!saveFile()) {
            profile = op;
            session = os;
            return false;
        }
        return true;
    }

    public synchronized void clear() {
        profile = new JSONObject();
        session = new JSONObject();
        if (file.exists()) file.delete();
        if (tmp.exists()) tmp.delete();
        if (bak.exists()) bak.delete();
    }

    private void load() {
        JSONObject good = parse(file);
        if (good == null) {
            JSONObject fromBak = parse(bak);
            if (fromBak != null) {
                good = fromBak;
                if (file.exists()) file.delete();
                bak.renameTo(file);
            }
        }
        if (good != null) {
            JSONObject p = good.optJSONObject("profile");
            JSONObject s = good.optJSONObject("session");
            if (p != null) profile = p;
            if (s != null) session = s;
        }
        if (tmp.exists()) tmp.delete();
        if (bak.exists() && parse(file) != null) bak.delete();
    }

    private boolean saveFile() {
        byte[] bytes;
        try {
            JSONObject o = new JSONObject();
            o.put("profile", profile);
            o.put("session", session);
            bytes = o.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return false;
        }
        boolean hadOld = file.exists();
        try {
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(bytes);
                os.flush();
                os.getFD().sync();
            } finally {
                os.close();
            }
            if (tmp.length() != bytes.length) return false;
            if (hadOld) {
                if (bak.exists() && !bak.delete()) return false;
                if (!file.renameTo(bak)) return false;
            }
            if (!tmp.renameTo(file)) {
                if (hadOld) bak.renameTo(file);
                return false;
            }
            if (file.length() != bytes.length) {
                if (hadOld) { file.delete(); bak.renameTo(file); }
                return false;
            }
            if (bak.exists()) bak.delete();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static JSONObject parse(File f) {
        if (f == null || !f.exists() || f.length() == 0) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new JSONObject(new String(b.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }
}
