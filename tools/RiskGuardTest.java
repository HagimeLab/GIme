package com.fan.shuatiku;

/**
 * RiskGuard 的桌面回归：只验证「这个错误算不算上游在说别问了」。
 *
 * <p>答案缓存已经删掉；这里钉住的是：该停手的错误必须识别出来，偶发/网络/登录
 * 类错误不能被误判成风险。
 */
public class RiskGuardTest {

    private static int pass, fail;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fail++;
        System.out.println("  [FAIL] " + name + "  " + detail);
    }

    public static void main(String[] args) {
        System.out.println("=== RiskGuard（风险判定）===");

        ok("40300 算风险", RiskGuard.isRisk(40300, ""), "");
        ok("429 算风险", RiskGuard.isRisk(429, ""), "");
        ok("「频繁」算风险", RiskGuard.isRisk(0, "请求过于频繁，请稍后再试"), "");
        ok("too many 算风险", RiskGuard.isRisk(0, "Too many requests"), "");
        ok("rate limit 算风险", RiskGuard.isRisk(0, "rate limit exceeded"), "");
        ok("「配额」算风险", RiskGuard.isRisk(0, "今日配额已用完"), "");
        ok("40301 不算风险（一次性挑战，偶发）",
           !RiskGuard.isRisk(40301, "INVALID_POW_RESPONSE"), "");
        ok("40003 不算风险（要重新登录）",
           !RiskGuard.isRisk(40003, "INVALID_TOKEN"), "");
        ok("普通网络错误不算风险", !RiskGuard.isRisk(-1, "connect failed"), "");
        ok("空消息不算风险", !RiskGuard.isRisk(0, null), "");
        ok("拦截页算风险", RiskGuard.isRisk(RiskGuard.BLOCKED_CODE, ""), "");
        ok("「请完成安全验证」算风险", RiskGuard.isRisk(0, "请完成安全验证后重试"), "");
        ok("「人机校验」算风险", RiskGuard.isRisk(0, "触发人机校验"), "");
        ok("captcha 算风险", RiskGuard.isRisk(0, "Captcha required"), "");
        ok("异常流量算风险", RiskGuard.isRisk(0, "检测到异常流量"), "");
        ok("access denied 算风险", RiskGuard.isRisk(0, "Access Denied"), "");

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
