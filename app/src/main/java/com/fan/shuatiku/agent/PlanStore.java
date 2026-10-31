package com.fan.shuatiku.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 方案库：单文件 {@code filesDir/plans.json} + rev 乐观锁。
 *
 * <p>移植自 agent 项目的 Store.java，但改掉了三处不适合 GIme 的地方：
 * <ol>
 *   <li>不依赖 Android：构造只吃一个 {@link File} 目录，桌面 javac 能直接跑单测
 *       （和 BankStore / RiskGuard / BridgeGuard 同一套路）。</li>
 *   <li>落盘走「写 tmp → 旧文件挪 .bak → tmp 顶上 → 删 .bak」，进程死在任意一步
 *       都能在下次启动时按现场恢复；agent 原版是直接覆盖写，断电会留下半截 JSON。</li>
 *   <li>每个写操作都校验调用方手里的 rev：对不上返回 {@link #REV_CONFLICT}，
 *       不覆盖别人的改动。</li>
 * </ol>
 *
 * <p>计划字段以 MCP 工具 schema 为唯一真源（见 S4 的 create_plan / update_plan），
 * 这里只负责存与取，不另立一套定义。
 */
public final class PlanStore {

    /** 方案文件名。页面与 MCP 工具共用同一份。 */
    public static final String FILE = "plans.json";

    /** rev 对不上时的返回值。不覆盖，让调用方重新拉一次再改。 */
    public static final int REV_CONFLICT = -1;

    /** 天数上限：够长但不至于把 JSON 撑爆。 */
    public static final int MAX_DAYS = 60;

    /** 标题兜底截断长度。 */
    private static final int TITLE_CLIP = 14;

    private final File file;
    private final File tmp;
    private final File bak;

    private int rev;
    private JSONArray plans = new JSONArray();

    public PlanStore(File dir) {
        file = new File(dir, FILE);
        tmp = new File(dir, FILE + ".tmp");
        bak = new File(dir, FILE + ".bak");
        load();
    }

    /** 写操作的返回值：新 rev + 受影响的方案。 */
    public static final class Out {
        public final int rev;
        public final JSONObject plan;

        Out(int rev, JSONObject plan) {
            this.rev = rev;
            this.plan = plan;
        }

        /** false 表示 rev 冲突，什么都没改。 */
        public boolean ok() { return rev != REV_CONFLICT; }
    }

    /* ================= 落盘与恢复 ================= */

    /**
     * 启动时读一次。按落盘现场决定谁说了算：
     *
     * <pre>
     *   plans.json 能解析            → 用它（.bak 是多余旧版，删掉）
     *   plans.json 不在或解析不了、
     *   而 .bak 能解析               → 死在替换中途，.bak 是唯一完整副本，还原成正式文件
     *   两个都解析不了               → 保持空方案库（不崩、不写坏文件）
     *   .tmp                         → 永远是没写完的半成品，直接删
     * </pre>
     */
    private void load() {
        JSONObject good = parse(file);
        if (good == null) {
            JSONObject fromBak = parse(bak);
            if (fromBak != null) {
                good = fromBak;
                // .bak 是唯一完整副本，放回正式位置；放不回也不影响本次内存里的数据
                if (file.exists()) file.delete();
                bak.renameTo(file);
            }
        }
        if (good != null) {
            rev = good.optInt("rev", 0);
            JSONArray p = good.optJSONArray("plans");
            if (p != null) plans = p;
        }
        // 半截临时文件不作数
        if (tmp.exists()) tmp.delete();
        // 正式文件已经能用了，旧备份可以丢
        if (bak.exists() && parse(file) != null) bak.delete();
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

    /**
     * 原子替换。**旧文件绝不先删**：先删再 rename，rename 一失败方案就全没了。
     *
     * @return true 表示正式文件已经是新内容
     */
    private boolean save() {
        byte[] bytes;
        try {
            JSONObject o = new JSONObject();
            o.put("rev", rev);
            o.put("plans", plans);
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
                os.getFD().sync();      // 落到磁盘再往下走，别让进程被杀时留下半截文件
            } finally {
                os.close();
            }
            if (tmp.length() != bytes.length) return false;

            if (hadOld) {
                if (bak.exists() && !bak.delete()) return false;
                if (!file.renameTo(bak)) return false;      // 挪走而不是删掉
            }
            if (!tmp.renameTo(file)) {
                if (hadOld) bak.renameTo(file);             // 新文件没顶上，把旧的放回去
                return false;
            }
            if (file.length() != bytes.length) {
                if (hadOld) {
                    file.delete();
                    bak.renameTo(file);
                }
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

    /* ================= 读 ================= */

    public synchronized int rev() { return rev; }

    /** 给页面/工具的一次性快照：rev + 全部方案（含 done/total/pct/today 派生字段）。 */
    public synchronized String snapshot() {
        try {
            JSONObject o = new JSONObject();
            o.put("rev", rev);
            o.put("plans", list());
            return o.toString();
        } catch (Exception e) {
            return "{\"rev\":" + rev + ",\"plans\":[]}";
        }
    }

    /**
     * 方案摘要列表。派生字段在这里算，页面与 MCP 工具都拿这一份，不各算一遍。
     *
     * <p>{@code today} 只在该方案真有今天这一天的条目时才出现——没有就是没有，
     * 别给一个空数组让调用方以为「今天没事做」和「这个方案今天没排期」是一回事。
     */
    public synchronized JSONArray list() throws JSONException {
        JSONArray out = new JSONArray();
        String today = todayKey();
        for (int i = 0; i < plans.length(); i++) {
            JSONObject p = plans.optJSONObject(i);
            if (p == null) continue;
            int done = 0, total = 0;
            JSONArray days = p.optJSONArray("days");
            if (days != null) {
                for (int k = 0; k < days.length(); k++) {
                    JSONObject d = days.optJSONObject(k);
                    if (d == null) continue;
                    JSONArray items = d.optJSONArray("items");
                    if (items == null) continue;
                    for (int m = 0; m < items.length(); m++) {
                        JSONObject it = items.optJSONObject(m);
                        if (it == null) continue;
                        total++;
                        if (it.optBoolean("done", false)) done++;
                    }
                }
            }
            JSONObject o = new JSONObject();
            o.put("id", p.optString("id"));
            o.put("title", p.optString("title"));
            o.put("goal", p.optString("goal"));
            o.put("enabled", p.optBoolean("enabled", true));
            o.put("remind", p.optBoolean("remind", false));
            o.put("totalDays", p.optInt("totalDays", days == null ? 0 : days.length()));
            o.put("done", done);
            o.put("total", total);
            o.put("pct", total > 0 ? Math.round(done * 100f / total) : 0);
            JSONObject td = dayOf(p, today);
            if (td != null) o.put("today", td.optJSONArray("items"));
            out.put(o);
        }
        return out;
    }

    /** 按 id 找一份完整方案；没有返回 null。 */
    public synchronized JSONObject find(String id) {
        return planById(id);
    }

    /* ================= 写 ================= */

    /**
     * 整份覆盖（agent 的 sync 模型保留下来，作为 rev 锁最直白的表达）。
     *
     * @return 接受后的新 rev；rev 对不上返回 {@link #REV_CONFLICT}，什么都不改
     */
    public synchronized int setPlans(JSONArray list, int clientRev) {
        if (clientRev != rev) return REV_CONFLICT;
        plans = list == null ? new JSONArray() : list;
        rev++;
        if (!save()) {
            // 落盘失败就把内存也退回去，别让「内存里有、磁盘上没有」
            rev--;
            return REV_CONFLICT;
        }
        return rev;
    }

    /**
     * 新建方案。goal 必填；days 越界会被夹到 1..{@link #MAX_DAYS}；
     * 不给逐日内容时按模板兜底拆解（agent 原逻辑）。
     *
     * @param clientRev 调用方手里的 rev；传 {@link #REV_CONFLICT} 之外的旧值会冲突
     */
    public synchronized Out create(String goal, int days, String title, JSONArray dayList,
                                   int clientRev) throws JSONException {
        String g = goal == null ? "" : goal.trim();
        if (g.length() == 0) throw new JSONException("goal 不能为空");
        if (clientRev != rev) return new Out(REV_CONFLICT, null);

        int n = Math.max(1, Math.min(MAX_DAYS, days <= 0 ? 7 : days));
        JSONArray dd = dayList != null && dayList.length() > 0 ? normalizedDays(dayList) : templateDays(g, n);
        // 一天都没排上内容的方案等于空壳，页面会显示成 0/0，不如当场拒掉
        if (dd.length() == 0 || countItems(dd) == 0) throw new JSONException("days 里没有可用的条目");

        JSONObject p = new JSONObject();
        p.put("id", newId());
        p.put("goal", g);
        p.put("title", title == null || title.trim().length() == 0 ? clip(g, TITLE_CLIP) : title.trim());
        p.put("totalDays", dd.length());
        p.put("createdAt", System.currentTimeMillis());
        p.put("enabled", true);
        p.put("remind", false);
        p.put("days", dd);
        JSONArray adj = new JSONArray();
        adj.put(adjustment("建立方案", dayList != null && dayList.length() > 0
                ? "按调用方给定的逐日内容建立" : "按 " + dd.length() + " 天模板拆解"));
        p.put("adjustments", adj);

        plans.put(p);
        rev++;
        if (!save()) {
            rev--;
            plans.remove(plans.length() - 1);
            throw new JSONException("方案写盘失败，没有建立");
        }
        return new Out(rev, p);
    }

    /**
     * 改方案。action 枚举见 {@link #ACTIONS}，未知的一律拒绝而不是当没发生。
     *
     * @param args      按 action 取用：date / text / title / done / days
     * @param clientRev 调用方手里的 rev
     */
    public synchronized Out update(String id, String action, JSONObject args,
                                   int clientRev) throws JSONException {
        JSONObject p = planById(id);
        if (p == null) throw new JSONException("方案不存在：" + id);
        if (clientRev != rev) return new Out(REV_CONFLICT, null);

        String act = action == null ? "" : action;
        String date = args == null ? "" : args.optString("date", "");
        String text = args == null ? "" : args.optString("text", "");

        if ("pause".equals(act)) {
            p.put("enabled", false);
            adjust(p, "暂停", "方案不再计入今日待办");
        } else if ("resume".equals(act)) {
            p.put("enabled", true);
            adjust(p, "恢复", "方案重新计入今日待办");
        } else if ("rename".equals(act)) {
            String t = args == null ? "" : args.optString("title", "").trim();
            if (t.length() == 0) throw new JSONException("rename 需要 title");
            adjust(p, "改名", p.optString("title") + " → " + t);
            p.put("title", t);
        } else if ("remind_on".equals(act)) {
            p.put("remind", true);
            adjust(p, "提醒", "开启每日提醒");
        } else if ("remind_off".equals(act)) {
            p.put("remind", false);
            adjust(p, "提醒", "关闭每日提醒");
        } else if ("add_item".equals(act)) {
            JSONObject d = dayOf(p, date);
            if (d == null) throw new JSONException("找不到该日期：" + date);
            if (text.length() == 0) throw new JSONException("add_item 需要 text");
            d.getJSONArray("items").put(item(text, false));
            adjust(p, "调整", date + " 新增：" + text);
        } else if ("remove_item".equals(act)) {
            JSONObject d = dayOf(p, date);
            if (d == null) throw new JSONException("找不到该日期：" + date);
            JSONArray src = d.getJSONArray("items");
            JSONArray left = new JSONArray();
            boolean hit = false;
            for (int i = 0; i < src.length(); i++) {
                JSONObject it = src.getJSONObject(i);
                if (!hit && it.optString("text").equals(text)) { hit = true; continue; }
                left.put(it);
            }
            if (!hit) throw new JSONException("当天没有这一项：" + text);
            d.put("items", left);
            adjust(p, "调整", date + " 删除：" + text);
        } else if ("toggle_item".equals(act)) {
            JSONObject d = dayOf(p, date);
            if (d == null) throw new JSONException("找不到该日期：" + date);
            JSONArray src = d.getJSONArray("items");
            boolean hit = false;
            for (int i = 0; i < src.length(); i++) {
                JSONObject it = src.getJSONObject(i);
                if (!it.optString("text").equals(text)) continue;
                // done 显式给了就按给的写，没给就翻面——页面点一下是翻面，工具补录是显式
                boolean done = args != null && args.has("done")
                        ? args.optBoolean("done") : !it.optBoolean("done");
                it.put("done", done);
                hit = true;
                adjust(p, "调整", date + " 标记" + (done ? "完成" : "未完成") + "：" + text);
                break;
            }
            if (!hit) throw new JSONException("当天没有这一项：" + text);
        } else if ("set_days".equals(act)) {
            JSONArray days = args == null ? null : args.optJSONArray("days");
            if (days == null) throw new JSONException("set_days 需要 days 数组");
            JSONArray nd = normalizedDays(days);
            if (nd.length() == 0) throw new JSONException("days 里没有可用的条目");
            p.put("days", nd);
            p.put("totalDays", nd.length());
            adjust(p, "重排", "按调用方给定内容重排为 " + nd.length() + " 天");
        } else {
            throw new JSONException("未知 action：" + act);
        }

        rev++;
        if (!save()) {
            rev--;
            throw new JSONException("方案写盘失败，没有改动");
        }
        return new Out(rev, p);
    }

    /** 删方案。返回被删掉的那份，方便调用方回显。 */
    public synchronized Out delete(String id, int clientRev) throws JSONException {
        String pid = id == null ? "" : id.trim();
        if (pid.length() == 0) throw new JSONException("planId 不能为空");
        if (clientRev != rev) return new Out(REV_CONFLICT, null);

        JSONArray left = new JSONArray();
        JSONObject removed = null;
        for (int i = 0; i < plans.length(); i++) {
            JSONObject p = plans.optJSONObject(i);
            if (p == null) continue;
            if (pid.equals(p.optString("id"))) { removed = p; continue; }
            left.put(p);
        }
        if (removed == null) throw new JSONException("方案不存在：" + pid);

        JSONArray old = plans;
        plans = left;
        rev++;
        if (!save()) {
            rev--;
            plans = old;
            throw new JSONException("方案写盘失败，没有删除");
        }
        return new Out(rev, removed);
    }

    /** update 认识的 action，MCP schema 的枚举就是这一份。 */
    public static final String[] ACTIONS = {
            "pause", "resume", "rename", "remind_on", "remind_off",
            "add_item", "remove_item", "toggle_item", "set_days"
    };

    /* ================= 内部 ================= */

    private JSONObject planById(String id) {
        if (id == null) return null;
        for (int i = 0; i < plans.length(); i++) {
            JSONObject p = plans.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    private static JSONObject dayOf(JSONObject p, String date) {
        if (date == null || date.length() == 0) return null;
        JSONArray days = p.optJSONArray("days");
        if (days == null) return null;
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d != null && date.equals(d.optString("date"))) return d;
        }
        return null;
    }

    private static void adjust(JSONObject p, String reason, String change) throws JSONException {
        JSONArray adj = p.optJSONArray("adjustments");
        if (adj == null) {
            adj = new JSONArray();
            p.put("adjustments", adj);
        }
        adj.put(adjustment(reason, change));
    }

    private static JSONObject adjustment(String reason, String change) throws JSONException {
        JSONObject a = new JSONObject();
        a.put("ts", System.currentTimeMillis());
        a.put("reason", reason);
        a.put("change", change);
        return a;
    }

    /** 今天的日期键，格式与 days[].date 一致。测试也用它造「今天」的条目。 */
    public static String todayKey() {
        return key(new Date());
    }

    public static String key(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
    }

    private static Date plusDays(Date base, int n) {
        Calendar c = Calendar.getInstance();
        c.setTime(base);
        c.add(Calendar.DAY_OF_MONTH, n);
        return c.getTime();
    }

    /**
     * 模型没给逐日内容时的兜底拆解（agent Store.java 的原逻辑）：
     * 头一天拆小步、最后一天复盘、中间按天推进，每三天回头检查一次。
     */
    static JSONArray templateDays(String goal, int n) throws JSONException {
        JSONArray days = new JSONArray();
        Date now = new Date();
        for (int i = 0; i < n; i++) {
            JSONArray items = new JSONArray();
            if (i == 0) {
                items.put(item("把「" + goal + "」拆成可执行的小步", false));
                items.put(item("列出现有条件与约束", false));
            } else if (i == n - 1) {
                items.put(item("复盘整体结果与偏差", false));
                items.put(item("整理可复用的结论", false));
            } else {
                items.put(item("第 " + (i + 1) + " 步：推进「" + goal + "」", false));
                items.put(item("记录当日进展与阻塞", false));
                if (i % 3 == 0) items.put(item("回头检查前几日的产出", false));
            }
            JSONObject d = new JSONObject();
            d.put("date", key(plusDays(now, i)));
            d.put("items", items);
            days.put(d);
        }
        return days;
    }

    /**
     * 把调用方给的逐日内容洗成统一结构：items 里给字符串就补成 {text,done:false}，
     * 给对象就取 text/done。认不出来的条目直接丢，不留半个空壳。
     */
    static JSONArray normalizedDays(JSONArray in) throws JSONException {
        JSONArray out = new JSONArray();
        if (in == null) return out;
        for (int i = 0; i < in.length(); i++) {
            JSONObject d = in.optJSONObject(i);
            if (d == null) continue;
            JSONObject o = new JSONObject();
            o.put("date", d.optString("date", ""));
            JSONArray items = new JSONArray();
            JSONArray src = d.optJSONArray("items");
            if (src != null) {
                for (int k = 0; k < src.length(); k++) {
                    Object it = src.opt(k);
                    if (it instanceof String) {
                        String s = ((String) it).trim();
                        if (s.length() > 0) items.put(item(s, false));
                    } else if (it instanceof JSONObject) {
                        JSONObject j = (JSONObject) it;
                        String s = j.optString("text", "").trim();
                        if (s.length() > 0) items.put(item(s, j.optBoolean("done", false)));
                    }
                }
            }
            o.put("items", items);
            out.put(o);
        }
        return out;
    }

    /** 数一下逐日内容里到底有几项待办。全空就当这份计划没内容。 */
    static int countItems(JSONArray days) {
        int n = 0;
        if (days == null) return 0;
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null) continue;
            JSONArray items = d.optJSONArray("items");
            if (items != null) n += items.length();
        }
        return n;
    }

    private static JSONObject item(String text, boolean done) throws JSONException {        JSONObject o = new JSONObject();
        o.put("text", text);
        o.put("done", done);
        return o;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** id 要能当文件名之外的键用，也要人眼可读：36 进制时间戳 + 两位随机。 */
    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) (Math.random() * 1296), 36);
    }
}
