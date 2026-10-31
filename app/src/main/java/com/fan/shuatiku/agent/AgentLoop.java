package com.fan.shuatiku.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Agent 循环的护栏引擎：不碰网络、不执行工具，只回答「这一步能不能做」。
 *
 * <p>页面 JS 仍是唯一的循环执行器与工具执行处（流式上屏、题库解析、落盘都在那边）；
 * 这个类持有一次 run 的预算、重复调用集合与写操作批准状态，页面每个工具调用前
 * 经桥 {@code agentCall("check", ...)} 问一次，保证护栏只有一份实现、桌面可穷举测试。
 *
 * <p>三道护栏：
 * <ol>
 *   <li>轮次 / token 预算：超过就 stop，让页面用已有信息收尾；</li>
 *   <li>重复调用熔断：同一 (tool, args 规范化) 第二次直接 deny，并提示换策略；</li>
 *   <li>写操作 HITL：write_answers / write_explanations / delete_plan 第一次只返回
 *       preview，批准后才 allow；未批准不执行、不重试。</li>
 * </ol>
 *
 * <p>notify_phone 不在写工具名单里，但重复调用同样会被第二道护栏拦住（按参数去重）。
 */
public final class AgentLoop {

    public static final int DEFAULT_MAX_ROUNDS = 8;
    public static final int DEFAULT_MAX_TOKENS = 120000;

    /** 改数据前必须让用户看一眼的工具。 */
    public static final String[] WRITE_TOOLS = {"write_answers", "write_explanations", "delete_plan"};

    private static final Set<String> WRITE = new HashSet<String>();
    static {
        for (String t : WRITE_TOOLS) WRITE.add(t);
    }

    private static String runId = "";
    private static int maxRounds = DEFAULT_MAX_ROUNDS;
    private static int maxTokens = DEFAULT_MAX_TOKENS;
    private static int rounds;
    private static int tokens;
    private static int toolCalls;
    private static int repeats;
    private static int previews;
    private static boolean stopped;
    private static String stopReason = "";
    private static final Map<String, Integer> seen = new TreeMap<String, Integer>();
    private static final Set<String> approved = new HashSet<String>();
    private static final Set<String> denied = new HashSet<String>();
    private static long seq;

    private AgentLoop() { }

    public static synchronized void reset() {
        runId = "";
        maxRounds = DEFAULT_MAX_ROUNDS;
        maxTokens = DEFAULT_MAX_TOKENS;
        rounds = tokens = toolCalls = repeats = previews = 0;
        stopped = false;
        stopReason = "";
        seen.clear();
        approved.clear();
        denied.clear();
    }

    public static synchronized String begin(int roundsLimit, int tokensLimit) {
        reset();
        maxRounds = roundsLimit > 0 ? roundsLimit : DEFAULT_MAX_ROUNDS;
        maxTokens = tokensLimit > 0 ? tokensLimit : DEFAULT_MAX_TOKENS;
        runId = "run" + (++seq);
        RunTrace.start(runId);
        JSONObject o = new JSONObject();
        try {
            o.put("runId", runId);
            o.put("maxRounds", maxRounds);
            o.put("maxTokens", maxTokens);
            o.put("rounds", 0);
            o.put("tokens", 0);
        } catch (JSONException ignored) { }
        return o.toString();
    }

    /** 每收到一轮模型回包调一次。返回是否已经触顶。 */
    public static synchronized String tick(int promptTokens, int completionTokens) {
        if (stopped) return summaryJson();
        rounds++;
        tokens += Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (rounds >= maxRounds) {
            stopped = true;
            stopReason = "BUDGET_ROUNDS";
        } else if (tokens > maxTokens) {
            stopped = true;
            stopReason = "BUDGET_TOKENS";
        }
        return summaryJson();
    }

    /**
     * 工具执行前的闸门。
     *
     * @return action = allow / deny / preview / stop；stop 时 reason 是触顶类型
     */
    public static synchronized String check(String tool, JSONObject args) {
        JSONObject o = new JSONObject();
        try {
            String name = tool == null ? "" : tool.trim();
            String sig = signature(name, args);
            o.put("tool", name);
            o.put("signature", sig);
            if (stopped) return action(o, "stop", stopReason);
            if (denied.contains(sig)) {
                RunTrace.tool(name, sig, "deny", 0, "DENIED");
                return action(o, "deny", "DENIED");
            }
            Integer n = seen.get(sig);
            if (n != null && n > 0) {
                repeats++;
                o.put("count", n + 1);
                o.put("message", "同一个工具和参数已经执行过，结果不会变；请停止重试或换一个策略。");
                RunTrace.tool(name, sig, "deny", 0, "REPEAT_CALL");
                return action(o, "deny", "REPEAT_CALL");
            }
            if (WRITE.contains(name) && !approved.contains(sig)) {
                previews++;
                o.put("args", args == null ? new JSONObject() : args);
                RunTrace.tool(name, sig, "preview", 0, "NEEDS_APPROVAL");
                return action(o, "preview", "NEEDS_APPROVAL");
            }
            seen.put(sig, 1);
            toolCalls++;
            return action(o, "allow", "OK");
        } catch (JSONException e) {
            return "{\"action\":\"deny\",\"reason\":\"BAD_REQUEST\"}";
        }
    }

    public static synchronized String approve(String signature) {
        approved.add(signature == null ? "" : signature);
        return "{\"ok\":true,\"signature\":" + quote(signature) + "}";
    }

    public static synchronized String deny(String signature) {
        String s = signature == null ? "" : signature;
        denied.add(s);
        seen.put(s, 1);
        return "{\"ok\":true,\"signature\":" + quote(s) + "}";
    }

    public static synchronized String finish() {
        return summaryJson();
    }

    /** 页面桥口：一个方法分发全部 run 操作，少几个反射面。 */
    public static synchronized String handle(String op, String json) {
        try {
            JSONObject a = json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
            String o = op == null ? "" : op.trim();
            if ("begin".equals(o)) return begin(a.optInt("maxRounds", DEFAULT_MAX_ROUNDS),
                    a.optInt("maxTokens", DEFAULT_MAX_TOKENS));
            if ("tick".equals(o)) {
                String t = tick(a.optInt("promptTokens", 0), a.optInt("completionTokens", 0));
                RunTrace.round(rounds, a.optInt("promptTokens", 0), a.optInt("completionTokens", 0),
                        a.optInt("firstTokenMs", 0));
                return t;
            }
            if ("check".equals(o)) return check(a.optString("tool", ""), a.optJSONObject("args"));
            if ("toolResult".equals(o)) {
                RunTrace.tool(a.optString("tool", ""), a.optString("signature", ""),
                        a.optBoolean("ok", true) ? "allow" : "error",
                        a.optLong("ms", 0), a.optString("code", ""));
                return "{\"ok\":true}";
            }
            if ("approve".equals(o)) return approve(a.optString("signature", ""));
            if ("deny".equals(o)) return deny(a.optString("signature", ""));
            if ("finish".equals(o)) {
                String status = a.optString("status", stopped ? "stopped" : "ok");
                RunTrace.finish(status);
                return finish();
            }
            if ("trace".equals(o)) return RunTrace.exportJson();
            if ("reset".equals(o)) { reset(); RunTrace.reset(); return "{\"ok\":true}"; }
            return "{\"error\":" + quote("未知 op：" + o) + "}";
        } catch (JSONException e) {
            return "{\"error\":\"bad json\"}";
        }
    }

    /* ================= 内部 ================= */

    private static String action(JSONObject o, String action, String reason) throws JSONException {
        o.put("action", action);
        o.put("reason", reason);
        return o.toString();
    }

    private static String summaryJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("runId", runId);
            o.put("rounds", rounds);
            o.put("maxRounds", maxRounds);
            o.put("tokens", tokens);
            o.put("maxTokens", maxTokens);
            o.put("toolCalls", toolCalls);
            o.put("repeats", repeats);
            o.put("previews", previews);
            o.put("stopped", stopped);
            o.put("stopReason", stopReason);
        } catch (JSONException ignored) { }
        return o.toString();
    }

    private static String signature(String tool, JSONObject args) {
        return tool + ":" + canon(args == null ? new JSONObject() : args);
    }

    /** 参数规范化：键排序、递归，模型换个键序不会绕开重复调用护栏。 */
    private static String canon(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof JSONObject) {
            TreeMap<String, Object> m = new TreeMap<String, Object>();
            JSONObject o = (JSONObject) v;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.opt(k));
            }
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(quote(e.getKey())).append(':').append(canon(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) b.append(',');
                b.append(canon(a.opt(i)));
            }
            return b.append(']').toString();
        }
        if (v instanceof String) return quote((String) v);
        return String.valueOf(v);
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
