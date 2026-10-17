package com.fan.shuatiku;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载源列表。
 *
 * 背景：GitHub 的 Release 资产会 302 到 release-assets.githubusercontent.com，
 * 这个域名在部分网络下直接不通，而 api.github.com 却是通的——所以"能检查更新、
 * 但一下载就卡住"是很常见的现场。
 *
 * 这里按"实测能拿到完整字节"的顺序排源。每个源都是同一个文件的不同入口，
 * 调用方逐个试，第一个能下完的就是它。纯类，便于桌面测试排序与拼装规则。
 */
public final class DownloadSources {

    /** 仓库内放发布件的目录。APK 随源码一起进仓库，由 CDN 直出。 */
    static final String CDN_DIR = "release";

    /**
     * CDN 读的引用。
     *
     * 用**一个会被移动的 tag** 而不是分支：
     * jsDelivr 对 `@main` 的同一路径强缓存 7 天，换内容后客户端仍会读到旧文件；
     * 而 tag 在 jsDelivr 里是独立缓存键，每次发布把 `release` 这个 tag 移到新提交，
     * 路径保持不变、内容立即生效。
     */
    static final String REF = "release";

    private DownloadSources() {
    }

    /**
     * 给某个版本号生成下载地址列表。
     *
     * **路径必须带版本号**：jsDelivr 对同一路径强缓存 7 天（max-age=604800），
     * 用固定的 GIme-latest.apk 会导致发了新版还一直下发旧包。
     * 带版本号后，每个版本是不同 URL，天然绕开缓存，也不需要主动刷新。
     */
    public static List<String> forVersion(String repo, String version) {
        return forFile(repo, REF, "GIme-v" + version + ".apk");
    }

    /**
     * @param repo  GitHub 仓库，形如 HagimeLab/GIme
     * @param ref   分支或 tag 名
     * @param file  仓库内文件名（不带目录）
     * @return 按优先级排好的下载地址；调用方按顺序尝试
     */
    public static List<String> forFile(String repo, String ref, String file) {
        List<String> out = new ArrayList<String>();
        String path = CDN_DIR + "/" + file;

        /* jsDelivr 的四个入口实测都能直出 APK（含 .apk 后缀），
           国内网络下 cdn/fastly 通常几十毫秒就返回，且能命中缓存。 */
        out.add("https://cdn.jsdelivr.net/gh/" + repo + "@" + ref + "/" + path);
        out.add("https://fastly.jsdelivr.net/gh/" + repo + "@" + ref + "/" + path);
        out.add("https://testingcf.jsdelivr.net/gh/" + repo + "@" + ref + "/" + path);
        out.add("https://gcore.jsdelivr.net/gh/" + repo + "@" + ref + "/" + path);

        /* 兜底：GitHub 原始地址。部分网络下它反而是通的，
           放在最后试，失败也不影响前面的成功路径。 */
        out.add("https://github.com/" + repo + "/raw/" + ref + "/" + path);
        out.add("https://raw.githubusercontent.com/" + repo + "/" + ref + "/" + path);
        return out;
    }

    /** 发布页：让用户在浏览器里自己取安装包时用。 */
    public static String releasePage(String repo, String version) {
        return "https://github.com/" + repo + "/releases/tag/v" + version;
    }
}
