package com.fan.shuatiku;

/**
 * 上游风险判定：这个错误算不算「上游在说别问了」。
 *
 * <p>算的：{@code 40300}、HTTP 429、拦截页（HTML / 人机校验），以及响应里带
 * 「频繁 / 配额 / 人机 / captcha / 异常流量」等字样。算风险只影响「这一发重不重试」：
 * 不自动停用通道，也不记时长罚。
 *
 * <p>不算的：PoW 挑战一次性（偶发，重取一次挑战就好）、网络类错误、登录失效。
 *
 * <p>这个类刻意不依赖 Android，桌面单测能直接验证判定规则。
 */
public final class RiskGuard {

    /** 与 {@code DeepSeekWeb.CODE_BLOCKED} 同值：拦截页（HTML / 人机校验）。 */
    static final int BLOCKED_CODE = -5;

    private RiskGuard() {}

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
        // 人机校验 / WAF 拦截：各种措辞都收进来，别硬撞验证码
        if (m.contains("人机") || m.contains("安全验证") || m.contains("完成验证")
                || m.contains("captcha") || m.contains("异常流量")
                || m.contains("拒绝访问") || m.contains("blocked")) return true;
        // 英文的 WAF 页常见措辞
        return m.contains("access denied") || m.contains("forbidden") || m.contains("try again later");
    }
}
