package com.fan.shuatiku;

/**
 * 更新下载状态机的桌面回归。
 *
 * 不碰网络，只钉住「下载器报了某个状态，界面应该做什么」这条规则。
 * 之前 WAITING_TO_RETRY(194) 落进「还在下」，界面永远停在 0%，
 * 用户看到的就是「点了更新一直在转圈」。这里直接调用产品代码里的 outcomeOf，
 * 不在测试里另抄一份判断。
 */
public class UpdaterTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // Android DownloadManager 的公开常量值
        check("RUNNING(2) 显示进度", DownloadOutcome.of(2), "progress");
        check("PENDING(1) 显示进度", DownloadOutcome.of(1), "progress");
        check("SUCCESSFUL(8) 拉起安装", DownloadOutcome.of(8), "install");
        check("PAUSED(4) 报错", DownloadOutcome.of(4), "error");
        check("FAILED(16) 报错", DownloadOutcome.of(16), "error");

        // 关键回归：@hide 的 WAITING_TO_RETRY=194，漏掉它就退化成"永远转圈"
        check("WAITING_TO_RETRY(194) 必须报错", DownloadOutcome.of(194), "error");

        // 未知状态保持等待，别误报
        check("未知状态先等待", DownloadOutcome.of(190), "wait");
        check("未知状态先等待(2)", DownloadOutcome.of(999), "wait");

        // 0 是"没有这条下载"，不能当成功也不能当失败
        check("status=0 不误判为安装", DownloadOutcome.of(0), "wait");

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    private static void check(String name, String actual, String expect) {
        if (expect.equals(actual)) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）"); }
    }
}

