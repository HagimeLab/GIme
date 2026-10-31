import com.fan.shuatiku.agent.AgentLoop;

import org.json.JSONObject;

/**
 * AgentLoop 的四类剧本：正常多轮收敛 / 超预算收尾 / 死循环熔断 / 写操作未批准不落地。
 * 引擎不碰网络与工具，这里用 JSON 剧本模拟页面每轮问一次闸门。
 */
public class AgentLoopTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        ok(name, cond, null);
    }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : "  (" + detail + ")"));
        }
    }

    private static JSONObject gate(String tool, String args) throws Exception {
        return new JSONObject(AgentLoop.handle("check",
                new JSONObject().put("tool", tool).put("args", new JSONObject(args)).toString()));
    }

    private static JSONObject tick(int p, int c) throws Exception {
        return new JSONObject(AgentLoop.handle("tick",
                new JSONObject().put("promptTokens", p).put("completionTokens", c).toString()));
    }

    public static void main(String[] args) throws Exception {
        testNormalMultiRound();
        testBudgetRounds();
        testTokenBudget();
        testRepeatBreaker();
        testWriteApproval();
        testWriteDenied();
        testNotifyDedupe();
        testHandleOps();

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    private static void testNormalMultiRound() throws Exception {
        System.out.println();
        System.out.println("-- 剧本 1：正常多轮收敛 --");
        AgentLoop.reset();
        AgentLoop.begin(8, 24000);
        ok("读错题 → allow", "allow".equals(gate("list_questions", "{\"bank\":\"b1\",\"query\":\"错题\"}").optString("action")));
        ok("第 1 轮计数", tick(120, 40).optInt("rounds") == 1);
        ok("建方案 → allow", "allow".equals(gate("create_plan", "{\"goal\":\"7 天错题\"}").optString("action")));
        ok("第 2 轮计数", tick(160, 60).optInt("rounds") == 2);
        ok("发通知 → allow", "allow".equals(gate("notify_phone", "{\"body\":\"计划已建好\"}").optString("action")));
        ok("第 3 轮不触顶", !tick(80, 20).optBoolean("stopped"));
        JSONObject fin = new JSONObject(AgentLoop.finish());
        ok("收口统计：3 次工具、0 次熔断", fin.optInt("toolCalls") == 3 && fin.optInt("repeats") == 0,
                fin.toString());
    }

    private static void testBudgetRounds() throws Exception {
        System.out.println();
        System.out.println("-- 剧本 2a：轮次预算 --");
        AgentLoop.reset();
        AgentLoop.begin(2, 24000);
        ok("第 1 轮未触顶", !tick(10, 10).optBoolean("stopped"));
        JSONObject t2 = tick(10, 10);
        ok("第 2 轮触顶", t2.optBoolean("stopped") && "BUDGET_ROUNDS".equals(t2.optString("stopReason")),
                t2.toString());
        JSONObject g = gate("list_questions", "{\"bank\":\"b1\"}");
        ok("触顶后闸门直接 stop", "stop".equals(g.optString("action")) && "BUDGET_ROUNDS".equals(g.optString("reason")),
                g.toString());
    }

    private static void testTokenBudget() throws Exception {
        System.out.println();
        System.out.println("-- 剧本 2b：token 预算 --");
        AgentLoop.reset();
        AgentLoop.begin(8, 100);
        JSONObject t = tick(80, 30);
        ok("token 超限立即停", t.optBoolean("stopped") && "BUDGET_TOKENS".equals(t.optString("stopReason")),
                t.toString());
        ok("闸门同步 stop", "stop".equals(gate("list_plans", "{}").optString("action")));
    }

    private static void testRepeatBreaker() throws Exception {
        System.out.println();
        System.out.println("-- 剧本 3：死循环熔断 --");
        AgentLoop.reset();
        AgentLoop.begin(8, 24000);
        JSONObject a = gate("list_questions", "{\"bank\":\"b1\",\"ids\":[\"q1\"],\"limit\":5}");
        ok("第一次 allow", "allow".equals(a.optString("action")));
        JSONObject b = gate("list_questions", "{\"limit\":5,\"ids\":[\"q1\"],\"bank\":\"b1\"}");
        ok("键序不同也算同一调用 → deny",
                "deny".equals(b.optString("action")) && "REPEAT_CALL".equals(b.optString("reason")), b.toString());
        JSONObject c = gate("list_questions", "{\"bank\":\"b1\",\"ids\":[\"q2\"]}");
        ok("换参数可以继续", "allow".equals(c.optString("action")));
        ok("熔断计数", new JSONObject(AgentLoop.finish()).optInt("repeats") == 1);
    }

    private static void testWriteApproval() throws Exception {
        System.out.println();
        System.out.println("-- 剧本 4a：写操作未批准不落地 --");
        AgentLoop.reset();
        AgentLoop.begin(8, 24000);
        JSONObject p = gate("write_answers", "{\"bank\":\"b1\",\"items\":[{\"id\":\"q1\",\"answer\":[\"A\"]}]}");
        ok("第一次只给 preview", "preview".equals(p.optString("action")) &&
                "NEEDS_APPROVAL".equals(p.optString("reason")), p.toString());
        String sig = p.optString("signature");
        ok("未批准前再问一次仍是 preview", "preview".equals(gate("write_answers",
                "{\"bank\":\"b1\",\"items\":[{\"id\":\"q1\",\"answer\":[\"A\"]}]}").optString("action")));
        AgentLoop.approve(sig);
        ok("批准后 allow", "allow".equals(gate("write_answers",
                "{\"bank\":\"b1\",\"items\":[{\"id\":\"q1\",\"answer\":[\"A\"]}]}").optString("action")));
        ok("执行后重复调用被拦（幂等）", "deny".equals(gate("write_answers",
                "{\"bank\":\"b1\",\"items\":[{\"id\":\"q1\",\"answer\":[\"A\"]}]}").optString("action")));
    }

    private static void testWriteDenied() throws Exception {
        System.out.println();
        System.out.println("-- 剧本 4b：用户取消后不执行、不重试 --");
        AgentLoop.reset();
        AgentLoop.begin(8, 24000);
        JSONObject p = gate("delete_plan", "{\"planId\":\"p1\"}");
        AgentLoop.deny(p.optString("signature"));
        JSONObject again = gate("delete_plan", "{\"planId\":\"p1\"}");
        ok("取消后再问直接 deny", "deny".equals(again.optString("action")) &&
                "DENIED".equals(again.optString("reason")), again.toString());
    }

    private static void testNotifyDedupe() throws Exception {
        System.out.println();
        System.out.println("-- notify_phone 按参数去重 --");
        AgentLoop.reset();
        AgentLoop.begin(8, 24000);
        ok("第一次通知 allow", "allow".equals(gate("notify_phone", "{\"body\":\"去刷题\"}").optString("action")));
        ok("同参数第二次 deny", "deny".equals(gate("notify_phone", "{\"body\":\"去刷题\"}").optString("action")));
        ok("换正文可以再发", "allow".equals(gate("notify_phone", "{\"body\":\"换一句\"}").optString("action")));
    }

    private static void testHandleOps() throws Exception {
        System.out.println();
        System.out.println("-- 桥口分发 --");
        AgentLoop.reset();
        ok("begin 返回 runId", new JSONObject(AgentLoop.handle("begin", "{}")).optString("runId").startsWith("run"));
        ok("未知 op 报错", new JSONObject(AgentLoop.handle("nope", "{}")).has("error"));
        ok("坏 JSON 报错", new JSONObject(AgentLoop.handle("begin", "{")).has("error"));
    }
}
