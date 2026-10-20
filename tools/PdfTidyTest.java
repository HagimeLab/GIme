import com.fan.shuatiku.PdfTidy;

/**
 * PdfTidy 的桌面测试：不装手机就能验证「什么样的 PDF 输出算不能用」。
 *
 * 这一层最容易出两类错：把扫描件当成功导入（用户看到空题库），
 * 把正常中文当成乱码拒掉（用户白折腾）。两种都要钉住。
 */
public class PdfTidyTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void ok(String name, boolean cond) { ok(name, cond, null); }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + (detail == null ? "" : "  (" + detail + ")")); }
    }

    public static void main(String[] args) {
        tidyCases();
        footerCases();
        rejectCases();
        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    private static void tidyCases() {
        System.out.println("=== tidy：中文之间的空格要去掉，英文之间的要保留 ===");

        ok("汉字之间的空格被去掉",
                "以下哪个是可靠传输协议".equals(PdfTidy.tidy("以 下 哪 个 是 可 靠 传 输 协 议")),
                PdfTidy.tidy("以 下 哪 个 是 可 靠 传 输 协 议"));

        ok("英文单词之间的空格保留",
                "TCP provides reliable transfer".equals(PdfTidy.tidy("TCP provides reliable transfer")),
                PdfTidy.tidy("TCP provides reliable transfer"));

        ok("中英混排：汉字间的空格去，英文间的留",
                "使用 TCP 协议".equals(PdfTidy.tidy("使 用 TCP 协 议")),
                PdfTidy.tidy("使 用 TCP 协 议"));

        ok("行首行尾空白被裁掉",
                "1、题干\nA、选项".equals(PdfTidy.tidy("   1、题干   \n   A、选项  ")),
                PdfTidy.tidy("   1、题干   \n   A、选项  "));

        ok("CRLF 统一成 LF",
                "第一行\n第二行".equals(PdfTidy.tidy("第一行\r\n第二行")),
                PdfTidy.tidy("第一行\r\n第二行"));

        ok("不换行空格与全角空格被归一",
                "答案：B".equals(PdfTidy.tidy("答案：\u00A0B")),
                PdfTidy.tidy("答案：\u00A0B"));

        ok("空串不炸", "".equals(PdfTidy.tidy("")));
        ok("null 不炸", "".equals(PdfTidy.tidy(null)));

        ok("全角括号之间的空格也去掉",
                "（单选题）题干".equals(PdfTidy.tidy("（ 单选题 ） 题干")),
                PdfTidy.tidy("（ 单选题 ） 题干"));

        ok("连续多个空格不会挤成零宽",
                PdfTidy.tidy("A   B").indexOf("  ") < 0 || PdfTidy.tidy("A   B").equals("A B"),
                PdfTidy.tidy("A   B"));

        /* 关键回归：不能把正常中文句子里的标点吃掉 */
        String normal = "1、以下哪个是可靠传输协议？\nA、UDP\nB、TCP\n答案：B";
        ok("正常题库文本原样保留", normal.equals(PdfTidy.tidy(normal)), PdfTidy.tidy(normal));
    }

    private static void footerCases() {
        System.out.println();
        System.out.println("=== dropPageFooter：只删页末那一行、且正好是本页页码 ===");

        String page = "第3题\n\n题目：以下哪个选项为字符处理函数？\n\n3\n";
        ok("页末页码被删掉",
                PdfTidy.dropPageFooter(page, 3).indexOf("\n3\n") < 0
                        && PdfTidy.dropPageFooter(page, 3).contains("字符处理函数"),
                PdfTidy.dropPageFooter(page, 3));
        ok("页码不对时不删（正文里的 4 留着）",
                PdfTidy.dropPageFooter(page, 4).contains("\n3\n"), PdfTidy.dropPageFooter(page, 4));
        ok("页末是正文时不动",
                "题目：3 个选项哪个对".equals(PdfTidy.dropPageFooter("题目：3 个选项哪个对", 3)),
                PdfTidy.dropPageFooter("题目：3 个选项哪个对", 3));
        ok("正文中间的页码行不删",
                PdfTidy.dropPageFooter("3\n题目：以下哪个对\n答案：A", 3).startsWith("3"),
                PdfTidy.dropPageFooter("3\n题目：以下哪个对\n答案：A", 3));
        ok("页末带空行也能认出页码",
                PdfTidy.dropPageFooter("题干\n\n3\n\n", 3).indexOf("3") < 0,
                PdfTidy.dropPageFooter("题干\n\n3\n\n", 3));
        ok("空串与 null 不炸",
                "".equals(PdfTidy.dropPageFooter("", 1)) && "".equals(PdfTidy.dropPageFooter(null, 1)));
    }

    private static void rejectCases() {
        System.out.println();
        System.out.println("=== rejectReason：三种读不出来的情况必须分别说清 ===");

        ok("空白 PDF 被拒", PdfTidy.rejectReason("", 3) != null);
        ok("空白 PDF 的提示提到扫描件",
                PdfTidy.rejectReason("   \n\n  ", 2).contains("扫描件"),
                PdfTidy.rejectReason("   \n\n  ", 2));

        StringBuilder few = new StringBuilder();
        for (int i = 0; i < 20; i++) few.append("啊");
        String fewMsg = PdfTidy.rejectReason(few.toString(), 10);
        ok("每页字数过少（扫描件）被拒", fewMsg != null, fewMsg);
        ok("字数过少的提示带页数", fewMsg != null && fewMsg.contains("10"), fewMsg);
        ok("字数过少的提示提到 OCR", fewMsg != null && fewMsg.contains("OCR"), fewMsg);

        StringBuilder pua = new StringBuilder();
        for (int i = 0; i < 200; i++) pua.append('\uE123');
        String puaMsg = PdfTidy.rejectReason(pua.toString(), 1);
        ok("私用区字符（字体没 ToUnicode）被拒", puaMsg != null, puaMsg);
        ok("乱码提示提到编码", puaMsg != null && puaMsg.contains("编码"), puaMsg);

        StringBuilder fffd = new StringBuilder();
        for (int i = 0; i < 200; i++) fffd.append('\uFFFD');
        ok("替换符堆满也被拒", PdfTidy.rejectReason(fffd.toString(), 1) != null);

        /* 少量私用区不算乱码：有些题库里真有 ① ② 这类符号，不能一刀切 */
        StringBuilder mostlyOk = new StringBuilder();
        for (int i = 0; i < 400; i++) mostlyOk.append('题');
        for (int i = 0; i < 20; i++) mostlyOk.append('\uE123');
        ok("少量私用区不影响导入", PdfTidy.rejectReason(mostlyOk.toString(), 1) == null,
                PdfTidy.rejectReason(mostlyOk.toString(), 1));

        /* 正常题库文本必须通过 */
        StringBuilder good = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            good.append(i + 1).append("、以下哪个是可靠传输协议？\nA、UDP\nB、TCP\n答案：B\n解析：TCP 有确认重传。\n");
        }
        ok("正常题库文本通过体检", PdfTidy.rejectReason(good.toString(), 4) == null,
                PdfTidy.rejectReason(good.toString(), 4));

        /* 页数很少但字很多，说明文字层是好的 */
        ok("单页长文档不算扫描件", PdfTidy.rejectReason(good.toString(), 1) == null);

        /* 边界：刚好达到每页下限 */
        StringBuilder edge = new StringBuilder();
        for (int i = 0; i < PdfTidy.MIN_CHARS_PER_PAGE; i++) edge.append('题');
        ok("刚好达到每页字数下限时放行", PdfTidy.rejectReason(edge.toString(), 1) == null);
        ok("差一个字就被拒", PdfTidy.rejectReason(edge.substring(0, PdfTidy.MIN_CHARS_PER_PAGE - 1), 1) != null);
    }
}
