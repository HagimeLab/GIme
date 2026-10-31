package com.fan.shuatiku.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * PlanStore 的桌面回归测试。三件事必须钉住：
 * <ol>
 *   <li>增删改查与派生字段——页面和 MCP 工具都读同一份，算错了两边一起错；</li>
 *   <li>rev 乐观锁——别人改过就别覆盖，返回 -1 而不是硬写；</li>
 *   <li>落盘死在任意一步，下次启动都要能拿到一份完整方案库。</li>
 * </ol>
 *
 * <p>风格照 BankStoreTest：直接跑 main，逐条打 PASS/FAIL，有失败就 exit 1。
 */
public class PlanStoreTest {

    private static int pass = 0;
    private static int fail = 0;
    private static File root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("gime-planstore").toFile();

        testCreateTemplate();
        testDerivedFields();
        testToggle();
        testRevConflict();
        testActions();
        testRejections();
        testDelete();
        testLimits();
        testCrashRecovery();

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    /* ================= 建立 ================= */

    private static void testCreateTemplate() throws Exception {
        System.out.println();
        System.out.println("-- 建立方案（模板兜底拆解） --");
        File d = sub("create");
        PlanStore s = new PlanStore(d);
        check("新库 rev 从 0 开始", s.rev(), 0);
        check("新库没有方案", s.list().length(), 0);
        check("snapshot 带 rev", s.snapshot().indexOf("\"rev\"") >= 0, true);

        PlanStore.Out out = s.create("学会 TCP", 3, null, null, 0);
        check("建立返回成功", out.ok(), true);
        check("建立后 rev 前进一格", s.rev(), 1);
        check("建立后列表有一条", s.list().length(), 1);

        JSONObject p = out.plan;
        check("id 非空", p.optString("id").length() > 0, true);
        check("标题没给就用目标名", p.optString("title"), "学会 TCP");
        check("天数按参数", p.optInt("totalDays"), 3);
        check("默认启用", p.optBoolean("enabled"), true);
        check("默认不提醒", p.optBoolean("remind"), false);
        check("天数条数对得上", p.optJSONArray("days").length(), 3);
        check("第一天是今天", p.optJSONArray("days").optJSONObject(0).optString("date"), PlanStore.todayKey());
        check("第一天拆小步", items(p, 0).indexOf("把「学会 TCP」拆成可执行的小步") >= 0, true);
        check("第一天列出条件与约束", items(p, 0).indexOf("列出现有条件与约束") >= 0, true);
        check("最后一天复盘", items(p, 2).indexOf("复盘整体结果与偏差") >= 0, true);
        check("留了一条调整记录", p.optJSONArray("adjustments").length(), 1);
        check("调整记录写了原因", p.optJSONArray("adjustments").optJSONObject(0).optString("reason").length() > 0, true);

        // 重新打开：rev 与内容都还在，说明真的落盘了而不只是活在内存里
        PlanStore again = new PlanStore(d);
        check("重开后 rev 保持", again.rev(), 1);
        check("重开后方案还在", again.list().length(), 1);
        check("重开后 id 一致", again.list().optJSONObject(0).optString("id"), p.optString("id"));

        // 给了逐日内容就用给的，不再套模板
        JSONArray mine = new JSONArray();
        JSONObject day = new JSONObject();
        day.put("date", "2099-01-01");
        day.put("items", new JSONArray().put("自己排的第一件").put(new JSONObject().put("text", "已经做完的").put("done", true)));
        mine.put(day);
        PlanStore.Out own = s.create("自排计划", 1, "自定义标题", mine, s.rev());
        check("给了内容就不套模板", items(own.plan, 0).indexOf("自己排的第一件") >= 0, true);
        check("字符串条目补成未完成", own.plan.optJSONArray("days").optJSONObject(0)
                .optJSONArray("items").optJSONObject(0).optBoolean("done"), false);
        check("对象条目的 done 保留", own.plan.optJSONArray("days").optJSONObject(0)
                .optJSONArray("items").optJSONObject(1).optBoolean("done"), true);
        check("自定义标题生效", own.plan.optString("title"), "自定义标题");
    }

    /* ================= 派生字段 ================= */

    private static void testDerivedFields() throws Exception {
        System.out.println();
        System.out.println("-- 派生字段（done / total / pct / today） --");
        File d = sub("derive");
        PlanStore s = new PlanStore(d);
        s.create("背单词", 3, null, null, 0);

        JSONObject row = s.list().optJSONObject(0);
        // 3 天：第一天 2 项、中间一天 2 项、最后一天 2 项
        check("总项数按天累加", row.optInt("total"), 6);
        check("还没做时完成数是 0", row.optInt("done"), 0);
        check("完成率 0", row.optInt("pct"), 0);
        check("今天有排期就带 today", row.has("today"), true);
        check("today 是今天的条目", row.optJSONArray("today").length(), 2);
        check("列表不带整份 days（省流量）", row.has("days"), false);

        // 今天没排期时不能给一个空 today，否则「今天没事」和「这个方案今天不排期」分不清
        JSONArray future = new JSONArray();
        JSONObject day = new JSONObject();
        day.put("date", "2099-02-02");
        day.put("items", new JSONArray().put("很久以后的一件事"));
        future.put(day);
        PlanStore.Out o = s.create("未来计划", 1, null, future, s.rev());
        JSONObject row2 = find(s.list(), o.plan.optString("id"));
        check("今天没排期就不带 today", row2 != null && row2.has("today"), false);
        check("未来计划也进列表", s.list().length(), 2);
    }

    /* ================= 打卡 ================= */

    private static void testToggle() throws Exception {
        System.out.println();
        System.out.println("-- 打卡（toggle_item） --");
        File d = sub("toggle");
        PlanStore s = new PlanStore(d);
        String today = PlanStore.todayKey();
        PlanStore.Out c = s.create("跑步", 2, null, null, 0);
        String id = c.plan.optString("id");
        String first = "把「跑步」拆成可执行的小步";

        JSONObject args = new JSONObject().put("date", today).put("text", first);
        check("打卡返回成功", s.update(id, "toggle_item", args, s.rev()).ok(), true);
        check("打卡后完成数 +1", s.list().optJSONObject(0).optInt("done"), 1);
        check("完成率按四舍五入", s.list().optJSONObject(0).optInt("pct"), 25);

        // 不带 done 就是翻面：页面点一下是翻面
        s.update(id, "toggle_item", args, s.rev());
        check("再点一下翻回未完成", s.list().optJSONObject(0).optInt("done"), 0);

        // 带了 done 就按给的写，重复调用不会来回跳——工具补录要的是幂等
        JSONObject on = new JSONObject().put("date", today).put("text", first).put("done", true);
        s.update(id, "toggle_item", on, s.rev());
        check("显式 done=true 就写 true", s.list().optJSONObject(0).optInt("done"), 1);
        s.update(id, "toggle_item", on, s.rev());
        check("重复写 done=true 不翻面", s.list().optJSONObject(0).optInt("done"), 1);

        // 打卡要落盘：换一个实例读得到
        PlanStore again = new PlanStore(d);
        check("打卡结果落盘了", again.list().optJSONObject(0).optInt("done"), 1);
    }

    /* ================= rev 乐观锁 ================= */

    private static void testRevConflict() throws Exception {
        System.out.println();
        System.out.println("-- rev 乐观锁 --");
        File d = sub("rev");
        PlanStore s = new PlanStore(d);
        PlanStore.Out c = s.create("冲突测试", 2, null, null, 0);
        String id = c.plan.optString("id");
        int stale = 0;   // 建立之前手里的 rev

        check("旧 rev 建方案被拒", s.create("另一个方案", 2, null, null, stale).rev, PlanStore.REV_CONFLICT);
        check("被拒后方案数不变", s.list().length(), 1);
        check("被拒后 rev 不变", s.rev(), 1);

        JSONObject rename = new JSONObject().put("title", "改名了");
        check("旧 rev 改方案被拒", s.update(id, "rename", rename, stale).rev, PlanStore.REV_CONFLICT);
        check("被拒后标题没变", s.list().optJSONObject(0).optString("title"), "冲突测试");

        check("旧 rev 删方案被拒", s.delete(id, stale).rev, PlanStore.REV_CONFLICT);
        check("被拒后方案还在", s.list().length(), 1);

        check("setPlans 旧 rev 返回 -1", s.setPlans(new JSONArray(), stale), PlanStore.REV_CONFLICT);
        check("setPlans 被拒后方案还在", s.list().length(), 1);

        check("当前 rev 的 setPlans 被接受", s.setPlans(new JSONArray(), s.rev()), 2);
        check("setPlans 后清空", s.list().length(), 0);
    }

    /* ================= 各类 action ================= */

    private static void testActions() throws Exception {
        System.out.println();
        System.out.println("-- 各类 action --");
        File d = sub("actions");
        PlanStore s = new PlanStore(d);
        String today = PlanStore.todayKey();
        PlanStore.Out c = s.create("整理笔记", 3, null, null, 0);
        String id = c.plan.optString("id");
        JSONObject none = new JSONObject();

        s.update(id, "pause", none, s.rev());
        check("pause 后 enabled=false", s.list().optJSONObject(0).optBoolean("enabled"), false);
        s.update(id, "resume", none, s.rev());
        check("resume 后 enabled=true", s.list().optJSONObject(0).optBoolean("enabled"), true);

        s.update(id, "rename", new JSONObject().put("title", "整理读书笔记"), s.rev());
        check("rename 改掉标题", s.list().optJSONObject(0).optString("title"), "整理读书笔记");

        s.update(id, "remind_on", none, s.rev());
        check("remind_on 打开提醒", s.list().optJSONObject(0).optBoolean("remind"), true);
        s.update(id, "remind_off", none, s.rev());
        check("remind_off 关掉提醒", s.list().optJSONObject(0).optBoolean("remind"), false);

        int before = s.list().optJSONObject(0).optInt("total");
        s.update(id, "add_item", new JSONObject().put("date", today).put("text", "临时加的一项"), s.rev());
        check("add_item 多一项", s.list().optJSONObject(0).optInt("total"), before + 1);
        check("add_item 的文案进得去", items(s.find(id), 0).indexOf("临时加的一项") >= 0, true);

        s.update(id, "remove_item", new JSONObject().put("date", today).put("text", "临时加的一项"), s.rev());
        check("remove_item 又少一项", s.list().optJSONObject(0).optInt("total"), before);
        check("remove_item 真的删掉了", items(s.find(id), 0).indexOf("临时加的一项") >= 0, false);

        JSONArray nd = new JSONArray();
        JSONObject day = new JSONObject();
        day.put("date", "2099-02-02");
        day.put("items", new JSONArray().put("只做这一件"));
        nd.put(day);
        s.update(id, "set_days", new JSONObject().put("days", nd), s.rev());
        check("set_days 后只剩一天", s.list().optJSONObject(0).optInt("totalDays"), 1);
        check("set_days 后项数跟着变", s.list().optJSONObject(0).optInt("total"), 1);

        // 每次改动都留痕，页面的「调整记录」才有东西可看
        check("改动都留了调整记录", s.find(id).optJSONArray("adjustments").length() >= 9, true);
    }

    /* ================= 该拒的都拒 ================= */

    private static void testRejections() throws Exception {
        System.out.println();
        System.out.println("-- 该拒的都拒 --");
        File d = sub("reject");
        PlanStore s = new PlanStore(d);
        String today = PlanStore.todayKey();
        String id = s.create("拒绝测试", 2, null, null, 0).plan.optString("id");

        check("goal 空要报错", throwsOn(new Job() {
            public void run() throws Exception { s.create("   ", 3, null, null, s.rev()); }
        }), true);
        check("goal 空不该留下方案", s.list().length(), 1);

        check("未知 action 要报错", throwsOn(new Job() {
            public void run() throws Exception { s.update(id, "boom", new JSONObject(), s.rev()); }
        }), true);
        check("rename 缺 title 要报错", throwsOn(new Job() {
            public void run() throws Exception { s.update(id, "rename", new JSONObject(), s.rev()); }
        }), true);
        check("add_item 缺 text 要报错", throwsOn(new Job() {
            public void run() throws Exception {
                s.update(id, "add_item", new JSONObject().put("date", today), s.rev());
            }
        }), true);
        check("add_item 日期不存在要报错", throwsOn(new Job() {
            public void run() throws Exception {
                s.update(id, "add_item", new JSONObject().put("date", "1999-01-01").put("text", "x"), s.rev());
            }
        }), true);
        check("remove_item 没这一项要报错", throwsOn(new Job() {
            public void run() throws Exception {
                s.update(id, "remove_item", new JSONObject().put("date", today).put("text", "压根没有"), s.rev());
            }
        }), true);
        check("toggle_item 没这一项要报错", throwsOn(new Job() {
            public void run() throws Exception {
                s.update(id, "toggle_item", new JSONObject().put("date", today).put("text", "压根没有"), s.rev());
            }
        }), true);
        check("set_days 缺数组要报错", throwsOn(new Job() {
            public void run() throws Exception { s.update(id, "set_days", new JSONObject(), s.rev()); }
        }), true);
        check("改不存在的方案要报错", throwsOn(new Job() {
            public void run() throws Exception { s.update("nope", "pause", new JSONObject(), s.rev()); }
        }), true);
        check("删不存在的方案要报错", throwsOn(new Job() {
            public void run() throws Exception { s.delete("nope", s.rev()); }
        }), true);
        check("planId 空要报错", throwsOn(new Job() {
            public void run() throws Exception { s.delete("  ", s.rev()); }
        }), true);

        check("拒绝之后方案数没变", s.list().length(), 1);
        check("拒绝之后标题没变", s.list().optJSONObject(0).optString("title"), "拒绝测试");
    }

    /* ================= 删除 ================= */

    private static void testDelete() throws Exception {
        System.out.println();
        System.out.println("-- 删除 --");
        File d = sub("delete");
        PlanStore s = new PlanStore(d);
        PlanStore.Out a = s.create("甲", 2, null, null, 0);
        s.create("乙", 2, null, null, s.rev());
        check("建了两个", s.list().length(), 2);

        PlanStore.Out del = s.delete(a.plan.optString("id"), s.rev());
        check("删除返回成功", del.ok(), true);
        check("删掉的那份回显回来", del.plan.optString("title"), "甲");
        check("删完只剩一个", s.list().length(), 1);
        check("删的是对的那个", s.list().optJSONObject(0).optString("title"), "乙");

        PlanStore again = new PlanStore(d);
        check("删除也落盘了", again.list().length(), 1);
    }

    /* ================= 边界 ================= */

    private static void testLimits() throws Exception {
        System.out.println();
        System.out.println("-- 边界 --");
        File d = sub("limits");
        PlanStore s = new PlanStore(d);

        check("不给天数默认 7 天", s.create("默认天数", 0, null, null, s.rev()).plan.optInt("totalDays"), 7);
        check("负数天数也走默认 7 天", s.create("负天数", -5, null, null, s.rev()).plan.optInt("totalDays"), 7);
        check("天数被夹到上限", s.create("超长计划", 999, null, null, s.rev()).plan.optInt("totalDays"), PlanStore.MAX_DAYS);
        check("标题超长按 14 字截断", s.create("这是一个非常非常长的目标名称超过十四字", 1, null, null, s.rev())
                .plan.optString("title"), "这是一个非常非常长的目标名称…");

        // 逐日内容全认不出来时要报错，不能建一个空壳方案
        JSONArray junk = new JSONArray();
        JSONObject empty = new JSONObject();
        empty.put("date", "2099-03-03");
        empty.put("items", new JSONArray());
        junk.put(empty);
        check("逐日内容全空要报错", throwsOn(new Job() {
            public void run() throws Exception { s.create("空壳", 1, null, junk, s.rev()); }
        }), true);
    }

    /* ================= 落盘崩溃恢复 ================= */

    private static void testCrashRecovery() throws Exception {
        System.out.println();
        System.out.println("-- 落盘崩溃恢复 --");
        String good = "{\"rev\":7,\"plans\":[{\"id\":\"p1\",\"title\":\"好方案\",\"totalDays\":1,"
                + "\"days\":[{\"date\":\"2099-01-01\",\"items\":[{\"text\":\"一件事\",\"done\":false}]}]}]}";

        // 崩溃 1：正式文件半截、.bak 完整 —— 死在「挪走旧的」和「新的顶上」之间，
        // 唯一完整副本就是 .bak，必须还原成正式文件
        File d1 = sub("crash1");
        write(d1, "plans.json", "{\"rev\":7,\"plans\":[{\"id\":\"p1\",\"ti");
        write(d1, "plans.json.bak", good);
        PlanStore s1 = new PlanStore(d1);
        check("[崩溃1] 用 .bak 恢复", s1.rev(), 7);
        check("[崩溃1] 方案回来了", s1.list().length(), 1);
        check("[崩溃1] 半截文件被换成完整的", read(d1, "plans.json").indexOf("好方案") >= 0, true);
        check("[崩溃1] .bak 不再留着", new File(d1, "plans.json.bak").exists(), false);

        // 崩溃 2：正式文件半截、没有 .bak —— 当空库，不崩也不乱写
        File d2 = sub("crash2");
        write(d2, "plans.json", "{半截");
        PlanStore s2 = new PlanStore(d2);
        check("[崩溃2] 坏文件不崩", s2.rev(), 0);
        check("[崩溃2] 坏文件当空库", s2.list().length(), 0);

        // 崩溃 3：正式文件完整、.bak 是旧版 —— 以正式为准，旧备份清掉
        File d3 = sub("crash3");
        write(d3, "plans.json", good);
        write(d3, "plans.json.bak", "{\"rev\":1,\"plans\":[]}");
        PlanStore s3 = new PlanStore(d3);
        check("[崩溃3] 以正式文件为准", s3.rev(), 7);
        check("[崩溃3] 多余 .bak 被清", new File(d3, "plans.json.bak").exists(), false);

        // 崩溃 4：.tmp 残留 —— 永远是没写完的半成品，删掉
        File d4 = sub("crash4");
        write(d4, "plans.json", good);
        write(d4, "plans.json.tmp", "{\"rev\":9");
        PlanStore s4 = new PlanStore(d4);
        check("[崩溃4] 残留 .tmp 被清", new File(d4, "plans.json.tmp").exists(), false);
        check("[崩溃4] 正式内容不受影响", s4.rev(), 7);

        // 崩溃 5：三个残留并存 —— 正式完整就它说了算
        File d5 = sub("crash5");
        write(d5, "plans.json", good);
        write(d5, "plans.json.bak", "{\"rev\":1,\"plans\":[]}");
        write(d5, "plans.json.tmp", "{\"rev\":9");
        PlanStore s5 = new PlanStore(d5);
        check("[崩溃5] 三残留并存以正式为准", s5.rev(), 7);
        check("[崩溃5] .bak 清掉", new File(d5, "plans.json.bak").exists(), false);
        check("[崩溃5] .tmp 清掉", new File(d5, "plans.json.tmp").exists(), false);

        // 恢复之后还能正常写：说明 load() 没把内存和磁盘搞脱节
        s1.create("恢复后新建", 1, null, null, s1.rev());
        check("[崩溃1] 恢复后还能继续写", s1.list().length(), 2);
        check("[崩溃1] 写完真的落盘", new PlanStore(d1).list().length(), 2);
    }

    /* ================= 工具 ================= */

    private interface Job {
        void run() throws Exception;
    }

    private static boolean throwsOn(Job job) {
        try {
            job.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** 把某一天的条目文案拼起来，方便做「包含」断言。 */
    private static String items(JSONObject plan, int dayIdx) {
        StringBuilder b = new StringBuilder();
        if (plan == null) return "";
        JSONArray days = plan.optJSONArray("days");
        JSONObject day = days == null ? null : days.optJSONObject(dayIdx);
        JSONArray its = day == null ? null : day.optJSONArray("items");
        if (its != null) {
            for (int i = 0; i < its.length(); i++) {
                JSONObject it = its.optJSONObject(i);
                if (it != null) b.append(it.optString("text")).append('\n');
            }
        }
        return b.toString();
    }

    private static JSONObject find(JSONArray rows, String id) {
        if (rows == null || id == null) return null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private static File sub(String name) {
        File f = new File(root, name);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    private static String read(File dir, String name) throws Exception {
        File f = new File(dir, name);
        if (!f.exists()) return "<缺失>";
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File dir, String name, String content) throws Exception {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(String name, boolean actual, boolean expect) {
        if (actual == expect) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）");
        }
    }

    private static void check(String name, int actual, int expect) {
        if (actual == expect) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）");
        }
    }

    private static void check(String name, String actual, String expect) {
        if (expect == null ? actual == null : expect.equals(actual)) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）");
        }
    }
}
