import com.fan.shuatiku.agent.RunTrace;

import org.json.JSONArray;
import org.json.JSONObject;

/** RunTrace：三级 span、脱敏、环形上限与指标。 */
public class RunTraceTest {

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

    public static void main(String[] args) throws Exception {
        final StringBuilder logs = new StringBuilder();
        RunTrace.setSink(new RunTrace.Sink() {
            @Override public void log(String line) { logs.append(line).append('\n'); }
        });
        RunTrace.reset();

        System.out.println();
        System.out.println("-- 三级 span --");
        RunTrace.start("run-test");
        RunTrace.round(1, 120, 40, 350);
        RunTrace.round(2, 160, 60, 280);
        RunTrace.tool("write_answers", "write_answers:{\"secret\":\"题干原文不应出现\"}", "preview", 0, "NEEDS_APPROVAL");
        RunTrace.tool("write_answers", "write_answers:{\"secret\":\"题干原文不应出现\"}", "allow", 37, "OK");
        RunTrace.finish("ok");
        JSONObject root = new JSONObject(RunTrace.exportJson());
        JSONArray runs = root.getJSONArray("runs");
        ok("一次 run 被保存", runs.length() == 1, String.valueOf(runs.length()));
        JSONObject run = runs.getJSONObject(0);
        ok("traceId / status 在顶层", "run-test".equals(run.optString("traceId"))
                && "ok".equals(run.optString("status")));
        ok("round 级 span 有两条", run.getJSONArray("rounds").length() == 2);
        ok("tool_call 级 span 有两条", run.getJSONArray("toolCalls").length() == 2);
        ok("首 token 延迟记录", run.getJSONArray("rounds").getJSONObject(0).optInt("firstTokenMs") == 350);

        System.out.println();
        System.out.println("-- 脱敏 --");
        String raw = RunTrace.exportJson();
        ok("参数原文不进诊断包", raw.indexOf("题干原文不应出现") < 0 && raw.indexOf("secret") < 0);
        String sig = run.getJSONArray("toolCalls").getJSONObject(1).optString("sig");
        ok("签名只留 12 位 hash", sig.matches("[0-9a-f]{12}"), sig);

        System.out.println();
        System.out.println("-- 指标 --");
        JSONObject m = root.getJSONObject("metrics");
        ok("完成/停止/平均轮数", m.optInt("completed") == 1 && m.optInt("stopped") == 0
                && Math.abs(m.optDouble("avgRounds") - 2.0) < 0.001, m.toString());
        ok("工具成功率按 OK/ALLOW 算", Math.abs(m.optDouble("toolSuccessRate") - 1.0) < 0.001,
                m.toString());
        ok("平均首 token 延迟", m.optInt("avgFirstTokenMs") == 315, m.toString());

        System.out.println();
        System.out.println("-- 环形上限 --");
        for (int i = 0; i < 25; i++) {
            RunTrace.start("r" + i);
            RunTrace.round(1, 1, 1, 1);
            RunTrace.finish(i % 2 == 0 ? "ok" : "stopped");
        }
        JSONArray capped = new JSONObject(RunTrace.exportJson()).getJSONArray("runs");
        ok("只保留最近 20 次", capped.length() == RunTrace.MAX_RUNS, String.valueOf(capped.length()));
        ok("最早一次被挤掉", !"run-test".equals(capped.getJSONObject(0).optString("traceId")));

        System.out.println();
        System.out.println("-- 日志出口 --");
        ok("sink 收到 run/tool/round 行", logs.indexOf("run_start") >= 0
                && logs.indexOf("tool ") >= 0 && logs.indexOf("round ") >= 0);

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }
}
