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
 *   <li><b>熔断</b>：上游连续表达不满（缺头/限流）就停手，别拿重试硬扛——
 *      而且惩罚是<b>递增</b>的：同一天里被警告第二次就停 20 分钟，第三次 40 分钟，
 *      最多 2 小时；整整一天不再被警告才把惩罚降回 10 分钟。被提醒之后仍按同一节奏
 *      硬撞的流量，比「明显收敛」的账号更像脚本。</li>
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

    /** 第一次熔断的时长：10 分钟。 */
    static final long COOLDOWN_MS = 10 * 60 * 1000L;

    /** 递增惩罚的上限：2 小时。再久就是在把一个普通用户永久关在门外了。 */
    static final long MAX_COOLDOWN_MS = 2 * 60 * 60 * 1000L;

    /** 多久没再被警告就把「第几次被警告」的账清掉：一天。 */
    static final long STRIKE_FORGET_MS = 24 * 60 * 60 * 1000L;

    /** 每天最多问多少次。正常刷题够用；规模调用会被挡在门外。 */
    static final int DAILY_LIMIT = 60;

    /** 答案缓存条数（按最近使用淘汰）。 */
    static final int CACHE_MAX = 60;

    /**
     * 单条缓存值的字符上限。
     *
     * <p>不设上限的话，一条超长解析就能把其它几十条挤掉——缓存是「省一次请求」，
     * 不该被一条大答案拖垮。
     */
    static final int CACHE_MAX_CHARS = 8000;

    /** 与 {@code DeepSeekWeb.CODE_BLOCKED} 同值：拦截页（HTML / 人机校验）。 */
    static final int BLOCKED_CODE = -5;

    private long frozenUntil = 0;
    private int consecutive = 0;
    /** 今天已经被警告过几次（递增惩罚的依据）。 */
    private int strikes = 0;
    /** 最近一次被警告的时刻，用来判断「安静了一整天没有」。 */
    private long lastRiskAt = 0;
    private String day = "";
    private int used = 0;
    /** 累计命中次数：让「缓存到底有没有用」可被观察，而不是靠感觉。 */
    private long hits = 0;

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

    /**
     * 成功一次（带时间）：除了清零连续计数，还要在「安静够久」之后把递增惩罚降级。
     *
     * @return true 表示这次把 strikes 清掉了（调用方需要落盘）
     */
    synchronized boolean onSuccess(long now) {
        consecutive = 0;
        if (strikes > 0 && lastRiskAt > 0 && now - lastRiskAt > STRIKE_FORGET_MS) {
            strikes = 0;
            lastRiskAt = 0;
            return true;
        }
        return false;
    }

    /** 上游表达了一次不满；攒够 {@link #MAX_CONSECUTIVE} 次就熔断。 */
    synchronized void onRisk(long now) {
        consecutive++;
        if (consecutive < MAX_CONSECUTIVE) return;
        consecutive = 0;
        strikes++;
        frozenUntil = now + cooldownFor(strikes);
        lastRiskAt = now;
    }

    /**
     * 第 n 次被警告停多久：10 分钟、20、40、80…，上限 {@link #MAX_COOLDOWN_MS}。
     *
     * <p>用位移而不是乘法表，是为了让「第几次」和「停多久」的关系一眼可见。
     */
    static long cooldownFor(int strikes) {
        if (strikes <= 1) return COOLDOWN_MS;
        int shift = Math.min(strikes - 1, 8);
        long ms = COOLDOWN_MS << shift;
        return Math.min(ms, MAX_COOLDOWN_MS);
    }

    synchronized int consecutive() { return consecutive; }

    synchronized int strikes() { return strikes; }

    synchronized long frozenUntil() { return frozenUntil; }

    synchronized long lastRiskAt() { return lastRiskAt; }

    /**
     * 用户手动「知道了，再试一次」时用。
     *
     * <p>只解冻，<b>不清 strikes</b>：手动放行是用户的权利，但「今天已经被警告过几次」
     * 是上游的账，重复解除不该让下一次惩罚重新从 10 分钟起步。
     */
    synchronized void resetFreeze() { frozenUntil = 0; consecutive = 0; }

    /** 启动时从磁盘恢复熔断状态：否则重启一次就把惩罚清空了。 */
    synchronized void restoreFreeze(long savedUntil, int savedStrikes, long savedRiskAt) {
        frozenUntil = Math.max(0, savedUntil);
        strikes = Math.max(0, savedStrikes);
        lastRiskAt = Math.max(0, savedRiskAt);
        consecutive = 0;
    }

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

    /** 命中就记一笔：这个数字是「缓存有没有用」的唯一证据。 */
    synchronized String cacheGet(String key) {
        if (key == null) return null;
        String v = cache.get(key);
        if (v != null) hits++;
        return v;
    }

    synchronized void cachePut(String key, String value) {
        if (key == null || value == null || key.isEmpty()) return;
        if (value.length() > CACHE_MAX_CHARS) return;
        cache.put(key, value);
    }

    synchronized void cacheClear() { cache.clear(); }

    synchronized int cacheSize() { return cache.size(); }

    synchronized long hitCount() { return hits; }

    /** 启动时把「累计复用次数」接上，别让每一次重启都从 0 开始。 */
    synchronized void restoreHits(long saved) { hits = Math.max(0, saved); }

    /**
     * 缓存快照：按最近使用顺序（最旧的在前）拷一份出来，调用方拿去落盘。
     *
     * <p>答案缓存原先只在内存里，App 一重启就清零——同一道题第二天再问又得打一次上游。
     * 落盘之后重复提问才真的不产生请求，这对「少撞风控」和「少花 token」是同一件事。
     */
    synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<String, String>(cache);
    }

    /** 从快照恢复：按顺序放回，超出 {@link #CACHE_MAX} 的由淘汰规则丢掉最旧的。 */
    synchronized void restoreCache(Map<String, String> saved) {
        if (saved == null || saved.isEmpty()) return;
        for (Map.Entry<String, String> e : saved.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getKey().isEmpty()) continue;
            if (e.getValue().length() > CACHE_MAX_CHARS) continue;
            cache.put(e.getKey(), e.getValue());
        }
    }

    // ── 判定 ────────────────────────────────────────────────────────────

    /**
     * 这个错误算不算「上游在说别问了」。
     *
     * <p>算的：{@code 40300}（缺 PoW 头，通常意味着这一路被卡了）、HTTP 429、
     * 拦截页（上游拿不到 JSON、回了 HTML / 人机校验，见 {@link #BLOCKED_CODE}），
     * 以及响应里带「频繁 / 太多 / rate / limit / 验证」字样。
     * 不算的：PoW 挑战一次性（偶发，重取一次挑战就好）、网络类错误、
     * 登录失效（那是要重新登录，跟频率无关）。
     */
    static boolean isRisk(int code, String msg) {
        if (code == 40300 || code == 429) return true;
        // 拦截页：上游没给 JSON，给了个 HTML（WAF / 人机校验）。这是最明确的「别问了」。
        if (code == BLOCKED_CODE) return true;
        if (msg == null) return false;
        String m = msg.toLowerCase();
        if (m.contains("频繁") || m.contains("too many") || m.contains("rate limit")) return true;
        // 中文里也说「请求过多」「配额」
        if (m.contains("请求过多") || m.contains("配额") || m.contains("quota")) return true;
        // 人机校验 / WAF 拦截：各种措辞都收进来，宁可多停十分钟，也别硬撞验证码
        if (m.contains("人机") || m.contains("安全验证") || m.contains("完成验证")
                || m.contains("captcha") || m.contains("异常流量")
                || m.contains("拒绝访问") || m.contains("blocked")) return true;
        // 英文的 WAF 页常见措辞
        return m.contains("access denied") || m.contains("forbidden") || m.contains("try again later");
    }

    /**
     * 缓存键：**只由「哪道题 + 问的什么 + 这道题被答成什么样」决定**，不含历史。
     *
     * <p>为什么不再用整段 prompt：拍平后的 prompt 里塞着最近几条对话，多聊一句
     * 键就全变了——同一道题重复问也几乎命中不了，缓存形同虚设。
     *
     * <p>现在只拼三样东西：
     * <ol>
     *   <li>题目指纹：题干 + 选项 + 答案（同一道题每次都一样）；</li>
     *   <li>本轮提问：最后一条 user 消息（归一后）；</li>
     *   <li>作答状态：选了哪几个选项、是否已判分、什么模式。</li>
     * </ol>
     *
     * <p>不把历史算进去是对的：同一道题问「这题怎么解」，答案就该一样；
     * 若用户带上了前文语境（「那 B 为什么不对」），问题的措辞本身不同，键也不同。
     *
     * <p>作答状态要进键的理由：同一句话在「你选了 B」和「你选了 C」下，
     * 大肥鱼第一句话就该不一样；判过卷再问，它还会点评你的选择。这三样不进键，
     * 就会出现「换了个选项问，还是拿到上一个选项的答案」——那是缓存把用户坑了。
     */
    static String cacheKey(String questionFingerprint, String ask, String answerState) {
        String q = questionFingerprint == null ? "" : questionFingerprint;
        String a = normalizeAsk(ask);
        if (q.isEmpty() && a.isEmpty()) return "";
        String s = answerState == null ? "" : answerState.trim();
        // "v2" 是键域版本：换了键的构成方式就换域，旧条目自然不再命中（也就不会串味）
        return sha16("v2\u0001" + q + "\u0001" + a + "\u0001" + s);
    }

    /** 不带作答状态的签名：只想按「题目 + 提问」建键时用。 */
    static String cacheKey(String questionFingerprint, String ask) {
        return cacheKey(questionFingerprint, ask, "");
    }

    /**
     * 提问归一：让「同一句话的不同打写方式」落到同一个键上。
     *
     * <p>依次做：去空白 → 去零宽字符 → 全角字母数字折成半角 → 折大小写 →
     * 去首尾标点 → 去句尾语气词 → 再去一次首尾标点。
     *
     * <p>为什么连「呢/呀/吧」也去：刷题时用户问的是同一件事，多打一个语气词
     * 不该导致再打一次上游。这不会把「为什么选 B」和「为什么不选 B」混同——
     * 那是实词差异，不在归一范围内。
     */
    static String normalizeAsk(String ask) {
        if (ask == null) return "";
        StringBuilder b = new StringBuilder(ask.length());
        for (int i = 0; i < ask.length(); i++) {
            char c = ask.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') continue;
            b.append(Character.toLowerCase(foldFullWidth(c)));
        }
        return trimPunct(stripTailParticles(trimPunct(b.toString())));
    }

    /** 全角字母/数字折成半角：中文输入法下「Ａ」和「A」是同一个意思。 */
    private static char foldFullWidth(char c) {
        if (c >= 'Ａ' && c <= 'Ｚ') return (char) ('a' + (c - 'Ａ'));
        if (c >= 'ａ' && c <= 'ｚ') return (char) ('a' + (c - 'ａ'));
        if (c >= '０' && c <= '９') return (char) ('0' + (c - '０'));
        return c;
    }

    /** 去掉首尾的常见标点（中文问号/感叹号/句号/英文对应）。 */
    private static String trimPunct(String s) {
        int from = 0, to = s.length();
        while (from < to && "?？!！.。~～、,".indexOf(s.charAt(from)) >= 0) from++;
        while (to > from && "?？!！.。~～、,".indexOf(s.charAt(to - 1)) >= 0) to--;
        return s.substring(from, to);
    }

    /** 去掉句尾语气词（可连着几个：「这题怎么解呢呀」也是同一问）。 */
    private static String stripTailParticles(String s) {
        int to = s.length();
        while (to > 0 && "呢呀吧啊嘛哦咯啦呐".indexOf(s.charAt(to - 1)) >= 0) to--;
        return s.substring(0, to);
    }

    /** SHA-256 前 16 位十六进制。 */
    private static String sha16(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                int v = d[i] & 0xff;
                if (v < 16) b.append('0');
                b.append(Integer.toHexString(v));
            }
            return b.toString();
        } catch (Exception e) {
            // 罕见（算法缺失）：退回字符串哈希，宁可偶尔碰撞也不要崩
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 旧签名保留：整段 prompt 的哈希（单测与向后兼容用）。 */
    static String cacheKey(String prompt) {
        return sha16(prompt);
    }
}