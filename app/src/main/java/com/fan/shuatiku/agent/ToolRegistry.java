package com.fan.shuatiku.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 10 个工具的唯一定义处：名字、描述、严格 JSON Schema、前置条件、副作用、
 * 幂等声明、错误类型和 MCP 的 readOnly/destructive 标注都在这里。
 *
 * <p>执行仍留在页面 JS（题库解析、落盘、渲染复用同一套），本类只输出 schema：
 * 页面通过桥 {@code toolSchemas()} 取，MCP {@code tools/list} 与 AgentLoop
 * 组装的 {@code tools} 参数都读这一份，避免两处字面量漂移。
 *
 * <p>顺序固定（见 {@link #ORDER}）：schema 是 prompt 的前缀，顺序一变缓存就失效。
 */
public final class ToolRegistry {

    /** MCP / loop 共用的固定顺序。改动顺序等于改 prompt，需同步回归。 */
    public static final String[] ORDER = {
            "list_banks", "import_bank", "list_questions", "write_explanations", "write_answers",
            "create_plan", "update_plan", "delete_plan", "list_plans", "notify_phone"
    };

    private ToolRegistry() { }

    /** 页面桥口：{@code {"tools":[...]}}，顺序固定。 */
    public static String schemasJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("tools", tools());
        } catch (JSONException e) {
            return "{\"tools\":[],\"error\":\"schema 组装失败\"}";
        }
        return o.toString();
    }

    /** 按 {@link #ORDER} 输出全部工具。 */
    public static JSONArray tools() {
        JSONArray a = new JSONArray();
        a.put(listBanks());
        a.put(importBank());
        a.put(listQuestions());
        a.put(writeExplanations());
        a.put(writeAnswers());
        a.put(createPlan());
        a.put(updatePlan());
        a.put(deletePlan());
        a.put(listPlans());
        a.put(notifyPhone());
        return a;
    }

    /** 按名字取一个工具；未知返回 null。 */
    public static JSONObject find(String name) {
        JSONArray a = tools();
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t != null && name != null && name.equals(t.optString("name"))) return t;
        }
        return null;
    }

    /* ================= 工具定义 ================= */

    private static JSONObject listBanks() {
        return tool("list_banks", "列出本机当前题库：id、显示名、题量。", schema(emptyProps(), null),
                true, false, true, "none", new String[0], new String[]{"NOT_READY"});
    }

    private static JSONObject importBank() {
        JSONObject p = emptyProps();
        put(p, "name", with(prop("string", "题库显示名，缺省「MCP 导入」，最多 30 字"), "maxLength", 30));
        put(p, "text", with(prop("string", "题库正文，格式与 App 内导入一致"), "minLength", 1));
        put(p, "dryRun", prop("boolean", "只返回识别统计与前三题预览，不保存"));
        return tool("import_bank",
                "把一段题库文本按 App 的导入格式解析成新题库并保存。text 必填；dryRun=true 只解析不保存。",
                schema(p, new String[]{"text"}),
                false, false, false, "新增一个题库文件；dryRun 时不落盘",
                new String[]{"用户明确要求导入"}, new String[]{"INVALID_ARGS", "PARSE_EMPTY", "NAME_TOO_LONG", "WRITE_FAILED"});
    }

    private static JSONObject listQuestions() {
        JSONObject p = emptyProps();
        put(p, "bank", with(prop("string", "题库 id 或显示名"), "minLength", 1));
        put(p, "ids", arrayOf(prop("string", "题目 id"), "题目 id 列表", 1));
        put(p, "offset", with(prop("integer", "从第几题开始，默认 0"), "minimum", 0));
        put(p, "limit", with(with(prop("integer", "最多返回多少题，默认 20"), "minimum", 1), "maximum", 100));
        put(p, "type", with(prop("string", "single / multi / judge / fill / short"),
                "enum", arr(new String[]{"single", "multi", "judge", "fill", "short"})));
        put(p, "query", prop("string", "题干包含的文字"));
        return tool("list_questions",
                "提取一个题库里的题目与选项。ids 指定单题或多题；不给 ids 时按 offset/limit 分页。",
                schema(p, new String[]{"bank"}),
                true, false, true, "none", new String[]{"题库存在"}, new String[]{"BANK_NOT_FOUND", "NOT_READY"});
    }

    private static JSONObject writeExplanations() {
        JSONObject item = emptyProps();
        put(item, "id", with(prop("string", "题目 id"), "minLength", 1));
        put(item, "explain", prop("string", "新解析；给了就按 mode 覆盖或追加"));
        put(item, "ref", prop("string", "主观题参考答案；给了就按 mode 覆盖或追加"));
        JSONObject p = emptyProps();
        put(p, "bank", with(prop("string", "题库 id 或显示名"), "minLength", 1));
        put(p, "items", arrayOf(item, "至少一条解析/参考答案修改", 1));
        put(p, "mode", with(prop("string", "replace 覆盖 / append 追加"), "enum", arr(new String[]{"replace", "append"})));
        return tool("write_explanations",
                "修改或撰写题目解析（explain）与主观题参考答案（ref）。items 可只给一条；mode=replace 覆盖，append 追加。只在用户明确要求改解析/参考答案时调用。",
                schema(p, new String[]{"bank", "items"}),
                false, true, false, "重写题库文件里的解析/参考答案",
                new String[]{"题库存在", "用户明确要求修改"},
                new String[]{"BANK_NOT_FOUND", "ITEM_NOT_FOUND", "TOO_LONG", "WRITE_FAILED"});
    }

    private static JSONObject writeAnswers() {
        JSONObject item = emptyProps();
        put(item, "id", with(prop("string", "题目 id"), "minLength", 1));
        put(item, "answer", with(arrayOf(prop("string", "选项字母，如 A / C"), "非空选项字母数组", 1), "minItems", 1));
        JSONObject p = emptyProps();
        put(p, "bank", with(prop("string", "题库 id 或显示名"), "minLength", 1));
        put(p, "items", arrayOf(item, "至少一条答案修改", 1));
        return tool("write_answers",
                "修改客观题（单选题/多选题/判断题）的正确答案。items 每项给题目 id 和 answer 选项字母数组。只在用户明确要求改正确答案时调用；主观题请用 write_explanations 改参考答案。",
                schema(p, new String[]{"bank", "items"}),
                false, true, true, "重写题库文件里的答案",
                new String[]{"题库存在", "用户明确要求修改", "题目可判分"},
                new String[]{"BANK_NOT_FOUND", "ITEM_NOT_FOUND", "INVALID_ANSWER", "WRITE_FAILED"});
    }

    private static JSONObject createPlan() {
        JSONObject p = emptyProps();
        put(p, "goal", with(prop("string", "目标描述，例如：三个月跑完半程马拉松"), "minLength", 1));
        put(p, "totalDays", with(with(prop("integer", "天数 1-60，默认 7"), "minimum", 1), "maximum", 60));
        put(p, "title", prop("string", "方案标题，默认取目标前 14 字"));
        put(p, "days", daysProp("逐日内容，给了就不按模板拆解"));
        put(p, "notify", prop("boolean", "建立后是否推送手机通知，默认 true"));
        put(p, "note", prop("string", "通知正文，默认用方案标题"));
        return tool("create_plan",
                "为用户建立一个执行方案。可以只给目标和天数（按模板拆解），也可以直接给逐日内容；建立后可给手机发通知。",
                schema(p, new String[]{"goal"}),
                false, false, false, "新增 plans.json 条目；notify=true 时发通知",
                new String[]{"用户明确要求建立方案"}, new String[]{"INVALID_ARGS", "REV_CONFLICT", "WRITE_FAILED", "NOT_ALLOWED"});
    }

    private static JSONObject updatePlan() {
        JSONObject p = emptyProps();
        put(p, "planId", with(prop("string", "方案 id，可用 list_plans 查"), "minLength", 1));
        put(p, "action", with(prop("string", "pause / resume / rename / remind_on / remind_off / add_item / remove_item / toggle_item / set_days"),
                "enum", arr(PlanStore.ACTIONS)));
        put(p, "date", with(prop("string", "日期 YYYY-MM-DD，add_item / remove_item / toggle_item 需要"),
                "pattern", "^\\d{4}-\\d{2}-\\d{2}$"));
        put(p, "text", prop("string", "执行项文本，add_item / remove_item / toggle_item 需要"));
        put(p, "done", prop("boolean", "toggle_item 时指定目标状态，不填则取反"));
        put(p, "title", prop("string", "rename 时的新标题"));
        put(p, "days", daysProp("set_days 时的新逐日内容，形同 create_plan 的 days"));
        put(p, "notify", prop("boolean", "改完是否推送手机通知，默认 false"));
        put(p, "note", prop("string", "通知正文"));
        return tool("update_plan",
                "调整已有方案：暂停/恢复、改名、开关提醒、给某天加删执行项、勾选完成、或整体重排逐日内容。",
                schema(p, new String[]{"planId", "action"}),
                false, true, false, "重写 plans.json 条目；notify=true 时发通知",
                new String[]{"方案存在", "用户明确要求修改"},
                new String[]{"PLAN_NOT_FOUND", "UNKNOWN_ACTION", "REV_CONFLICT", "WRITE_FAILED", "NOT_ALLOWED"});
    }

    private static JSONObject deletePlan() {
        JSONObject p = emptyProps();
        put(p, "planId", with(prop("string", "方案 id，可用 list_plans 查"), "minLength", 1));
        return tool("delete_plan", "删除一个方案及其执行记录，不能恢复。", schema(p, new String[]{"planId"}),
                false, true, false, "移除 plans.json 条目",
                new String[]{"方案存在", "用户明确要求删除"}, new String[]{"PLAN_NOT_FOUND", "REV_CONFLICT", "WRITE_FAILED"});
    }

    private static JSONObject listPlans() {
        return tool("list_plans", "列出所有方案及其进度、今天要做的项，用来决定下一步调整。", schema(emptyProps(), null),
                true, false, true, "none", new String[0], new String[]{"NOT_READY"});
    }

    private static JSONObject notifyPhone() {
        JSONObject p = emptyProps();
        put(p, "title", prop("string", "通知标题，默认「方案提醒」"));
        put(p, "body", with(prop("string", "通知正文"), "minLength", 1));
        return tool("notify_phone",
                "给用户手机发一条通知。App 运行时立即弹出；App 没开就发不出来。",
                schema(p, new String[]{"body"}),
                false, false, false, "在通知栏弹一条通知",
                new String[]{"通知权限已放行"}, new String[]{"INVALID_ARGS", "NOT_ALLOWED"});
    }

    /* ================= 组装助手 ================= */

    private static JSONObject tool(String name, String desc, JSONObject schema,
                                  boolean readOnly, boolean destructive, boolean idempotent,
                                  String sideEffects, String[] preconditions, String[] errors) {
        JSONObject t = new JSONObject();
        JSONObject a = new JSONObject();
        try {
            t.put("name", name);
            t.put("description", desc);
            t.put("inputSchema", schema);
            a.put("readOnlyHint", readOnly);
            a.put("destructiveHint", destructive);
            a.put("idempotent", idempotent);
            a.put("sideEffects", sideEffects);
            a.put("preconditions", arr(preconditions));
            a.put("errors", arr(errors));
            t.put("annotations", a);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return t;
    }

    /** 所有对象层都关 additionalProperties：漏字段就让调用方显式补，别静默吞。 */
    private static JSONObject schema(JSONObject props, String[] required) {
        JSONObject s = new JSONObject();
        try {
            s.put("type", "object");
            s.put("properties", props);
            s.put("additionalProperties", false);
            if (required != null && required.length > 0) s.put("required", arr(required));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    private static JSONObject emptyProps() {
        return new JSONObject();
    }

    private static JSONObject prop(String type, String desc) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            o.put("description", desc);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    private static JSONObject with(JSONObject o, String key, Object v) {
        try {
            o.put(key, v);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    private static void put(JSONObject o, String key, Object v) {
        try {
            o.put(key, v);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONArray arr(String[] values) {
        JSONArray a = new JSONArray();
        if (values != null) for (String v : values) a.put(v);
        return a;
    }

    private static JSONArray arr(JSONObject item) {
        JSONArray a = new JSONArray();
        a.put(item);
        return a;
    }

    private static JSONObject arrayOf(JSONObject item, String desc, int minItems) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "array");
            o.put("description", desc);
            o.put("items", item);
            o.put("minItems", minItems);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    /** 逐日内容：date + items（字符串或 {text,done}），两处 schema 共用。 */
    private static JSONObject daysProp(String desc) {
        JSONObject item = emptyProps();
        put(item, "text", with(prop("string", "执行项文本"), "minLength", 1));
        put(item, "done", prop("boolean", "是否完成，默认 false"));
        JSONObject objItem = schema(item, new String[]{"text"});
        JSONObject any = new JSONObject();
        try {
            any.put("anyOf", new JSONArray().put(prop("string", "执行项文本")).put(objItem));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        JSONObject day = emptyProps();
        put(day, "date", with(prop("string", "日期 YYYY-MM-DD"), "pattern", "^\\d{4}-\\d{2}-\\d{2}$"));
        JSONObject items = new JSONObject();
        try {
            items.put("type", "array");
            items.put("items", any);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        put(day, "items", items);
        JSONObject o = new JSONObject();
        try {
            o.put("type", "array");
            o.put("description", desc);
            o.put("items", schema(day, new String[]{"date", "items"}));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }
}
