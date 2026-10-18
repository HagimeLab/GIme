package com.fan.shuatiku;

import java.util.List;

/**
 * 下载源列表的回归测试。
 *
 * 钉住两件事：一是多个 CDN 入口都在（少一个就少一条活路），
 * 二是路径必须稳定（每次发版都换路径的话，老版本里写死的地址会 404）。
 */
public class DownloadSourcesTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        List<String> urls = DownloadSources.forVersion("HagimeLab/GIme", "1.0.2");

        checkBool("至少给出 4 个下载源", urls.size() >= 4);
        checkBool("首选是 jsDelivr", urls.get(0).startsWith("https://cdn.jsdelivr.net/gh/"));
        checkBool("包含 fastly 备用入口", hasPrefix(urls, "https://fastly.jsdelivr.net/gh/"));
        checkBool("包含 testingcf 备用入口", hasPrefix(urls, "https://testingcf.jsdelivr.net/gh/"));
        checkBool("保留 GitHub 原始地址作兜底", hasPrefix(urls, "https://raw.githubusercontent.com/"));

        // 路径稳定：不论版本号是多少，仓库内路径都必须是 release/GIme-latest.apk
        boolean allFixed = true;
        for (String u : urls) {
            if (!u.endsWith("/release/GIme-v1.0.2.apk")) { allFixed = false; break; }
        }
        checkBool("所有源都指向带版本号的路径", allFixed);

        List<String> other = DownloadSources.forVersion("HagimeLab/GIme", "9.9.9");
        checkBool("换版本号会改变下载路径（避开 CDN 强缓存）", !other.get(0).equals(urls.get(0)));

        checkBool("CDN 引用固定为 main（缓存靠版本化路径规避）", urls.get(0).contains("@main/"));

        checkStr("发布页按版本号生成",
                 DownloadSources.releasePage("HagimeLab/GIme", "1.0.2"),
                 "https://github.com/HagimeLab/GIme/releases/tag/v1.0.2");

        /* 内容指纹进文件名：同一个版本号重新构建时，路径必须跟着变，
           否则 jsDelivr 的 7 天强缓存会一直下发旧包。 */
        checkStr("文件名带内容指纹", DownloadSources.apkName("1.0.2", "a4b73111"),
                 "GIme-v1.0.2-a4b73111.apk");
        checkStr("没给指纹时退回纯版本名", DownloadSources.apkName("1.0.2", ""),
                 "GIme-v1.0.2.apk");
        checkStr("指纹为 null 也不炸", DownloadSources.apkName("1.0.2", null),
                 "GIme-v1.0.2.apk");
        checkBool("不同指纹给出不同地址",
                  !DownloadSources.forFile("r/x", "main", DownloadSources.apkName("1.0.2", "aaaaaaaa"))
                   .get(0).equals(
                   DownloadSources.forFile("r/x", "main", DownloadSources.apkName("1.0.2", "bbbbbbbb"))
                   .get(0)));

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    private static boolean hasPrefix(List<String> urls, String prefix) {
        for (String u : urls) if (u.startsWith(prefix)) return true;
        return false;
    }

    private static void checkBool(String name, boolean actual) {
        if (actual) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    private static void checkStr(String name, String actual, String expect) {
        if (expect.equals(actual)) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）"); }
    }
}




