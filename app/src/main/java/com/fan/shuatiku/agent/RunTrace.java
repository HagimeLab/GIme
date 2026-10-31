package com.fan.shuatiku.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Agent 运行的三级 span：trace(run) → round → tool_call。
 *
 * <p>不碰 Android；日志出口由宿主注一个 {@link Sink}（App 里接 logcat GImeAgent）。
 * 只存脱敏信息：工具签名取 SHA-256 前 12 位，不存参数原文；导出诊断包也只带这些。
 * 内存里保留最近 {@link #MAX_RUNS} 次 run，供「我的 → 导出诊断包」取用。
 */
public final class RunTrace {

    public static final int MAX_RUNS = 20;

    public interface Sink {
        void log(String line);
    }

    private static final List<Run> runs = new ArrayList<Run>();
    private static Run current;
    private static Sink sink;

    private RunTrace() { }

    public static synchronized void setSink(Sink s) {
        sink = s;
    }

    public static synchronized void reset() {
        runs.clear();
        current = null;
    }

    public static synchronized void start(String runId) {
        current = new Run(runId == null ? "" : runId);
        if (sink != null) sink.log("run_start " + current.id);
    }

    public static synchronized void round(int n, int promptTokens, int completionTokens, int firstTokenMs) {
        if (current == null) return;
        Round r = new Round();
        r.n = n;
        r.promptTokens = Math.max(0, promptTokens);
        r.completionTokens = Math.max(0, completionTokens);
        r.firstTokenMs = Math.max(0, firstTokenMs);
        current.rounds.add(r);
        if (sink != null) {
            sink.log("round " + n + " pt=" + r.promptTokens + " ct=" + r.completionTokens
                    + " first=" + r.firstTokenMs);
        }
    }

    /** 记一条工具事件；signature 只落 hash，不落原文。 */
    public static synchronized void tool(String name, String signature, String decision,
                                         long ms, String code) {
        if (current == null) return;
        Tool t = new Tool();
        t.name = name == null ? "" : name;
        t.sig = hash(signature);
        t.decision = decision == null ? "" : decision;
        t.ms = Math.max(0, ms);
        t.code = code == null ? "" : code;
        current.tools.add(t);
        if (sink != null) {
            sink.log("tool " + t.name + " sig=" + t.sig + " " + t.decision
                    + " ms=" + t.ms + " code=" + t.code);
        }
    }

    /**
     * 记一次「没走 tick、但真花了 token」的请求（触顶后补发的全量请求）。
     *
     * <p>护栏统计口径不动——补记会二次触发停机判定，行为就变了。
     * 这里只把数字挂到 run 上并标 uncounted，让诊断包不骗人。
     */
    public static synchronized void uncounted(int promptTokens) {
        if (current == null || promptTokens <= 0) return;
        current.uncountedTokens += promptTokens;
        if (sink != null) {
            sink.log("run_uncounted " + current.id + " promptTokens=" + promptTokens
                    + "（未计入护栏统计）");
        }
    }

    /** 兼容旧调用：只带状态，停止原因留空。 */
    public static synchronized void finish(String status) {
        finish(status, "", 0);
    }

    /**
     * 收尾一次 run。
     *
     * <p>stopReason 一定要带上：status=stopped 分不出是「轮次用完」还是「token 触顶」，
     * 两种情况的处置完全不同（前者该加轮次，后者该砍上下文）。
     * maxTokens 一起落库，便于事后核对阈值本身有没有被改。
     */
    public static synchronized void finish(String status, String stopReason, int maxTokens) {
        if (current == null) return;
        current.status = status == null || status.isEmpty() ? "ok" : status;
        current.stopReason = stopReason == null ? "" : stopReason;
        current.maxTokens = maxTokens;
        current.finishedAt = System.currentTimeMillis();
        runs.add(current);
        while (runs.size() > MAX_RUNS) runs.remove(0);
        if (sink != null) {
            sink.log("run_finish " + current.id + " status=" + current.status
                    + " stopReason=" + (current.stopReason.isEmpty() ? "-" : current.stopReason)
                    + " maxTokens=" + current.maxTokens
                    + " rounds=" + current.rounds.size() + " tools=" + current.tools.size());
        }
        current = null;
    }

    /** 诊断包：{runs:[...], metrics:{...}}。只含脱敏字段。 */
    public static synchronized String exportJson() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        int completed = 0, stopped = 0, tools = 0, toolsOk = 0, rounds = 0;
        long tokens = 0, firstMs = 0;
        int firstN = 0;
        for (Run r : runs) {
            if ("ok".equals(r.status)) completed++;
            if ("stopped".equals(r.status) || "budget".equals(r.status)) stopped++;
            rounds += r.rounds.size();
            for (Round x : r.rounds) {
                tokens += x.promptTokens + x.completionTokens;
                if (x.firstTokenMs > 0) { firstMs += x.firstTokenMs; firstN++; }
            }
            for (Tool t : r.tools) {
                /* 成功率只看真正执行过的调用；preview/deny 不算失败 */
                if ("allow".equals(t.decision) || "error".equals(t.decision)) {
                    tools++;
                    if ("OK".equals(t.code)) toolsOk++;
                }
            }
            arr.put(r.toJson());
        }
        JSONObject m = new JSONObject();
        try {
            m.put("runs", runs.size());
            m.put("completed", completed);
            m.put("stopped", stopped);
            m.put("avgRounds", runs.isEmpty() ? 0 : Math.round(rounds * 100.0 / runs.size()) / 100.0);
            m.put("toolSuccessRate", tools == 0 ? 0 : Math.round(toolsOk * 1000.0 / tools) / 1000.0);
            m.put("avgTokens", runs.isEmpty() ? 0 : Math.round(tokens * 1.0 / runs.size()));
            m.put("avgFirstTokenMs", firstN == 0 ? 0 : Math.round(firstMs * 1.0 / firstN));
            m.put("maxRuns", MAX_RUNS);
            root.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
            root.put("metrics", m);
            root.put("runs", arr);
        } catch (JSONException ignored) { }
        return root.toString();
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format(Locale.US, "%02x", d[i] & 0xff));
            return b.toString();
        } catch (Exception e) {
            return "000000000000";
        }
    }

    private static final class Run {
        final String id;
        final long startedAt = System.currentTimeMillis();
        long finishedAt;
        String status = "running";
        String stopReason = "";
        int maxTokens;
        /** 触顶后补发的全量请求：确实发了、确实花了钱，但没走 tick，不计入护栏统计。 */
        int uncountedTokens;
        final List<Round> rounds = new ArrayList<Round>();
        final List<Tool> tools = new ArrayList<Tool>();

        Run(String id) { this.id = id; }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("traceId", id);
                o.put("startedAt", startedAt);
                o.put("finishedAt", finishedAt);
                o.put("status", status);
                o.put("stopReason", stopReason);
                o.put("maxTokens", maxTokens);
                if (uncountedTokens > 0) {
                    o.put("uncounted", true);
                    o.put("uncountedTokens", uncountedTokens);
                }
                JSONArray rs = new JSONArray();
                for (Round r : rounds) rs.put(r.toJson());
                o.put("rounds", rs);
                JSONArray ts = new JSONArray();
                for (Tool t : tools) ts.put(t.toJson());
                o.put("toolCalls", ts);
            } catch (JSONException ignored) { }
            return o;
        }
    }

    private static final class Round {
        int n, promptTokens, completionTokens, firstTokenMs;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("n", n);
                o.put("promptTokens", promptTokens);
                o.put("completionTokens", completionTokens);
                o.put("firstTokenMs", firstTokenMs);
            } catch (JSONException ignored) { }
            return o;
        }
    }

    private static final class Tool {
        String name, sig, decision, code;
        long ms;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("sig", sig);
                o.put("decision", decision);
                o.put("ms", ms);
                o.put("code", code);
            } catch (JSONException ignored) { }
            return o;
        }
    }
}
