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
     * CDN 读的引用：固定用 main 分支。
     *
     * 这里不用 tag，因为 tag 带来两个麻烦：每次发布要新建/移动 tag，
     * 而 jsDelivr 对已存在的 tag 路径会命中旧缓存，反而更不可靠。
     *
     * 缓存问题在**路径**上解决，而不是在引用上：安装包路径带版本号
     * （见 forVersion），每个版本都是全新 URL，自然没有旧缓存可命中。
     * 清单路径虽然固定，但不从 CDN 读——见 Updater.fetchManifest，它走 GitHub API。
     */
    static final String REF = "main";

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
        return forFile(repo, REF, apkName(version, ""));
    }

    /**
     * 安装包在仓库里的文件名。
     *
     * 清单没写 apk 字段时，按版本号推一个默认名。
     *
     * **文件名必须随内容变**：jsDelivr 按 URL 强缓存 7 天，而同一个版本号
     * 在开发期难免要重新构建。只要路径不变，重新上传的新包在缓存过期前
     * 都不会被下发——用户装到的还是上一版。把内容指纹钉进文件名，
     * 每个不同的包就是不同的 URL，缓存自然失效。
     *
     * @param version 版本号，如 1.0.2
     * @param digest  内容指纹，一般取安装包 SHA-256 的前 8 位；空则不带
     */
    public static String apkName(String version, String digest) {
        String base = "GIme-v" + version;
        String d = digest == null ? "" : digest.trim();
        if (d.isEmpty()) return base + ".apk";
        return base + "-" + d + ".apk";
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

        /* 安装包不进仓库历史：66MB 的包过不了 Git Data API 的 blob 单次上传上限
           （422 input too large），仓库里也就没有 jsDelivr 能直出的对象。
           所以安装包只走两个发布资产入口：Gitee 发行版（国内首源）→ GitHub Release 资产。 */
        if (file.toLowerCase().endsWith(".apk")) {
            String gv = versionFromApkName(file);
            if (gv != null) {
                out.add("https://gitee.com/hagemilab/gi/releases/download/v" + gv + "/" + file);
                out.add("https://github.com/" + repo + "/releases/download/v" + gv + "/" + file);
                return out;
            }
        }

        /* Gitee 国内中转仓库（用户自建镜像）。
           Gitee 的 raw 只对文本文件直出，二进制会 403，
           所以文本清单走 raw、安装包走发行版附件。 */
        out.add("https://gitee.com/hagemilab/gi/raw/main/" + path);

        /* jsDelivr 的四个入口：清单类小文件走它们最快，且能命中缓存。 */
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

    /**
     * 从安装包文件名里取版本号：GIme-v1.1.7-4e235173.apk → 1.1.7。
     * 取不到返回 null（调用方就不放 Gitee Release 源）。
     */
    static String versionFromApkName(String file) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^GIme-v([0-9]+(?:\\.[0-9]+)*)").matcher(file == null ? "" : file);
        return m.find() ? m.group(1) : null;
    }

    /** 发布页：让用户在浏览器里自己取安装包时用。 */
    public static String releasePage(String repo, String version) {
        return "https://github.com/" + repo + "/releases/tag/v" + version;
    }

    /** 国内镜像发布页（Gitee）：GitHub 连不上时从这里手动取安装包。 */
    public static String giteeReleasePage(String version) {
        return "https://gitee.com/hagemilab/gi/releases/tag/v" + version;
    }
}

