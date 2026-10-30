package com.fan.shuatiku;

import java.util.Locale;

/**
 * JS 桥的入参闸门。
 *
 * <p>桥方法（{@code Android.xxx}）对页面完全开放：页面里任何一段脚本都能拿任意参数
 * 调它们。页面自己的代码是可信的，但「可信」不等于「不会出错」——一次拼接失误、
 * 一段模型生成的链接、一个被污染的历史消息，都可能把 {@code javascript:} 之类的东西
 * 送到 {@link android.content.Intent} 或下载器里。P0 只做低风险项：
 * 协议白名单、长度上限、下载源白名单，以及带 id 的回调必须配对。
 *
 * <p>刻意不依赖 Android：这些规则要能在桌面 javac 上直接跑单测（见 {@code tools/BridgeTest.java}），
 * 不必起模拟器就能验证「非法入参一定被拒」。origin 校验、按页面分组注入等彻底改造留到 P3。
 *
 * <p>每个方法都返回「人话原因」，通过返回 {@code null}；调用方拿原因打一行日志即可，
 * 不用在桥方法里重复拼字符串。
 */
public final class BridgeGuard {

    /** URL 统一长度上限。正常发布页、接口地址都远小于这个数。 */
    static final int MAX_URL = 2048;

    /** 回调 id 的长度上限。页面生成的是 {@code c12} / {@code mcp3} 这种，很短。 */
    static final int MAX_ID = 64;

    /** 请求体上限。附件长文按 18000 字切段上行，加上系统提示与历史也远用不到 512KB。 */
    static final int MAX_BODY = 512 * 1024;

    /** MCP 回包上限。工具返回整库题目时可能很大，留 4MB。 */
    static final int MAX_REPLY = 4 * 1024 * 1024;

    /**
     * 允许下载安装包的主机。
     *
     * <p>与 {@link DownloadSources#forFile} 生成的六个入口一一对应。下载器会把
     * 拿到的东西直接交给系统安装页，所以这里必须白名单而不是只判协议。
     */
    private static final String[] DOWNLOAD_HOSTS = {
            "cdn.jsdelivr.net",
            "fastly.jsdelivr.net",
            "testingcf.jsdelivr.net",
            "gcore.jsdelivr.net",
            "github.com",
            "raw.githubusercontent.com",
    };

    private BridgeGuard() {
    }

    /** 回调 id：只允许字母数字和 {@code _ -}，长度受限。 */
    static boolean okId(String id) {
        if (id == null) return false;
        int n = id.length();
        if (n == 0 || n > MAX_ID) return false;
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** 交给系统浏览器打开的地址：只允许 http(s)。 */
    static String navUrl(String url) {
        return webUrl(url, true);
    }

    /**
     * 指纹体检地址：同样只允许 http(s)，但允许空串（空表示用内置默认值）。
     */
    static String probeUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        return webUrl(url, true);
    }

    /** 大肥鱼请求地址：内置通道的 {@code builtin://deepseek} 或 http(s)。 */
    static String chatUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "空地址";
        String u = url.trim();
        if (u.length() > MAX_URL) return "地址太长";
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("builtin://deepseek")) return null;
        return webUrl(u, true);
    }

    /**
     * 更新包下载地址：必须 https、必须落在发布源白名单里、必须是 release 目录下的 apk。
     *
     * <p>这条比 {@link #navUrl} 严得多——它进的是系统下载器，下完直接拉安装页。
     */
    static String downloadUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "空地址";
        String u = url.trim();
        if (u.length() > MAX_URL) return "地址太长";
        String lower = u.toLowerCase(Locale.ROOT);
        /* 下载器不能接受任意协议；明文 http 还允许中间人换包，一律只认 https */
        if (!lower.startsWith("https://")) return "下载地址必须是 https";

        int hostStart = "https://".length();
        int pathStart = u.indexOf('/', hostStart);
        if (pathStart < 0) return "下载地址缺少仓库路径";
        String host = lower.substring(hostStart, pathStart);
        int at = host.indexOf('@');
        if (at >= 0) return "下载地址不允许带用户信息";
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (!inDownloadHosts(host)) return "不在允许的下载源里：" + host;

        String path = u.substring(pathStart);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        if (path.indexOf("/HagimeLab/GIme") < 0) return "路径不在本仓库里";
        if (path.indexOf("/release/") < 0) return "路径不在 release 目录下";
        if (!path.toLowerCase(Locale.ROOT).endsWith(".apk")) return "不是 apk 文件";
        return null;
    }

    /** 请求体长度上限。 */
    static String body(String s) {
        if (s == null) return "空请求体";
        if (s.length() > MAX_BODY) return "请求体太长（" + s.length() + " 字符）";
        return null;
    }

    /** MCP 回包长度上限；{@code null} 视为空串，与原有语义一致。 */
    static String reply(String json) {
        if (json == null) return null;
        if (json.length() > MAX_REPLY) return "回包太长（" + json.length() + " 字符）";
        return null;
    }

    /**
     * http(s) 地址的公共判定。
     *
     * <p>只判协议不看主机：浏览器的最终防护在系统那边，而 P0 的目标是
     * 「别把 {@code javascript:} / {@code file:} / {@code intent:} 送进 Intent」。
     */
    private static String webUrl(String url, boolean allowHttp) {
        if (url == null || url.trim().isEmpty()) return "空地址";
        String u = url.trim();
        if (u.length() > MAX_URL) return "地址太长";
        String lower = u.toLowerCase(Locale.ROOT);
        int scheme;
        if (lower.startsWith("https://")) scheme = "https://".length();
        else if (allowHttp && lower.startsWith("http://")) scheme = "http://".length();
        else return "只允许 http(s) 地址";
        /* 光有协议头没有主机（https:// 后面直接是斜杠或结束）也是废地址，
           送进 Intent 只会抛 ActivityNotFound，不如在这里就拒掉。 */
        int hostEnd = u.length();
        for (int i = scheme; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '/' || c == '?' || c == '#') { hostEnd = i; break; }
        }
        String host = u.substring(scheme, hostEnd);
        if (host.isEmpty()) return "地址里没有主机";
        if (host.indexOf('@') >= 0) return "地址不允许带用户信息";
        return null;
    }

    private static boolean inDownloadHosts(String host) {
        for (int i = 0; i < DOWNLOAD_HOSTS.length; i++) {
            if (DOWNLOAD_HOSTS[i].equals(host)) return true;
        }
        return false;
    }
}
