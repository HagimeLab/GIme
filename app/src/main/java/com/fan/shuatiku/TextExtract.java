package com.fan.shuatiku;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.content.Context;

import java.io.File;

/**
 * 只负责把文件变成纯文本，题目解析交给页面里的 JS 做。
 * docx 本质是 zip + word/document.xml，用 java.util.zip 就够，不引第三方库。
 */
public final class TextExtract {

    /** 单个文件最多读这么多字节。题库再大也就几百 KB，20MB 已经是异常值。 */
    static final long MAX_BYTES = 20L * 1024 * 1024;
    /** docx 解压后的正文 XML 上限，防压缩炸弹。 */
    static final int MAX_XML = 16 * 1024 * 1024;

    /** 用户能看懂的拒绝理由；MainActivity 直接把它甩给页面。 */
    static class Reject extends IOException {
        Reject(String msg) { super(msg); }
    }

    private TextExtract() {
    }

    public static String extract(Context ctx, InputStream in, String name) throws IOException {
        String lower = name == null ? "" : name.toLowerCase();
        String ext = extOf(lower);
        /* 有些内容提供方（微信、网盘、部分文件管理器）给的 DISPLAY_NAME 干脆没有后缀，
           只按名字判断会把好好的 PDF 当成「不支持的格式」拒掉。后缀缺失或陌生时，
           退回按文件头认——PDF 是 %PDF-，docx 是 PK 开头的 zip。 */
        if (!KNOWN_EXT.contains(ext)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BYTES) throw new Reject("文件太大（超过 20MB），请拆分后再导入");
            }
            byte[] head = bos.toByteArray();
            String sniffed = sniff(head);
            if (sniffed != null) return fromBytes(ctx, head, sniffed);
            if (".doc".equals(ext)) throw new Reject("不支持 .doc 旧格式，请用 Word 另存为 .docx 后再导入");
            throw new Reject("只支持 PDF、Word（.docx）和 txt / md，其他格式请先另存为 .docx");
        }
        if (".docx".equals(ext)) {
            File tmp = File.createTempFile("import-", ".docx", ctx.getCacheDir());
            try {
                copy(in, tmp);
                return docx(tmp);
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
        /* PDF 必须落成临时文件：解析器要能随机访问整个文件（交叉引用表、对象流），
           流式读不了。这也是唯一一个需要第三方库的格式。 */
        if (".pdf".equals(ext)) {
            File tmp = File.createTempFile("import-", ".pdf", ctx.getCacheDir());
            try {
                copy(in, tmp);
                return PdfText.extract(ctx, tmp);
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
        if (".txt".equals(ext) || ".md".equals(ext)) return plain(readAll(in));
        /* 老式 .doc 是 OLE 复合文档，不是 zip，本版本没有解析器；其余后缀一律拒绝，
           免得拿二进制当 UTF-8 读出一堆乱码再报「没认出题目」。 */
        if (".doc".equals(ext)) throw new Reject("不支持 .doc 旧格式，请用 Word 另存为 .docx 后再导入");
        throw new Reject("只支持 PDF、Word（.docx）和 txt / md，其他格式请先另存为 .docx");
    }

    /** 能按后缀直接分派的格式。不在这里的一律先嗅探文件头。 */
    private static final java.util.Set<String> KNOWN_EXT =
            new java.util.HashSet<>(java.util.Arrays.asList(".pdf", ".docx", ".txt", ".md", ".doc"));

    /** 按文件头认格式；认不出返回 null（交给调用方按文本处理或拒绝）。 */
    static String sniff(byte[] b) {
        if (b == null || b.length < 4) return null;
        /* PDF 规范允许 %PDF- 前面有少量垃圾字节，但绝大多数文件就是开头 */
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return ".pdf";
        if (b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) return ".docx";
        return null;
    }

    /** 已经整份读进内存时的分派（只在嗅探路径上用）。 */
    private static String fromBytes(Context ctx, byte[] raw, String ext) throws IOException {
        if (".pdf".equals(ext)) {
            File tmp = File.createTempFile("import-", ".pdf", ctx.getCacheDir());
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
                try {
                    os.write(raw);
                } finally {
                    os.close();
                }
                return PdfText.extract(ctx, tmp);
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
        if (".docx".equals(ext)) {
            File tmp = File.createTempFile("import-", ".docx", ctx.getCacheDir());
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
                try {
                    os.write(raw);
                } finally {
                    os.close();
                }
                return docx(tmp);
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
        return plain(raw);
    }

    /** 取小写后缀（含点），没有后缀返回空串。 */
    private static String extOf(String lower) {
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot);
    }

    private static void copy(InputStream in, File out) throws IOException {
        java.io.FileOutputStream os = new java.io.FileOutputStream(out);
        try {
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new Reject("文件太大（超过 20MB），请拆分后再导入");
                os.write(buf, 0, n);
            }
        } finally {
            os.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) throw new Reject("文件太大（超过 20MB），请拆分后再导入");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** txt / md：先认 BOM，再按 UTF-8 严格解，失败退回 GB18030（国内题库常见）。 */
    static String plain(byte[] raw) {
        int off = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return d.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("GB18030"));
        }
    }

    static String docx(File file) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(file);
        } catch (IOException e) {
            /* 不是 zip 就不是 docx（.doc 改名、下载中断、文件损坏都会走到这里）。
               原样抛上去只会让用户看到 "zip END header not found"。 */
            throw new Reject("这个文件不是有效的 Word 文档，可能已损坏或不是 .docx");
        }
        try {
            Enumeration<? extends ZipEntry> es = zip.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (!"word/document.xml".equals(e.getName())) continue;
                if (e.getSize() > MAX_XML) throw new Reject("这个 docx 解压后过大，请拆分后再导入");
                InputStream xmlIn = zip.getInputStream(e);
                try {
                    String xml = new String(readAll(xmlIn), StandardCharsets.UTF_8);
                    return documentText(xml);
                } finally {
                    xmlIn.close();
                }
            }
            throw new Reject("这个文件不是正常的 Word 文档（缺 word/document.xml）");
        } finally {
            zip.close();
        }
    }

    /**
     * 逐段取正文；红字（FF0000 / C00000 一类）用 \u0001 \u0002 包起来，
     * 页面解析时可以用"哪些选项是红的"来推答案——华为题库 PDF 常用这个排版。
     */
    static String documentText(String xml) {
        StringBuilder out = new StringBuilder();
        for (String para : xml.split("</w:p>")) {
            String line = paragraphText(para);
            if (line.length() > 0 || out.length() > 0) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String paragraphText(String paraXml) {
        StringBuilder sb = new StringBuilder();
        for (String run : paraXml.split("</w:r>")) {
            List<String> texts = betweenAll(run, "<w:t", "</w:t>");
            if (texts.isEmpty() && run.indexOf("<w:tab") >= 0) {
                sb.append(' ');
                continue;
            }
            if (texts.isEmpty()) {
                if (run.indexOf("<w:br") >= 0) sb.append('\n');
                continue;
            }
            String joined = unescape(String.join("", texts));
            if (isRed(run)) sb.append('\u0001').append(joined).append('\u0002');
            else sb.append(joined);
        }
        return sb.toString();
    }

    private static boolean isRed(String run) {
        return run.indexOf("w:val=\"FF0000\"") >= 0 || run.indexOf("w:val=\"ff0000\"") >= 0
                || run.indexOf("w:val=\"C00000\"") >= 0 || run.indexOf("w:val=\"c00000\"") >= 0
                || run.indexOf("w:val=\"EE0000\"") >= 0 || run.indexOf("w:val=\"FF3333\"") >= 0;
    }

    private static List<String> betweenAll(String s, String openTag, String close) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int a = s.indexOf(openTag, i);
            if (a < 0) break;
            int gt = s.indexOf('>', a);
            int b = s.indexOf(close, gt);
            if (gt < 0 || b < 0) break;
            out.add(s.substring(gt + 1, b));
            i = b + close.length();
        }
        return out;
    }

    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
