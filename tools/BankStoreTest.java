package com.fan.shuatiku;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BankStore 的桌面回归测试。重点验证「任何一步失败都不丢旧题库」，
 * 靠注入失败来走那些正常跑不到的异常路径。
 */
public class BankStoreTest {

    private static int pass = 0;
    private static int fail = 0;

    private static File dir;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("gime-bankstore").toFile();

        testFirstSave();
        testOverwriteKeepsOldOnRenameFailure();
        testOverwriteSucceeds();
        testRenameFailureRestoresOld();
        testTmpLeftoverRemoved();
        testCleanup();
        testSafeId();

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    /** 首次保存：没有旧文件也要能落盘。 */
    private static void testFirstSave() throws Exception {
        byte[] data = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        boolean ok = BankStore.save(dir, "first", data);
        check("首次保存成功", ok, true);
        check("首次保存内容正确", read("first.json"), "{\"v\":1}");
    }

    /** 核心回归：覆盖已有题库时 rename 失败，旧题库必须原样还在。 */
    private static void testOverwriteKeepsOldOnRenameFailure() throws Exception {
        write("keep.json", "{\"v\":\"old\"}");
        byte[] data = "{\"v\":\"new\"}".getBytes(StandardCharsets.UTF_8);
        // 第一次 move 是 target→bak（成功），第二次是 tmp→target（失败）
        AtomicInteger n = new AtomicInteger();
        boolean ok = BankStore.save(dir, "keep", data, (from, to) -> n.incrementAndGet() != 2);
        check("rename 失败时返回 false", ok, false);
        check("rename 失败后旧题库仍在且内容不变", read("keep.json"), "{\"v\":\"old\"}");
        check("rename 失败后不留临时文件", new File(dir, "keep.json.tmp").exists(), false);
    }

    /** 正常覆盖：新内容生效，备份被清掉。 */
    private static void testOverwriteSucceeds() throws Exception {
        write("ok.json", "{\"v\":\"old\"}");
        byte[] data = "{\"v\":\"new\"}".getBytes(StandardCharsets.UTF_8);
        boolean ok = BankStore.save(dir, "ok", data);
        check("覆盖保存成功", ok, true);
        check("覆盖后是新内容", read("ok.json"), "{\"v\":\"new\"}");
        check("成功后不留 .bak", new File(dir, "ok.json.bak").exists(), false);
    }

    /** tmp→target 成功但长度对不上（模拟写入被截断），应回滚旧文件。 */
    private static void testRenameFailureRestoresOld() throws Exception {
        write("trunc.json", "{\"v\":\"old\"}");
        byte[] data = "{\"v\":\"new\"}".getBytes(StandardCharsets.UTF_8);
        // 只在「tmp 挪成正式文件」那一次把内容截空，模拟落盘不完整；
        // 回滚那一步（.bak → .json）必须正常搬运，否则测的就不是产品代码了。
        AtomicInteger toTarget = new AtomicInteger();
        boolean ok = BankStore.save(dir, "trunc", data, (from, to) -> {
            boolean moved = from.renameTo(to);
            if (moved && to.getName().equals("trunc.json") && toTarget.incrementAndGet() == 1) {
                try { Files.write(to.toPath(), new byte[0]); } catch (Exception ignored) { }
            }
            return moved;
        });
        check("长度校验失败时返回 false", ok, false);
        check("长度校验失败后旧题库被放回", read("trunc.json"), "{\"v\":\"old\"}");
    }

    /** 写临时文件阶段就失败，旧文件不能被碰。 */
    private static void testTmpLeftoverRemoved() throws Exception {
        write("tmpfail.json", "{\"v\":\"old\"}");
        File tmp = new File(dir, "tmpfail.json.tmp");
        Files.write(tmp.toPath(), "残留".getBytes(StandardCharsets.UTF_8));
        BankStore.cleanup(dir);
        check("cleanup 清掉残留 .tmp", tmp.exists(), false);
        check("cleanup 不动正式文件", read("tmpfail.json"), "{\"v\":\"old\"}");
    }

    /** 孤儿的 .bak（对应正式文件已不在）要保留，别把唯一副本删了。 */
    private static void testCleanup() throws Exception {
        write("orphan.json.bak", "{\"v\":\"only\"}");
        BankStore.cleanup(dir);
        check("孤儿 .bak 不被误删", new File(dir, "orphan.json.bak").exists(), true);
        write("done.json", "{}");
        write("done.json.bak", "{}");
        BankStore.cleanup(dir);
        check("正式文件还在时清掉 .bak", new File(dir, "done.json.bak").exists(), false);
    }

    /** 题库 id 会被拼进文件名，必须把路径分隔符等危险字符挡掉。 */
    private static void testSafeId() {
        check("路径分隔符被替换", BankStore.safe("../../etc/passwd").contains("/"), false);
        check("反斜杠被替换", BankStore.safe("a\\b").contains("\\"), false);
        check("null id 有兜底", BankStore.safe(null), "bad");
        check("正常 id 原样保留", BankStore.safe("import_abc-1.2"), "import_abc-1.2");
    }

    private static String read(String name) throws Exception {
        File f = new File(dir, name);
        if (!f.exists()) return "<缺失>";
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(String name, String content) throws Exception {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(String name, boolean actual, boolean expect) {
        if (actual == expect) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）"); }
    }

    private static void check(String name, String actual, String expect) {
        if (expect.equals(actual)) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）"); }
    }
}
