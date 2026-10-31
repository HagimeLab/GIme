import com.fan.shuatiku.agent.MemoryStore;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** MemoryStore：画像/摘要读写、重开恢复、清除、坏文件不崩。 */
public class MemoryStoreTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        ok(name, cond, null);
    }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : "  (" + detail + ")"));
        }
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("gime-memory").toFile();
        File dir = new File(root, "memory");
        dir.mkdirs();

        System.out.println();
        System.out.println("-- 读写与重开 --");
        MemoryStore s = new MemoryStore(dir);
        JSONObject empty = new JSONObject(s.snapshot());
        ok("新库 profile/session 都是空对象",
                empty.getJSONObject("profile").length() == 0
                        && empty.getJSONObject("session").length() == 0);
        JSONObject p = new JSONObject();
        p.put("weakest", "单选题");
        p.put("rate7", 71);
        JSONObject sess = new JSONObject();
        sess.put("summary", "前 30 条聊过错题与计划");
        sess.put("covered", 30);
        ok("保存成功", s.save(p, sess));
        MemoryStore again = new MemoryStore(dir);
        JSONObject got = new JSONObject(again.snapshot());
        ok("重开后画像还在", "单选题".equals(got.getJSONObject("profile").optString("weakest")));
        ok("重开后摘要还在", got.getJSONObject("session").optInt("covered") == 30);

        System.out.println();
        System.out.println("-- 清除 --");
        again.clear();
        MemoryStore after = new MemoryStore(dir);
        JSONObject cleared = new JSONObject(after.snapshot());
        ok("清除后为空", cleared.getJSONObject("profile").length() == 0
                && cleared.getJSONObject("session").length() == 0);
        ok("清除后文件不在了", !new File(dir, MemoryStore.FILE).exists());

        System.out.println();
        System.out.println("-- 坏文件不崩 --");
        File f = new File(dir, MemoryStore.FILE);
        Files.write(f.toPath(), "{half".getBytes(StandardCharsets.UTF_8));
        MemoryStore bad = new MemoryStore(dir);
        ok("坏 JSON 不抛异常且保持空", new JSONObject(bad.snapshot())
                .getJSONObject("profile").length() == 0);

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }
}
