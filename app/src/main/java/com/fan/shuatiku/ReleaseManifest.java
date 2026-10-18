package com.fan.shuatiku;

/**
 * 更新元数据的来源。
 *
 * 原来只读 GitHub 的 releases/latest，但那个接口和"仓库里实际放了哪个安装包"
 * 是两套东西：一旦 Release 没建成功、或建了但资产没传上去，
 * 拼出来的下载地址就会 404，而界面还理直气壮地说"有新版本"。
 *
 * 现在把唯一真源放在仓库里：release/latest.json 同时声明版本号、安装包文件名和说明。
 * 版本检查读它，下载地址也由它推出来，两边不会再对不上。
 *
 * 刻意不依赖 org.json：这份解析要能在桌面 javac 上直接跑测试，
 * 而清单格式只有固定几个字段，手写解析比引依赖更可控。
 */
public final class ReleaseManifest {

    /** 仓库内的清单路径。 */
    static final String PATH = "release/latest.json";

    /** 解析结果。字段缺失时给出安全的空值，不抛异常。 */
    static final class Info {
        String version = "";
        String apk = "";
        String date = "";
        String[] notes = new String[0];
        /** 清单是否可用；false 时调用方应回落到 GitHub API。 */
        boolean valid;
    }

    private ReleaseManifest() {
    }

    /** 解析 latest.json。格式错误一律返回 invalid，由调用方决定回退。 */
    public static Info parse(String json) {
        Info out = new Info();
        if (json == null || json.trim().isEmpty()) return out;

        String v = str(json, "version");
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        v = v.trim();
        if (v.isEmpty()) return out;

        out.version = v;
        out.apk = str(json, "apk").trim();
        out.date = str(json, "date").trim();
        out.notes = strArray(json, "notes");
        out.valid = true;
        return out;
    }

    /**
     * 清单里没写 apk 文件名时，按版本号推一个默认名。
     *
     * 正常发布时清单会显式写上带内容指纹的文件名（见 DownloadSources.apkName），
     * 这里只是兜底，保证字段缺失时仍能拼出一个地址而不是空串。
     */
    public static String apkNameFor(Info info) {
        if (info.apk != null && !info.apk.isEmpty()) return info.apk;
        return DownloadSources.apkName(info.version, "");
    }

    /** 取一个字符串字段；缺失返回空串。 */
    private static String str(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return "";
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        StringBuilder sb = new StringBuilder();
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(p + 1);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else if (n == 'r') sb.append('\r');
                else sb.append(n);
                p++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return "";
    }

    /** 取字符串数组字段；空串条目会被剔除。 */
    private static String[] strArray(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return new String[0];
        int open = json.indexOf('[', k + needle.length());
        if (open < 0) return new String[0];
        int close = json.indexOf(']', open);
        if (close < 0) return new String[0];

        java.util.List<String> list = new java.util.ArrayList<String>();
        String body = json.substring(open + 1, close);
        int i = 0;
        while (i < body.length()) {
            int q = body.indexOf('"', i);
            if (q < 0) break;
            StringBuilder sb = new StringBuilder();
            int p = q + 1;
            for (; p < body.length(); p++) {
                char c = body.charAt(p);
                if (c == '\\' && p + 1 < body.length()) {
                    char n = body.charAt(p + 1);
                    if (n == 'n') sb.append('\n');
                    else if (n == 't') sb.append('\t');
                    else if (n == 'r') sb.append('\r');
                    else sb.append(n);
                    p++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            String s = sb.toString().trim();
            if (!s.isEmpty()) list.add(s);
            i = p + 1;
        }
        return list.toArray(new String[0]);
    }
}
