package com.fan.shuatiku;

/**
 * PDF 抽出来的文字要先过两关才能交给页面解析：规整、体检。
 *
 * 单独拆出来是因为这两步都是纯字符串处理，不碰 Android、不碰 PDFBox，
 * 可以在桌面上直接 javac + java 跑测试。真机上的解析要验，但「什么样的输出算不能用」
 * 这条规则不该只有装上手机才能验证。
 */
public final class PdfTidy {

    /** 平均每页少于这么多字符，基本可以断定是扫描件（纯图片，没有文字层）。 */
    public static final int MIN_CHARS_PER_PAGE = 12;
    /** 私用区字符 + 替换符占比超过它，说明字体编码没解出来，取到的是乱码。 */
    public static final double MAX_ODD_RATIO = 0.20;

    private PdfTidy() {
    }

    /**
     * 判断取到的正文到底能不能用。返回 null 表示可用，否则返回给用户看的一句话。
     *
     * 三种情况分开报，因为用户要做的事完全不同：扫描件要去 OCR，
     * 乱码要换导出方式，空白页是文件本身有问题。
     */
    public static String rejectReason(String text, int pages) {
        String body = text == null ? "" : text.replace("\n", "").replace(" ", "").trim();
        if (body.isEmpty()) {
            return "这个 PDF 没有文字层，是扫描件或图片版。"
                    + "请用 OCR 工具转成文字，或找一份文字版 PDF 再导入";
        }
        int odd = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            /* 私用区（U+E000–U+F8FF）是字体没有 ToUnicode 映射时的典型症状：
               取出来的每个字都是「看不见的私有码位」，看着像乱码但又不是替换符 */
            if ((c >= '\uE000' && c <= '\uF8FF') || c == '\uFFFD') odd++;
        }
        if (odd > body.length() * MAX_ODD_RATIO) {
            return "这个 PDF 的文字编码解不出来，取到的是乱码。"
                    + "请换一个来源，或用 Word 另存为 .docx 再导入";
        }
        if (body.length() < pages * MIN_CHARS_PER_PAGE) {
            return "这个 PDF 有 " + pages + " 页，但几乎取不到文字，"
                    + "多半是扫描件。请用 OCR 工具转成文字后再导入";
        }
        return null;
    }

    /**
     * 去掉每页末尾的页码。
     *
     * 页码在 PDF 里是一段独立的文字，抽出来就是页末单独一行「3」。
     * 交给页面解析时，它既不是题号（不连续）也不是选项，只会被当成续行
     * 拼进题干、选项或解析的结尾，用户看到的就是「……描述正确的是？ 3」。
     * 只删「页末那一行且正好等于本页页码」的数字，正文里的数字一概不动。
     */
    public static String dropPageFooter(String pageText, int pageNo) {
        if (pageText == null) return "";
        String[] lines = pageText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (!t.equals(String.valueOf(pageNo))) return pageText;
            StringBuilder sb = new StringBuilder(pageText.length());
            for (int j = 0; j < lines.length; j++) {
                if (j > 0) sb.append('\n');
                if (j != i) sb.append(lines[j]);
            }
            return sb.toString();
        }
        return pageText;
    }

    /**
     * 规整 PDF 抽出来的文字。PDF 是按字形定位排版的，抽成纯文本后会带两类噪声：
     * 一是中文之间被塞进空格（「以 下 哪 个」），二是每行首尾的空白。
     * 中文本来就不靠空格分词，把汉字之间的空格去掉是安全的；英文单词之间的空格保留。
     */
    public static String tidy(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00A0', ' ').replace('\u3000', ' ');
        StringBuilder out = new StringBuilder(s.length());
        String[] lines = s.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.indexOf(' ') >= 0 || line.indexOf('\t') >= 0) line = squeezeCjk(line);
            out.append(line.trim());
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /** 去掉「汉字 空格 汉字」里的空格，英文与数字之间的原样保留。 */
    private static String squeezeCjk(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        char prev = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                int j = i;
                while (j < line.length() && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) j++;
                char next = j < line.length() ? line.charAt(j) : 0;
                boolean drop = (isCjk(prev) && isCjk(next))
                        /* 中文标点两侧的空格同样是排版噪声：「答案： B」抽出来就是多一个空格，
                           它不影响解析，但会出现在题面和选项里，看着像数据脏了 */
                        || isCjkPunct(prev) || isCjkPunct(next);
                if (!drop) sb.append(' ');
                i = j - 1;
                prev = ' ';
                continue;
            }
            sb.append(c);
            prev = c;
        }
        return sb.toString();
    }

    static boolean isCjk(char c) {
        if (c == 0) return false;
        /* 汉字、中文标点、全角字母数字：这些字符之间的空格一定是排版噪声 */
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\u3000' && c <= '\u303F')
                || (c >= '\uFF00' && c <= '\uFFEF')
                || (c >= '\uFE30' && c <= '\uFE4F');
    }

    /** 中文标点（含全角标点）。它两侧的空格一律是排版噪声，与汉字之间的规则分开写更好读。 */
    static boolean isCjkPunct(char c) {
        if (c == 0) return false;
        return (c >= '\u3000' && c <= '\u303F')
                || (c >= '\uFF00' && c <= '\uFFEF')
                || (c >= '\uFE30' && c <= '\uFE4F');
    }
}
