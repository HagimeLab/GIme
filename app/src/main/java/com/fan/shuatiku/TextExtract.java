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

    private TextExtract() {
    }

    public static String extract(Context ctx, InputStream in, String name) throws IOException {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".docx")) {
            File tmp = File.createTempFile("import-", ".docx", ctx.getCacheDir());
            try {
                copy(in, tmp);
                return docx(tmp);
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
        return plain(readAll(in));
    }

    private static void copy(InputStream in, File out) throws IOException {
        java.io.FileOutputStream os = new java.io.FileOutputStream(out);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            os.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
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
        ZipFile zip = new ZipFile(file);
        try {
            Enumeration<? extends ZipEntry> es = zip.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (!"word/document.xml".equals(e.getName())) continue;
                String xml = new String(readAll(zip.getInputStream(e)), StandardCharsets.UTF_8);
                return documentText(xml);
            }
            throw new IOException("这个 docx 里没有 word/document.xml");
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
