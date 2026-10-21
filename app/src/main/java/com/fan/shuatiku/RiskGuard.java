package com.fan.shuatiku;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用护栏：熔断 + 每日预算 + 答案缓存。
 *
 * <p>定位很明确：<b>让正常问答的流量保持在人类水平</b>，而不是把自动化藏起来。
 * 三件事都属于「遵守上游信号」：
 * <ul>
 *   <li><b>熔断</b>：上游连续表达不满（缺头/限流）就停手，别拿重试硬扛——</li>
 *   <li><b>每日预算</b>：一天问多少题是有限度的，超了就明确告诉用户，而不是偷偷打；</li>
 *   <li><b>答案缓存</b>：同一道题、同一句话再问一遍，直接复用上次答案，
 *       压根不发请求。刷题场景里重复提问很常见，这是最诚实也最有效的一招。</li>
 * </ul>
 *
 * <p>这个类刻意不依赖 Android（时间由调用方传入、持久化由调用方负责），
 * 这样桌面单测能直接验证熔断计时、跨天清零、缓存淘汰这些逻辑。
 */
public final class RiskGuard {

    /** 连续几次「上游不满」就熔断。2 次足够保守，又不会因一次抖动就停摆。 */
    static final int MAX_CONSECUTIVE = 2;

    /** 熔断时长：10 分钟。 */
    static final long COOLDOWN_MS = 10 * 60 * 1000L;

    /** 每天最多问多少次。正常刷题够用；规模调用会被挡在门外。 */
    static final int DAILY_LIMIT = 60;

    /** 答案缓存条数（按最近使用淘汰）。 */
    static final int CACHE_MAX = 60;

    private long frozenUntil = 0;
    private int consecutive = 0;
    private String day = "";
    private int used = 0;

    /** accessOrder=true：按访问排序，最久没用的先被淘汰。 */
    private final LinkedHashMap<String, String> cache =
            new LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    // ── 熔断 ────────────────────────────────────────────────────────────

    synchronized boolean isFrozen(long now) { return now < frozenUntil; }

    synchronized long frozenRemainingMs(long now) { return Math.max(0, frozenUntil - now); }

    /** 成功一次就清零连续计数：上游恢复了就不该继续惩罚用户。 */
    synchronized void onSuccess() { consecutive = 0; }

    /** 上游表达了一次不满；攒够 {@link #MAX_CONSECUTIVE} 次就熔断。 */
    synchronized void onRisk(long now) {
        consecutive++;
        if (consecutive >= MAX_CONSECUTIVE) {
            frozenUntil = now + COOLDOWN_MS;
            consecutive = 0;
        }
    }

    synchronized int consecutive() { return consecutive; }

    /** 用户手动「知道了，再试一次」时用。 */
    synchronized void resetFreeze() { frozenUntil = 0; consecutive = 0; }

    // ── 每日预算 ────────────────────────────────────────────────────────

    /** 换天就清零。返回今天已用次数。 */
    synchronized int touchDay(String today) {
        if (today != null && !today.equals(day)) { day = today; used = 0; }
        return used;
    }

    synchronized boolean budgetLeft(String today) {
        touchDay(today);
        return used < DAILY_LIMIT;
    }

    synchronized int remaining(String today) {
        touchDay(today);
        return Math.max(0, DAILY_LIMIT - used);
    }

    synchronized void onUsed(String today) {
        touchDay(today);
        used++;
    }

    /** 启动时从磁盘恢复（day 为空表示还没记过）。 */
    synchronized void restore(String savedDay, int savedUsed) {
        if (savedDay == null || savedDay.isEmpty()) return;
        day = savedDay;
        used = Math.max(0, savedUsed);
    }

    synchronized String day() { return day; }

    synchronized int used() { return used; }

    // ── 答案缓存 ────────────────────────────────────────────────────────

    synchronized String cacheGet(String key) {
        return key == null ? null : cache.get(key);
    }

    synchronized void cachePut(String key, String value) {
        if (key != null && value != null && !key.isEmpty()) cache.put(key, value);
    }

    synchronized void cacheClear() { cache.clear(); }

    synchronized int cacheSize() { return cache.size(); }

    // ── 判定 ────────────────────────────────────────────────────────────

    /**
     * 这个错误算不算「上游在说别问了」。
     *
     * <p>算的：{@code 40300}（缺 PoW 头，通常意味着这一路被卡了）、HTTP 429、
     * 以及响应里带「频繁 / 太多 / rate / limit」字样。
     * 不算的：{@code 40301}（挑战一次性，偶发可重试）、网络类错误、
     * 登录失效（那是要重新登录，跟频率无关）。
     */
    static boolean isRisk(int code, String msg) {
        if (code == 40300 || code == 429) return true;
        if (msg == null) return false;
        String m = msg.toLowerCase();
        if (m.contains("频繁") || m.contains("too many") || m.contains("rate limit")) return true;
        // 中文里也说「请求过多」「配额」
        return m.contains("请求过多") || m.contains("配额") || m.contains("quota");
    }

    /**
     * 缓存键：整段 prompt 的 SHA-256 前 16 位。
     *
     * <p>用完整 prompt 而不是「用户这句话」当键，是因为 prompt 里带着当前题目上下文——
     * 同一句话在另一道题下面应该算是不同的问题，不能命中同一份答案。
     */
    static String cacheKey(String prompt) {
        if (prompt == null || prompt.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(prompt.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                int v = d[i] & 0xff;
                if (v < 16) b.append('0');
                b.append(Integer.toHexString(v));
            }
            return b.toString();
        } catch (Exception e) {
            // 罕见（算法缺失）：退回字符串哈希，宁可偶尔碰撞也不要崩
            return Integer.toHexString(prompt.hashCode());
        }
    }
}
