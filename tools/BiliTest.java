package com.fan.shuatiku;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * BiliWeb 解析层的回归测试（桌面 javac 直跑，不起模拟器）。
 *
 * <p>钉住的全是 2026-11-04 实测拿到的形状，不是想象出来的接口文档：
 * 字段名、data 为 null、HTML 错误页、durl 缺失、档位名来源。
 * 这些一旦漂了，页面表现就是「整列空白」或「点开的视频黑屏」这类最难查的毛病。
 */
public class BiliTest {

    private static int pass = 0;
    private static int fail = 0;

    /** 真实抓下来的公开收藏夹响应（标题换成 ASCII，避免编码噪声掩盖真正的断言）。 */
    private static final String FAV_BODY = "{"
            + "\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
            + "\"info\":{\"media_id\":59563299,\"title\":\"默认三连哄骗夹\",\"attr\":20},"
            + "\"has_more\":true,"
            + "\"medias\":["
            + "{\"id\":287424721,\"bvid\":\"BV1Ef4y1B79w\",\"type\":2,"
            + "\"title\":\"A title\",\"cover\":\"http://i0.hdslb.com/bfs/archive/a.jpg\","
            + "\"duration\":160,\"fav_time\":1793424583,\"attr\":0,"
            + "\"ogv\":{},\"season\":{},"
            + "\"upper\":{\"mid\":4007862,\"name\":\"o0Ling0o\",\"face\":\"http://i0.hdslb.com/bfs/face/b.jpg\"},"
            + "\"cnt_info\":{\"collect\":201,\"play\":17014}},\n"
            + "{\"id\":1,\"bvid\":\"BV1dead\",\"type\":2,\"title\":\"Gone\","
            + "\"cover\":\"\",\"duration\":0,\"attr\":1,\"upper\":{\"name\":\"x\"}},\n"
            + "{\"id\":2,\"bvid\":\"BV1ogv\",\"type\":4,\"title\":\"Bangumi\","
            + "\"cover\":\"\",\"duration\":0,\"attr\":0,\"upper\":{\"name\":\"y\"},\"season\":{\"season_id\":11}},"
            + "]}}";

    public static void main(String[] args) throws Exception {
        /* ---- 1. 收藏条目解析 ---- */
        JSONObject data = BiliWeb.dataOf(FAV_BODY);
        JSONObject items = BiliWeb.itemsOf(data);
        JSONArray list = items.getJSONArray("list");
        checkBool("三条条目全收到（含不可播的）", list.length() == 3);
        checkBool("has_more 透传成布尔", items.getBoolean("hasMore"));

        JSONObject a = list.getJSONObject(0);
        checkStr("bvid 取对", a.getString("bvid"), "BV1Ef4y1B79w");
        checkStr("aid 取对（medias.id）", String.valueOf(a.getLong("aid")), "287424721");
        checkStr("UP 主从 upper.name 取", a.getString("upper"), "o0Ling0o");
        checkStr("收藏时间用 fav_time", String.valueOf(a.getLong("favTime")), "1793424583");
        checkBool("可播条目不写 note", !a.has("note"));
        checkBool("可播性为真", a.getBoolean("playable"));

        /* ---- 2. 封面必须升 https ---- */
        checkStr("封面 http 改写 https",
                a.getString("cover"), "https://i0.hdslb.com/bfs/archive/a.jpg");
        checkStr("没封面给空串而不是 null", list.getJSONObject(1).getString("cover"), "");
        checkStr("https 原样不动", BiliWeb.https("https://x/y.jpg"), "https://x/y.jpg");

        /* ---- 3. 不可播的三种成因分开说 ---- */
        JSONObject dead = list.getJSONObject(1);
        checkBool("attr 非零判不可播", !dead.getBoolean("playable"));
        checkBool("attr 非零给的是「失效」这句", dead.getString("note").indexOf("失效") >= 0);
        JSONObject ogv = list.getJSONObject(2);
        checkBool("带 season 判不可播", !ogv.getBoolean("playable"));
        checkStr("番剧说「暂不支持番剧/影视」", ogv.getString("note"), "暂不支持番剧/影视");

        /* ---- 4. 三类失败响应的分法 ---- */
        String html = "<!DOCTYPE HTML><html><head><title>\u51fa\u9519\u5566! - bilibili.com</title></head></html>";
        BiliWeb.ApiError e1 = errOf(html);
        checkBool("HTML 错误页抛 ApiError", e1 != null);
        checkBool("HTML 归因写登录态失效", e1.getMessage().indexOf("\u767b\u5f55\u6001") >= 0);
        BiliWeb.ApiError e2 = errOf("{\"code\":-101,\"message\":\"\u672a\u767b\u5165\"}");
        checkBool("-101 让人重新绑定", e2.getMessage().indexOf("\u91cd\u65b0\u7ed1\u5b9a") >= 0);
        BiliWeb.ApiError e3 = errOf("{\"code\":-352,\"message\":\"-352\"}");
        checkBool("-352 报风控且带上码", e3.getMessage().indexOf("-352") >= 0);
        BiliWeb.ApiError e4 = errOf("{\"code\":-10403,\"message\":\"\u6743\u9650\u4e0d\u591f\"}");
        checkBool("其它码原样端出上游的话", e4.getMessage().startsWith("\u6743\u9650\u4e0d\u591f"));
        checkBool("上游码号也带在后面", e4.getMessage().indexOf("-10403") >= 0);
        checkBool("空响应也算失败", errOf("") != null);

        /* data 为 null（没有收藏夹时上游给的是 null，不是空对象）*/
        JSONObject empty = BiliWeb.dataOf("{\"code\":0,\"message\":\"OK\",\"data\":null}");
        checkBool("data:null 兜成空对象不抛", empty.length() == 0);
        checkBool("data:null 解析成空列表", BiliWeb.itemsOf(empty).getJSONArray("list").length() == 0);
        checkBool("folders 遇 null 给空表",
                BiliWeb.itemsOf(new JSONObject("{\"list\":null}")).getJSONArray("list").length() == 0);

        /* ---- 5. playurl ---- */
        JSONObject play = BiliWeb.playOf(new JSONObject("{"
                + "\"quality\":16,\"format\":\"mp4\",\"timelength\":212394,"
                + "\"accept_quality\":[64,16],\"accept_description\":[\"\u9ad8\u6e05 720P\",\"\u6d41\u7545 360P\"],"
                + "\"durl\":[{\"order\":1,\"size\":10516124,\"url\":\"https://cdn/x.mp4?deadline=1\"}]}"));
        checkStr("取到 durl 直链", play.getString("url"), "https://cdn/x.mp4?deadline=1");
        checkStr("档位名直接来自上游描述", play.getString("qualityName"), "\u6d41\u7545 360P");
        checkStr("换档位给换一档的名字",
                BiliWeb.qualityNameOf(new JSONObject("{\"accept_quality\":[64,16],"
                        + "\"accept_description\":[\"\u9ad8\u6e05 720P\",\"\u6d41\u7545 360P\"]}"), 64),
                "\u9ad8\u6e05 720P");
        checkStr("实际命中档位", String.valueOf(play.getInt("quality")), "16");
        checkStr("档位名找不到时退回数字",
                BiliWeb.qualityNameOf(new JSONObject("{\"accept_quality\":[64],"
                        + "\"accept_description\":[\"HD\"]}"), 16), "16P");

        JSONObject noDurl = BiliWeb.playOf(new JSONObject("{\"quality\":16,\"durl\":null}"));
        checkStr("没有 durl 给空串（不抛）", noDurl.getString("url"), "");
        checkBool("没有 durl 时 size 为 0", noDurl.getInt("size") == 0);
        checkBool("accept 列表按 qn/name 成形",
                play.getJSONArray("accept").getJSONObject(0).getString("name").indexOf("720") >= 0);

        /* ---- 6. 请求头：缺一个就是 403 ---- */
        Map<String, String> h = BiliWeb.headers("SESSDATA=abc; buvid3=xyz");
        checkStr("带 Referer", h.get("Referer"), "https://www.bilibili.com/");
        checkBool("UA 与原生出站身份同源", h.get("User-Agent").equals(DeepSeekWeb.UA));
        checkStr("整串 cookie 原样带上", h.get("Cookie"), "SESSDATA=abc; buvid3=xyz");
        checkBool("没 cookie 时不发 Cookie 头", !BiliWeb.headers("  ").containsKey("Cookie"));

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    /** 期望 dataOf 抛错时把异常拿回来，不期望它打印一遍栈。 */
    private static BiliWeb.ApiError errOf(String body) {
        try {
            BiliWeb.dataOf(body);
            return null;
        } catch (BiliWeb.ApiError e) {
            return e;
        } catch (Exception e) {
            return new BiliWeb.ApiError(-999, e.toString());
        }
    }

    private static void checkBool(String name, boolean actual) {
        if (actual) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    private static void checkStr(String name, String actual, String expect) {
        if (expect.equals(actual)) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）"); }
    }
}
