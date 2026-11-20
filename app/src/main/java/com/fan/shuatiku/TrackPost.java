package com.fan.shuatiku;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 轨的后处理：把「按 18 字硬切」的碎片重新编排成适合上屏的短句。
 *
 * <p>生成时为了不丢字，仍然按端点/满 18 字切；但那样会把「…我是黑马刚 / 哥。今天…」
 * 劈成两句。这里先把所有碎片按字符展开成时间轴，再按句末标点合并成整句；
 * 口语化视频常有 20 秒不断句的长句，整句上屏会把后面十几秒要说的话提前铺出来，
 * 所以整句再按句内标点（逗号/顿号/冒号）切成 ≤{@link #MAX_CUE} 字的短块，
 * 每块单独成一条字幕。标点只用来断句、不上屏。时间按字符比例插值。
 */
public final class TrackPost {

    /** 单条字幕最多多少字（约一行，3–7 秒的口语量）。 */
    private static final int MAX_CUE = 40;
    /** 模型词表里的全部标点；断句后从显示文本里去掉。 */
    private static final String PUNCT = "，。！？：；、,.!?;:";
    /** 句内标点：长句优先在这些位置切块。 */
    private static final String SOFT = "，、：,:";

    private TrackPost() { }

    public static JSONArray refine(JSONArray raw) {
        if (raw == null) return new JSONArray();

        /* 1) 展开成字符级时间轴：每个字占它所在碎片时长的一份 */
        StringBuilder sb = new StringBuilder();
        ArrayList<float[]> span = new ArrayList<>();   /* 每字 [起, 止] */
        for (int i = 0; i < raw.length(); i++) {
            JSONObject o = raw.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("t", "").replaceAll("\\s+", "");
            if (t.isEmpty()) continue;
            double s = o.optDouble("s", 0), e = o.optDouble("e", s);
            int n = t.length();
            for (int k = 0; k < n; k++) {
                span.add(new float[]{
                        (float) (s + (e - s) * k / n),
                        (float) (s + (e - s) * (k + 1) / n)});
            }
            sb.append(t);
        }
        String text = sb.toString();
        if (text.isEmpty()) return new JSONArray();

        /* 2) 按句末标点合并成整句（逗号不算句末） */
        ArrayList<int[]> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == ';' || c == '；') {
                sentences.add(new int[]{start, i + 1});
                start = i + 1;
            }
        }
        if (start < text.length()) sentences.add(new int[]{start, text.length()});

        /* 3) 整句再按句内标点切块，每块 ≤ MAX_CUE 字：口语化长句不会一次把十几秒的话全铺出来 */
        JSONArray out = new JSONArray();
        for (int[] se : sentences) {
            int cs = se[0], i = se[0], b = se[1];
            while (i < b) {
                int j = i;
                while (j < b && SOFT.indexOf(text.charAt(j)) < 0) j++;
                int clauseEnd = (j < b) ? j + 1 : b;
                if (clauseEnd - cs > MAX_CUE && i > cs) {
                    emit(out, text, span, cs, i);
                    cs = i;
                }
                i = clauseEnd;
            }
            if (cs < b) emit(out, text, span, cs, b);
        }
        return out;
    }

    /** 吐一条字幕；超过 MAX_CUE 的（一整段没有句内标点）按字数硬切兜底。 */
    private static void emit(JSONArray out, String text, ArrayList<float[]> span, int a, int b) {
        int cs = a;
        while (b - cs > MAX_CUE) {
            emitPiece(out, text, span, cs, cs + MAX_CUE);
            cs += MAX_CUE;
        }
        emitPiece(out, text, span, cs, b);
    }

    private static void emitPiece(JSONArray out, String text, ArrayList<float[]> span, int a, int b) {
        if (b <= a) return;
        String line = stripPunct(text.substring(a, b));
        if (line.isEmpty()) return;
        JSONObject o = new JSONObject();
        try {
            o.put("s", round2(span.get(a)[0])).put("e", round2(span.get(b - 1)[1])).put("t", line);
            out.put(o);
        } catch (Throwable ignored) { }
    }

    private static String stripPunct(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (PUNCT.indexOf(c) < 0) b.append(c);
        }
        return b.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
