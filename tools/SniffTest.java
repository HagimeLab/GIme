package com.fan.shuatiku;

public class SniffTest {
    static int pass=0, fail=0;
    static void ok(String n, boolean c){ ok(n, c, null); }
    static void ok(String n, boolean c, String d){ if(c){pass++;System.out.println("  [PASS] "+n);} else {fail++;System.out.println("  [FAIL] "+n+(d==null?"":"  ("+d+")"));} }
    public static void main(String[] a){
        System.out.println("=== sniff: 后缀缺失时按文件头认格式 ===");
        ok("PDF 头被认出", ".pdf".equals(TextExtract.sniff(new byte[]{'%','P','D','F','-','1','.','4'})), TextExtract.sniff(new byte[]{'%','P','D','F'}));
        ok("zip/docx 头被认出", ".docx".equals(TextExtract.sniff(new byte[]{'P','K',3,4,20,0,0,0})), TextExtract.sniff(new byte[]{'P','K',3,4}));
        ok("纯文本认不出（返回 null，交给文本路径）", TextExtract.sniff("1、题干\nA、选项".getBytes()) == null);
        ok("太短的字节不炸", TextExtract.sniff(new byte[]{'%'}) == null);
        ok("null 不炸", TextExtract.sniff(null) == null);
        ok("空数组不炸", TextExtract.sniff(new byte[0]) == null);
        ok("docx 的 PK\\x03\\x04 与 zip 共用同一判断", ".docx".equals(TextExtract.sniff(new byte[]{'P','K',3,4,1,2,3,4})));
        ok("PK 但版本号不对不算 zip", TextExtract.sniff(new byte[]{'P','K',7,8,0,0,0,0}) == null);
        System.out.println();
        if(fail>0){ System.out.println("失败 "+fail+" 项"); System.exit(1);} 
        System.out.println("全部通过："+pass+" 项");
    }
}
