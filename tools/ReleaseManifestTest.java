package com.fan.shuatiku;

/**
 * release/latest.json 的解析回归。
 *
 * 这份清单是「版本号 + 安装包文件名」的唯一真源：版本检查和下载地址都从它推出来，
 * 避免出现「说有新版本、下载地址却 404」这种对不上的情况。
 */
public class ReleaseManifestTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // 正常清单
        ReleaseManifest.Info ok = ReleaseManifest.parse(
            "{\"version\":\"1.0.3\",\"apk\":\"GIme-v1.0.3.apk\",\"date\":\"2026-10-18\"," +
            "\"notes\":[\"第一条\",\"第二条\"]}");
        checkBool("正常清单可解析", ok.valid);
        checkStr("版本号正确", ok.version, "1.0.3");
        checkStr("安装包名正确", ok.apk, "GIme-v1.0.3.apk");
        checkStr("日期正确", ok.date, "2026-10-18");
        checkInt("说明条数正确", ok.notes.length, 2);
        checkStr("首条说明", ok.notes[0], "第一条");

        // v 前缀要去掉，否则版本比较会当成字符串
        ReleaseManifest.Info v = ReleaseManifest.parse("{\"version\":\"v1.0.4\"}");
        checkStr("去掉 v 前缀", v.version, "1.0.4");
        checkBool("缺 apk 字段时仍有效", v.valid);
        checkStr("缺 apk 时按版本号推默认名", ReleaseManifest.apkNameFor(v), "GIme-v1.0.4.apk");

        // 脏数据一律判无效，交给调用方回落，不抛异常
        checkBool("空字符串判无效", ReleaseManifest.parse("").valid == false);
        checkBool("null 判无效", ReleaseManifest.parse(null).valid == false);
        checkBool("非法 JSON 判无效", ReleaseManifest.parse("{oops").valid == false);
        checkBool("缺 version 判无效", ReleaseManifest.parse("{\"apk\":\"x.apk\"}").valid == false);

        // notes 里的空串要剔掉，否则界面上会出现空条目
        ReleaseManifest.Info n = ReleaseManifest.parse(
            "{\"version\":\"1.0.5\",\"notes\":[\"有内容\",\"\",\"也有内容\"]}");
        checkInt("空说明被剔除", n.notes.length, 2);

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    private static void checkBool(String name, boolean actual) {
        if (actual) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    private static void checkStr(String name, String actual, String expect) {
        if (expect.equals(actual)) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）"); }
    }

    private static void checkInt(String name, int actual, int expect) {
        if (actual == expect) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）"); }
    }
}
