import com.fan.shuatiku.agent.PlanStore;
import com.fan.shuatiku.agent.ToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ToolRegistry 的桌面回归：10 个工具、固定顺序、严格 schema、annotations 完整。
 * 它是 MCP tools/list 与 AgentLoop tools 参数的唯一真源，这里一红两边都别想绿。
 */
public class ToolRegistryTest {

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
        JSONArray tools = ToolRegistry.tools();
        System.out.println();
        System.out.println("-- 工具数量与顺序 --");
        ok("共 10 个工具", tools.length() == 10, "实际 " + tools.length());
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < tools.length(); i++) names.add(tools.getJSONObject(i).optString("name"));
        ok("顺序与 ORDER 一字不差", names.equals(Arrays.asList(ToolRegistry.ORDER)), names.toString());
        ok("schemasJson 稳定可重复", ToolRegistry.schemasJson().equals(ToolRegistry.schemasJson()));

        System.out.println();
        System.out.println("-- 每个工具的六要素 --");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.getJSONObject(i);
            String name = t.optString("name", "(空)");
            ok(name + "：描述非空", t.optString("description", "").trim().length() > 0);
            JSONObject s = t.optJSONObject("inputSchema");
            ok(name + "：inputSchema 是 object 且禁多余字段",
                    s != null && "object".equals(s.optString("type"))
                            && s.optBoolean("additionalProperties", true) == false);
            ok(name + "：properties 存在", s != null && s.optJSONObject("properties") != null);

            JSONObject a = t.optJSONObject("annotations");
            ok(name + "：annotations 完整",
                    a != null && a.has("readOnlyHint") && a.has("destructiveHint")
                            && a.has("idempotent") && a.has("sideEffects")
                            && a.optJSONArray("preconditions") != null
                            && a.optJSONArray("errors") != null
                            && a.optJSONArray("errors").length() > 0,
                    a == null ? "null" : a.toString());
            ok(name + "：readOnly 与 destructive 不相容",
                    !(a != null && a.optBoolean("readOnlyHint") && a.optBoolean("destructiveHint")));

            /* required 里的键必须真的在 properties 里，拼错一个词模型就会一直补必填 */
            JSONArray req = s == null ? null : s.optJSONArray("required");
            boolean reqOk = true;
            List<String> missing = new ArrayList<String>();
            if (req != null) {
                JSONObject props = s.optJSONObject("properties");
                for (int k = 0; k < req.length(); k++) {
                    String key = req.optString(k, "");
                    if (props == null || !props.has(key)) { reqOk = false; missing.add(key); }
                }
            }
            ok(name + "：required 键都在 properties 里", reqOk, missing.toString());
        }

        System.out.println();
        System.out.println("-- 关键 schema 细节 --");
        JSONObject update = ToolRegistry.find("update_plan");
        JSONArray actions = update.getJSONObject("inputSchema").getJSONObject("properties")
                .getJSONObject("action").getJSONArray("enum");
        List<String> actList = new ArrayList<String>();
        for (int i = 0; i < actions.length(); i++) actList.add(actions.getString(i));
        ok("update_plan 的 action 枚举 = PlanStore.ACTIONS",
                actList.equals(Arrays.asList(PlanStore.ACTIONS)), actList.toString());
        JSONObject create = ToolRegistry.find("create_plan");
        JSONObject td = create.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("totalDays");
        ok("create_plan 天数有上下界", td.optInt("minimum", -1) == 1 && td.optInt("maximum", -1) == 60);

        System.out.println();
        System.out.println("-- readOnly / destructive 标注 --");
        ok("list_banks / list_questions / list_plans 是只读",
                readOnly("list_banks") && readOnly("list_questions") && readOnly("list_plans"));
        ok("delete_plan 是 destructive", destructive("delete_plan"));
        ok("notify_phone 不是只读", !readOnly("notify_phone"));
        ok("import_bank 不 destructive（新建题库）", !destructive("import_bank"));

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    private static boolean readOnly(String name) {
        return ToolRegistry.find(name).getJSONObject("annotations").optBoolean("readOnlyHint");
    }

    private static boolean destructive(String name) {
        return ToolRegistry.find(name).getJSONObject("annotations").optBoolean("destructiveHint");
    }
}
