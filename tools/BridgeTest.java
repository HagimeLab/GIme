package com.fan.shuatiku;

/**
 * JS 桥入参闸门的桌面回归。
 *
 * <p>桥方法对页面完全开放，P0 的三条硬规则都钉在这里：
 * 交给系统的地址只认 http(s)、下载包只认发布源白名单、带 id 的回调必须像自己发出去的。
 * 直接调产品代码里的 {@link BridgeGuard}，不在测试里另抄一份判断——
 * 「测试和实现一起写错」是这类安全断言最常见的失效方式。
 *
 * <p>不碰网络、不依赖 Android，{@code tools\check.cmd} 里单独编一遍就能跑。
 */
public class BridgeTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        /* ---------- 1. openUrl：只许 http(s) ---------- */
        allow("https 发布页放行", BridgeGuard.navUrl("https://github.com/HagimeLab/GIme/releases/tag/v1.1.2"));
        allow("http 也放行（老站点还在用）", BridgeGuard.navUrl("http://example.com/a"));
        reject("javascript: 伪协议必须拒", BridgeGuard.navUrl("javascript:alert(1)"));
        reject("JavaScript 大写变体也拒", BridgeGuard.navUrl("JaVaScRiPt:alert(1)"));
        reject("file:// 必须拒", BridgeGuard.navUrl("file:///android_asset/index.html"));
        reject("intent: 必须拒", BridgeGuard.navUrl("intent://scan/#Intent;scheme=zxing;end"));
        reject("data: 必须拒", BridgeGuard.navUrl("data:text/html,<script>alert(1)</script>"));
        reject("空地址必须拒", BridgeGuard.navUrl(""));
        reject("null 必须拒", BridgeGuard.navUrl(null));
        reject("超长地址必须拒", BridgeGuard.navUrl("https://a.com/" + repeat('x', BridgeGuard.MAX_URL)));
        allow("恰好等于上限的地址放行", BridgeGuard.navUrl("https://a.com/" + repeat('x', BridgeGuard.MAX_URL - "https://a.com/".length())));
        allow("前后空白会被接受（URI 侧再 trim）", BridgeGuard.navUrl("  https://a.com/x  "));
        reject("只给协议头没有主机也拒", BridgeGuard.navUrl("https://"));

        /* ---------- 2. fpProbe：同上，但空串表示用内置默认值 ---------- */
        allow("指纹体检允许空串（走默认页）", BridgeGuard.probeUrl(""));
        allow("指纹体检允许 null", BridgeGuard.probeUrl(null));
        allow("指纹体检放行 https", BridgeGuard.probeUrl("https://tls.peet.ws/api/all"));
        reject("指纹体检拒绝 file://", BridgeGuard.probeUrl("file:///etc/passwd"));
        reject("指纹体检拒绝 javascript:", BridgeGuard.probeUrl("javascript:1"));

        /* ---------- 3. chatApi：内置通道 + http(s) ---------- */
        allow("内置通道放行", BridgeGuard.chatUrl("builtin://deepseek"));
        allow("内置通道带路径也放行", BridgeGuard.chatUrl("builtin://deepseek/chat/completions"));
        allow("外部 OpenAI 兼容接口放行", BridgeGuard.chatUrl("https://api.openai.com/v1/chat/completions"));
        allow("本地 mock 的 http 也放行", BridgeGuard.chatUrl("http://127.0.0.1:9701/v1/chat/completions"));
        reject("chatApi 拒绝 file://", BridgeGuard.chatUrl("file:///data/local/tmp/x"));
        reject("chatApi 拒绝空地址", BridgeGuard.chatUrl(""));

        /* ---------- 4. downloadUpdate：https + 发布源白名单 + 本仓库 release/*.apk ---------- */
        final String apk = "release/GIme-v1.1.2-531077a4.apk";
        allow("jsDelivr 主源放行",
                BridgeGuard.downloadUrl("https://cdn.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk));
        allow("jsDelivr fastly 放行",
                BridgeGuard.downloadUrl("https://fastly.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk));
        allow("jsDelivr testingcf 放行",
                BridgeGuard.downloadUrl("https://testingcf.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk));
        allow("jsDelivr gcore 放行",
                BridgeGuard.downloadUrl("https://gcore.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk));
        allow("github raw 兜底放行",
                BridgeGuard.downloadUrl("https://github.com/HagimeLab/GIme/raw/main/" + apk));
        allow("raw.githubusercontent 放行",
                BridgeGuard.downloadUrl("https://raw.githubusercontent.com/HagimeLab/GIme/main/" + apk));
        allow("带查询串也认（CDN 常带版本参数）",
                BridgeGuard.downloadUrl("https://cdn.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk + "?v=2"));

        reject("明文 http 下载必须拒（防中间人换包）",
                BridgeGuard.downloadUrl("http://cdn.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk));
        reject("非白名单主机必须拒",
                BridgeGuard.downloadUrl("https://evil.example.com/HagimeLab/GIme/release/x.apk"));
        reject("白名单主机但路径不在本仓库必须拒",
                BridgeGuard.downloadUrl("https://cdn.jsdelivr.net/gh/Other/Repo@main/release/x.apk"));
        reject("本仓库但不在 release 目录必须拒",
                BridgeGuard.downloadUrl("https://cdn.jsdelivr.net/gh/HagimeLab/GIme@main/docs/x.apk"));
        reject("不是 apk 必须拒",
                BridgeGuard.downloadUrl("https://cdn.jsdelivr.net/gh/HagimeLab/GIme@main/release/latest.json"));
        reject("带用户信息的地址必须拒",
                BridgeGuard.downloadUrl("https://user:pw@cdn.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk));
        reject("主机名后加端口冒充必须拒",
                BridgeGuard.downloadUrl("https://cdn.jsdelivr.net.evil.com/gh/HagimeLab/GIme@main/" + apk));
        reject("下载地址空串必须拒", BridgeGuard.downloadUrl(""));
        reject("下载地址 null 必须拒", BridgeGuard.downloadUrl(null));
        reject("下载地址超长必须拒",
                BridgeGuard.downloadUrl("https://cdn.jsdelivr.net/gh/HagimeLab/GIme@main/" + apk
                        + repeat('x', BridgeGuard.MAX_URL)));

        /* ---------- 5. 回调 id：配对核验 ---------- */
        ok("普通 id 放行", BridgeGuard.okId("c12"));
        ok("mcp 前缀放行", BridgeGuard.okId("mcp3"));
        ok("下划线与减号放行", BridgeGuard.okId("chat_mvv51fux_8"));
        ok("恰好 64 字符放行", BridgeGuard.okId(repeat('a', BridgeGuard.MAX_ID)));
        rejectBool("65 字符必须拒", BridgeGuard.okId(repeat('a', BridgeGuard.MAX_ID + 1)));
        rejectBool("空 id 必须拒", BridgeGuard.okId(""));
        rejectBool("null 必须拒", BridgeGuard.okId(null));
        rejectBool("带引号与括号的注入型 id 必须拒", BridgeGuard.okId("c1\");alert(1);//"));
        rejectBool("带反斜杠的 id 必须拒", BridgeGuard.okId("c1\\z"));
        rejectBool("带空格的 id 必须拒", BridgeGuard.okId("c1 c2"));
        rejectBool("带换行的 id 必须拒", BridgeGuard.okId("c1\nc2"));
        rejectBool("路径型 id 必须拒", BridgeGuard.okId("../../etc/passwd"));

        /* ---------- 6. 长度上限 ---------- */
        allow("正常请求体放行", BridgeGuard.body("{\"model\":\"deepseek-web\"}"));
        allow("恰好 512KB 放行", BridgeGuard.body(repeat('a', BridgeGuard.MAX_BODY)));
        reject("512KB+1 必须拒", BridgeGuard.body(repeat('a', BridgeGuard.MAX_BODY + 1)));
        reject("null 请求体必须拒", BridgeGuard.body(null));
        allow("正常 MCP 回包放行", BridgeGuard.reply("{\"jsonrpc\":\"2.0\"}"));
        allow("MCP 回包 null 视为空串（与原有语义一致）", BridgeGuard.reply(null));
        reject("MCP 回包超 4MB 必须拒", BridgeGuard.reply(repeat('a', BridgeGuard.MAX_REPLY + 1)));

        /* ---------- 7. 与页面真实调用对齐（防止白名单和生成规则脱节） ---------- */
        /* 页面拿到的是 DownloadSources 生成的地址；这里直接生成一遍，
           确认每一源都能过闸——两边任何一边改动都会在这里先红。
           注意不要引 Updater.REPO：那个类依赖 Android，桌面编不过，
           仓库名在这里写死，一旦改名 DownloadSources 侧会先红。 */
        java.util.List<String> real = DownloadSources.forVersion("HagimeLab/GIme", "1.1.2");
        boolean all = true;
        for (String u : real) {
            String bad = BridgeGuard.downloadUrl(u);
            if (bad != null) {
                all = false;
                System.out.println("    [FAIL] 发布源过不了闸：" + u + " → " + bad);
            }
        }
        ok("DownloadSources 生成的 " + real.size() + " 个发布源全部能过闸", all);

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }

    /** 断言「被拒」，并且要把原因也一起看：空白的拒绝和写得清的拒绝不是一回事。 */
    private static void reject(String name, String reason) {
        ok(name + "（原因：" + (reason == null ? "null" : reason) + "）", reason != null && !reason.isEmpty());
    }

    /** 断言「放行」：闸门返回 null 才算过。 */
    private static void allow(String name, String reason) {
        ok(name + "（原因：" + (reason == null ? "null" : reason) + "）", reason == null);
    }

    /** 布尔闸门（如 okId）的「被拒」断言。 */
    private static void rejectBool(String name, boolean pass) {
        ok(name, !pass);
    }
}
