package com.fan.shuatiku;


/**
 * DeepSeekHashV1 的回归测试：向量全部由 Python 参考实现（web2api-mini/server.py）产出。
 * 只要有一条对不上，PoW 就永远解不出来，所以这里必须逐字节一致。
 */
public class DeepSeekHashTest {

    private static int pass, fail;

    private static void eq(String name, Object want, Object got) {
        if (want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
            System.out.println("         期望: " + want);
            System.out.println("         实际: " + got);
        }
    }

    private static byte[] bytes(String hex) {
        int n = hex.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    public static void main(String[] args) {
        System.out.println("=== DeepSeekHashV1（对照 Python 参考实现）===");

        // 空串 / 单字节 / 常见串
        eq("空串", "e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3",
                DeepSeekHashV1.hex(new byte[0]));
        eq("a", "910e595ccfd4af1264d04251f7273f7c28badc11e7de64c0e960419f416ed236",
                DeepSeekHashV1.hex("a".getBytes()));
        eq("abc", "f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506",
                DeepSeekHashV1.hex("abc".getBytes()));

        // 真实 PoW 前缀（salt_expireAt_ + answer），这两条就是线上跑过的输入
        eq("pow prefix + 0",
                "57825d04a12db344f2588e1c21c86384b979af32d0d2e0100589240cccb8e270",
                DeepSeekHashV1.hex("43adfc7f0638c86e3b10_1792495763023_0".getBytes()));
        eq("pow prefix + 24423",
                "9119fb512fc86a81c1a4a132e31437b12df5a857f4f59e16b4bdef3005b2bd10",
                DeepSeekHashV1.hex("43adfc7f0638c86e3b10_1792495763023_24423".getBytes()));

        // 多字节 UTF-8：方法名是 getBytes()，默认编码不能影响结果
        eq("中文测试（UTF-8）",
                "91c65cc175fa2f91871c88a04ea55108a00a6fd6d92ce774401fcab97832b884",
                DeepSeekHashV1.hex("中文测试".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // rate=136 的边界：135（不足一块）/ 136（正好一块）/ 137（跨块）/ 272（正好两块）
        eq("135 字节 x",
                "83b37f61450bade766525ba50e2989c301c7a6e1dea2a3b75a7ea76c95d220ab",
                DeepSeekHashV1.hex(rep("x", 135).getBytes()));
        eq("136 字节 x",
                "9c8a9eefd990d3073104fa5a3dd2d090681086778caccfa5b9af53b859752a99",
                DeepSeekHashV1.hex(rep("x", 136).getBytes()));
        eq("137 字节 x",
                "0ea4ef238af5fccabb511b3fb357bb2a4eab514e525d983474631379da418c72",
                DeepSeekHashV1.hex(rep("x", 137).getBytes()));
        eq("272 字节 x",
                "5eef77624bf495e030d3dd82ad81ba7aa98c83e99a8aa9b502c28587641ce4fe",
                DeepSeekHashV1.hex(rep("x", 272).getBytes()));

        // 摘要长度固定 64，且与 bytes(hex) 互逆
        eq("摘要长度", 64, DeepSeekHashV1.hex(bytes("00")).length());
        eq("bytes 往返", "f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506",
                DeepSeekHashV1.hex(bytes("616263")));

        // solve：造一个难度的子集，答案必须能被独立验算出来
        System.out.println("\n=== solve（暴力搜索 + 多线程）===");
        String challenge = DeepSeekHashV1.hex(bytes("616263"));
        byte[] prefix = new byte[0];
        // challenge 对应的是 "abc"，即 answer=97+... 不成立；改为找一个真实可搜的题：
        // 用 prefix="p" 搜 answer=0..2000，取 answer=7 的摘要当目标，验证能搜回来。
        byte[] p2 = "p".getBytes();
        int target = 7;
        String want = DeepSeekHashV1.hex(concat(p2, String.valueOf(target).getBytes()));
        long single = DeepSeekHashV1.solve(p2, want, 2000, 1);
        eq("串行搜回 answer", (long) target, single);
        long multi = DeepSeekHashV1.solve(p2, want, 144000, 8);
        eq("多线程搜回 answer", (long) target, multi);
        eq("搜不到返回 -1", -1L,
                DeepSeekHashV1.solve(p2, challenge, 50, 4));

        // 性能：难度 144000 全窗口串行一遍，估手机构建时的可接受度
        System.out.println("\n=== 吞吐量（决定手机端等待时长）===");
        long t0 = System.nanoTime();
        int n = 20000;
        for (int i = 0; i < n; i++) DeepSeekHashV1.hex(rep("z", 32).getBytes(), i);
        double dt = (System.nanoTime() - t0) / 1e9;
        System.out.printf("  %d 次哈希 %.2fs → %.0f 次/秒%n", n, dt, n / dt);
        System.out.printf("  难度 144000 全窗口 ≈ %.1fs，平均（半窗）≈ %.1fs%n",
                144000 / (n / dt), 72000 / (n / dt));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
