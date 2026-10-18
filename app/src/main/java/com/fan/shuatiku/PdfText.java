package com.fan.shuatiku;

import java.io.File;
import java.io.IOException;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;

/**
 * PDF 正文抽取。只做一件事：把 PDF 的文字层按行取出来交给页面解析。
 *
 * 这里刻意不碰加密、不做 OCR：
 * 加密的 PDF 我们解不开（也没打算解），扫描件根本没有文字层，
 * 两种情况都必须明确告诉用户「这个文件读不了」，而不是吐一堆空行或乱码让人以为导入成功了。
 *
 * 规整与体检的规则在 PdfTidy 里，那部分不依赖 Android，桌面上能直接测。
 */
public final class PdfText {

    private PdfText() {
    }

    /** PDFBox 的字体资源要先登记，否则标准 14 字体和 CMap 都找不到。 */
    private static void init(Context ctx) {
        try {
            PDFBoxResourceLoader.init(ctx.getApplicationContext());
        } catch (Throwable ignored) {
            // 重复 init 或已初始化过都会走到这里，不影响后续解析
        }
    }

    static String extract(Context ctx, File file) throws IOException {
        init(ctx);
        PDDocument doc;
        try {
            doc = PDDocument.load(file);
        } catch (InvalidPasswordException e) {
            throw new TextExtract.Reject("这个 PDF 有密码保护，请先用阅读器去掉密码再导入");
        } catch (NoClassDefFoundError e) {
            // 加密 PDF 的解析路径依赖 BouncyCastle，而我们在依赖里把它排除了
            // （它会让安装包多出 8MB，而我们的策略本来就是拒绝加密文件）。
            throw new TextExtract.Reject("这个 PDF 可能加了密，请先用阅读器另存为无密码的版本再导入");
        } catch (IOException e) {
            throw new TextExtract.Reject("这个 PDF 打不开，可能已损坏或不是标准 PDF 文件");
        }

        try {
            int pages = doc.getNumberOfPages();
            if (pages <= 0) throw new TextExtract.Reject("这个 PDF 里没有页面，可能是空文件");

            String text;
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                /* 按位置排序：不排序时 PDFBox 会按内容流顺序吐字，
                   双栏或带表格的题库会整段串行，题号全乱 */
                stripper.setSortByPosition(true);
                stripper.setLineSeparator("\n");
                stripper.setParagraphEnd("\n");
                stripper.setPageEnd("\n");
                text = stripper.getText(doc);
            } catch (NoClassDefFoundError e) {
                throw new TextExtract.Reject("这个 PDF 的字体编码不受支持，请换一个文件或另存为 .docx 再导入");
            } catch (IOException e) {
                throw new TextExtract.Reject("这个 PDF 的文字层读不出来，可能已损坏");
            }

            String tidy = PdfTidy.tidy(text);
            String bad = PdfTidy.rejectReason(tidy, pages);
            if (bad != null) throw new TextExtract.Reject(bad);
            return tidy;
        } finally {
            try {
                doc.close();
            } catch (IOException ignored) {
            }
        }
    }
}
