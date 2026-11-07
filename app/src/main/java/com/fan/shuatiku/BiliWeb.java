package com.fan.shuatiku;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B 站网页接口的只读客户端：收藏夹 + 播放地址。
 *
 * <p>零 Android 依赖（只用 {@link HttpNet} 与 org.json），桌面能直接 javac 跑回归，
 * 与 {@link DeepSeekWeb} 同一套编排。UA 复用 {@link DeepSeekWeb#UA}：那是启动时按
 * 真机型号注入的 Cronet 侧 UA，全站只需要一份「原生出站身份」。
 *
 * <p>2026-11-04 实测出来的三条硬约束，决定了这一层为什么长这样：
 * <ol>
 *   <li>这些接口不校验 wbi 签名，但<b>私有收藏夹没有 SESSDATA 时返回的是一整个 HTML
 *       错误页而不是 JSON</b> —— 「不是 JSON」本身就是登录态失效的指纹，必须单独成一句人话；</li>
 *   <li>播放直链要 {@code Referer} 与浏览器系 {@code User-Agent} <b>两个都带</b>才给
 *       206，缺任何一个 403；</li>
 *   <li>直链带 {@code deadline}、几十分钟就废，所以 URL 只交给 {@link BiliStream}
 *       原生代理，页面永远拿不到它，也拿不到 cookie。</li>
 * </ol>
 */
public final class BiliWeb {

    static final String API = "https://api.bilibili.com";
    /** 取 cookie 用的站点域：CookieManager 按域存，不按接口存。 */
    static final String SITE = "https://www.bilibili.com";
    static final String REFERER = "https://www.bilibili.com/";
    /** 每页条数。上游 ps 上限就是 20，写更大也只会拿到 20。 */
    static final int PS = 20;

    private BiliWeb() { }

    /** 上游拒绝、或我们根本连不上时统一抛这个；message 直接就是给人看的那句。 */
    public static final class ApiError extends Exception {
        public final int code;
        ApiError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /* ==================== 对外动作 ==================== */

    /** 登录态自检：上游给得出 mid 才算登录着（不看本地 cookie 变没变）。 */
    public static JSONObject nav(String cookie) throws Exception {
        JSONObject d = call("/x/web-interface/nav", null, cookie);
        JSONObject o = new JSONObject();
        o.put("mid", d.optLong("mid"));
        o.put("uname", d.optString("uname"));
        o.put("face", https(d.optString("face")));
        return o;
    }

    /**
     * 我创建的收藏夹。
     *
     * <p>{@code id} 才是 resource/list 要的 media_id（= fid×100 + 尾数），
     * 2026-11-04 用三个真实公开夹核对过。上游没有收藏夹时 {@code data} 直接是 null，
     * 不是空数组 —— 这里统一成空表，省得页面判两种形状。
     */
    public static JSONArray folders(String cookie, long mid) throws Exception {
        JSONObject d = call("/x/v3/fav/folder/created/list-all",
                q("up_mid", String.valueOf(mid)), cookie);
        JSONArray out = new JSONArray();
        JSONArray list = d.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null) continue;
            JSONObject o = new JSONObject();
            o.put("mediaId", f.optLong("id"));
            o.put("title", f.optString("title"));
            o.put("count", f.optInt("media_count"));
            out.put(o);
        }
        return out;
    }

    /** 收藏夹里的条目（一页）。返回 {@code {list:[…], has_more:bool}}。 */
    public static JSONObject items(String cookie, long mediaId, int pn) throws Exception {
        JSONObject d = call("/x/v3/fav/resource/list",
                q("media_id", String.valueOf(mediaId), "pn", String.valueOf(pn),
                        "ps", String.valueOf(PS), "keyword", "", "type", "0"), cookie);
        return itemsOf(d);
    }

    /** 稿件的分 P 列表（cid 只在 view 里有，收藏条目本身不带）。 */
    public static JSONObject view(String cookie, String bvid) throws Exception {
        JSONObject d = call("/x/web-interface/view", q("bvid", bvid), cookie);
        JSONObject o = new JSONObject();
        o.put("bvid", d.optString("bvid", bvid));
        o.put("title", d.optString("title"));
        JSONArray pages = d.optJSONArray("pages");
        JSONArray ps = new JSONArray();
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject p = pages.optJSONObject(i);
                if (p == null) continue;
                JSONObject x = new JSONObject();
                x.put("cid", p.optLong("cid"));
                x.put("page", p.optInt("page", i + 1));
                x.put("part", p.optString("part"));
                x.put("duration", p.optInt("duration"));
                ps.put(x);
            }
        }
        o.put("pages", ps);
        return o;
    }

    /**
     * 取播放地址。
     *
     * <p>{@code fnval=1} 是刻意的：只有它给 {@code durl}（音视频合一的单个 mp4）。
     * DASH（fnval=16）把音视频拆成两条流，播之前得自己合成，这一版不做。
     * 清晰度不硬编 1080P —— 能拿到哪档由上游的 {@code accept_quality} 说话。
     */
    public static JSONObject play(String cookie, String bvid, long cid, int qn) throws Exception {
        /* platform=html5 是关键：pc 版的直链会被 CDN 按 cookie/buvid 绑死，换环境直接 403；
           html5 版只认 Referer + UA，交给本机代理边取边播最稳。high_quality 让它别压到 360P。 */
        JSONObject d = call("/x/player/playurl",
                q("bvid", bvid, "cid", String.valueOf(cid), "qn", String.valueOf(qn),
                        "fnval", "1", "fourk", "1", "platform", "html5", "high_quality", "1"), cookie);
        return playOf(d);
    }

    /* ==================== 纯解析（回归打这里） ==================== */

    /**
     * 纯解析方法不声明 throws，而 Android 的 {@code JSONObject.put} 会抛受检的
     * {@code JSONException}（桌面 json-java 里它是运行时异常，所以两边对不上）。
     * 统一走这个兜底：写不进去就当缺字段，页面按空值处理——不让一条视频拖垮整页。
     */
    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception e) {
            /* 忽略：字段缺省即可 */
        }
    }

    /** 收藏条目 → 页面形状。字段名按 2026-11-04 抓到的真实响应核对。 */
    public static JSONObject itemsOf(JSONObject data) {
        JSONArray out = new JSONArray();
        JSONArray medias = data.optJSONArray("medias");
        if (medias != null) {
            for (int i = 0; i < medias.length(); i++) {
                JSONObject m = medias.optJSONObject(i);
                if (m != null) out.put(itemOf(m));
            }
        }
        JSONObject o = new JSONObject();
        put(o, "list", out);
        put(o, "hasMore", data.optBoolean("has_more"));
        return o;
    }

    public static JSONObject itemOf(JSONObject m) {
        JSONObject o = new JSONObject();
        put(o, "bvid", m.optString("bvid"));
        put(o, "aid", m.optLong("id"));
        put(o, "title", m.optString("title"));
        put(o, "cover", https(m.optString("cover")));
        put(o, "duration", m.optInt("duration"));
        put(o, "upper", m.optJSONObject("upper") == null
                ? "" : m.optJSONObject("upper").optString("name"));
        put(o, "favTime", m.optLong("fav_time"));
        put(o, "playable", playableOf(m));
        String note = noteOf(m);
        if (!note.isEmpty()) put(o, "note", note);
        return o;
    }

    /**
     * 能不能播。
     *
     * <p>{@code attr} 的位含义没有可靠来源，所以<b>不逐位解释</b>：非零就标「状态异常」，
     * 真原因交给 playurl 的 code/message 说（见 {@link #noteOf}）。
     * {@code type!=2} 或带 {@code ogv}/{@code season} 的是番剧/影视，走另一套播放接口，
     * 本周期明确不支持。
     */
    public static boolean playableOf(JSONObject m) {
        /* 只认普通 UGC：attr 非零 = 状态异常；type!=2 = 番剧/影视/课程，走另一套播放接口，本周期不做。
           注意：2026-11 的接口里 ogv / season 这两个字段几乎每条都带着（哪怕是个空对象 {}），
           拿「字段存在」当番剧判据会把整个收藏夹误判成不可播——真机就踩过这个坑。 */
        return m.optInt("attr") == 0 && m.optInt("type", 2) == 2;
    }

    /** 不可播时给人看的那句；可播返回空串。 */
    public static String noteOf(JSONObject m) {
        if (m.optInt("attr") != 0) return "这条可能已经失效（被删除、审核中或仅自己可见）";
        if (m.optInt("type", 2) != 2) return "暂不支持番剧/影视";
        return "";
    }

    /** playurl → 页面形状。url 不回页面，只回给原生代理。 */
    public static JSONObject playOf(JSONObject data) {
        JSONObject o = new JSONObject();
        int q = data.optInt("quality");
        put(o, "quality", q);
        put(o, "qualityName", qualityNameOf(data, q));
        put(o, "timelength", data.optLong("timelength"));
        JSONArray durl = data.optJSONArray("durl");
        if (durl == null || durl.length() == 0) {
            put(o, "url", "");
            put(o, "size", 0);
            return o;
        }
        JSONObject first = durl.optJSONObject(0);
        put(o, "url", first == null ? "" : first.optString("url"));
        put(o, "size", first == null ? 0 : first.optLong("size"));
        put(o, "segments", durl.length());
        JSONArray accQ = data.optJSONArray("accept_quality");
        JSONArray accD = data.optJSONArray("accept_description");
        JSONArray accept = new JSONArray();
        if (accQ != null) {
            for (int i = 0; i < accQ.length(); i++) {
                int v = accQ.optInt(i);
                if (v <= 0) continue;
                JSONObject x = new JSONObject();
                put(x, "qn", v);
                put(x, "name", accD != null && i < accD.length() ? accD.optString(i) : (v + "P"));
                accept.put(x);
            }
        }
        put(o, "accept", accept);
        return o;
    }

    /** 档位名直接取上游的 accept_description，避免自己编一张档位表。 */
    public static String qualityNameOf(JSONObject data, int qn) {
        JSONArray accQ = data.optJSONArray("accept_quality");
        JSONArray accD = data.optJSONArray("accept_description");
        if (accQ != null && accD != null) {
            for (int i = 0; i < accQ.length(); i++) {
                if (accQ.optInt(i) == qn && i < accD.length()) return accD.optString(i);
            }
        }
        return qn + "P";
    }

    /**
     * 封面与头像一律升 https。
     *
     * <p>上游回的是 {@code http://i0.hdslb.com/…}，而 {@code res/xml/net_sec.xml}
     * 只对回环放行明文 —— 不改写的话整个列表全是空白格子，且报错都不会有一句。
     */
    public static String https(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("http://")) return "https://" + u.substring("http://".length());
        return u;
    }

    /* ==================== 请求装配 ==================== */

    /** 发一次 GET，返回响应里的 data；data 为 null 时给空对象（调用方按缺字段兜底）。 */
    private static JSONObject call(String path, Map<String, String> query, String cookie)
            throws Exception {
        HttpNet.Conn c = HttpNet.open(new HttpNet.Request(API + path + qs(query),
                "GET", headers(cookie), null, 8000, 15000));
        String raw;
        int http;
        try {
            http = c.code();
            raw = HttpNet.readAll(c.body());
        } finally {
            try { c.close(); } catch (Exception ignored) { }
        }
        if (http != 200) throw new ApiError(-http, "B 站回的是 HTTP " + http + "，稍后再试");
        return dataOf(raw);
    }

    /**
     * 响应体 → data。
     *
     * <p>三类失败分开说，因为处置动作不一样：HTML（登录态失效/风控）要重绑，
     * -101/-352 也要重绑或换时间再试，其余把上游自己的 message 原样端出去。
     */
    public static JSONObject dataOf(String raw) throws ApiError {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new ApiError(-1, "B 站回了个空响应");
        if (s.charAt(0) == '<') {
            throw new ApiError(-1, "B 站返回的是网页而不是接口数据 —— 登录态多半已经失效，请重新绑定");
        }
        JSONObject j;
        try {
            j = new JSONObject(s);
        } catch (Exception e) {
            throw new ApiError(-1, "没看懂 B 站的返回（不是 JSON）");
        }
        int code = j.optInt("code", -1);
        if (code == 0) {
            JSONObject d = j.optJSONObject("data");
            return d == null ? new JSONObject() : d;
        }
        String msg = j.optString("message", "");
        if (code == -101 || code == -100) {
            throw new ApiError(code, "登录态已经失效，请重新绑定哔哩哔哩");
        }
        if (code == -352) {
            throw new ApiError(code, "B 站风控拦了这次请求（-352），过一会儿再试");
        }
        throw new ApiError(code, msg.isEmpty() ? "B 站拒绝了这次请求（" + code + "）"
                : msg + "（" + code + "）");
    }

    static Map<String, String> headers(String cookie) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", DeepSeekWeb.UA);
        h.put("Referer", REFERER);
        h.put("Accept", "application/json, text/plain, */*");
        if (cookie != null && !cookie.trim().isEmpty()) h.put("Cookie", cookie.trim());
        return h;
    }

    private static String qs(Map<String, String> q) {
        if (q == null || q.isEmpty()) return "";
        StringBuilder b = new StringBuilder("?");
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (b.length() > 1) b.append('&');
            b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return b.toString();
    }

    /** 只编 URL 里真正需要编的东西；bvid/fid 由 BridgeGuard 先过形状，这里不放开任意字符。 */
    private static String enc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') b.append(c);
            else if (c == ' ') b.append("+");
            else {
                byte[] by = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (int k = 0; k < by.length; k++) {
                    b.append('%');
                    b.append(Integer.toHexString((by[k] & 0xff) | 0x100).substring(1).toUpperCase());
                }
            }
        }
        return b.toString();
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
