package com.fan.shuatiku;

/** 版本号比较。抽成不依赖 Android 的纯类，才能在桌面 javac 直接跑测试。 */
public class Ver {

    /** "1.2.10" 比 "1.2.9" 新：按段做数值比较，不能比字符串。 */
    public static boolean isNewer(String latest, String current) {
        int[] a = parse(latest), b = parse(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** 容忍 "v1.0.2"、"1.0.2-beta"、空串和纯数字以外的尾巴。 */
    public static int[] parse(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '.' && (ch < '0' || ch > '9')) { cut = i; break; }
        }
        String[] parts = s.substring(0, cut).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (Exception e) { out[i] = 0; }
        }
        return out;
    }
}
