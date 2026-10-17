import com.fan.shuatiku.Ver;

/** Ver 的桌面回归测试：不依赖 Android，javac/java 直接跑。 */
public class VerTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        check("1.0.1 比 1.0.0 新", Ver.isNewer("1.0.1", "1.0.0"), true);
        check("同版本不算新", Ver.isNewer("1.0.1", "1.0.1"), false);
        check("旧版本不算新", Ver.isNewer("1.0.0", "1.0.1"), false);
        check("按段数值比较（1.2.10 > 1.2.9）", Ver.isNewer("1.2.10", "1.2.9"), true);
        check("容忍 v 前缀", Ver.isNewer("v1.0.1", "1.0.0"), true);
        check("容忍预发布尾巴", Ver.isNewer("1.0.1-beta", "1.0.0"), true);
        check("短版本补零（1.1 > 1.0.9）", Ver.isNewer("1.1", "1.0.9"), true);
        check("空字符串不崩", Ver.isNewer("", "1.0.0"), false);
        check("null 不崩", Ver.isNewer(null, "1.0.0"), false);

        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    private static void check(String name, boolean actual, boolean expect) {
        if (actual == expect) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "（期望 " + expect + "，实际 " + actual + "）");
        }
    }
}

