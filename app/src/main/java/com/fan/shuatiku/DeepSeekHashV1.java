package com.fan.shuatiku;

/**
 * DeepSeekHashV1 —— DeepSeek 网页版 PoW 用的哈希。
 *
 * <p>它是 Keccak-f[1600] 的定制版：标准 SHA3-256 跑 24 轮（r = 0..23），这里只跑
 * 23 轮并跳过 r=0、从 RC[1] 开始。所以 {@code MessageDigest.getInstance("SHA3-256")}
 * 算出来的值永远对不上，必须自己实现。参数与 SHA3-256 相同：rate=136 字节、
 * 填充域 0x06、输出 32 字节。
 *
 * <p>纯 Java 实现（无 JNI），因为 PoW 是每个请求都必须过的门槛：手机端要能算得动，
 * 靠的是多线程暴力搜索。
 */
public final class DeepSeekHashV1 {

    private DeepSeekHashV1() {}

    /** 轮常量：只用 RC[1..23]，RC[0] 被定制版刻意跳过。 */
    private static final long[] RC = {
            0x1L, 0x8082L, 0x800000000000808aL, 0x8000000080008000L, 0x808bL,
            0x80000001L, 0x8000000080008081L, 0x8000000000008009L, 0x8aL, 0x88L,
            0x80008009L, 0x8000000aL, 0x8000808bL, 0x800000000000008bL, 0x8000000000008089L,
            0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L, 0x800aL,
            0x800000008000000aL, 0x8000000080008081L, 0x8000000000008080L, 0x80000001L,
            0x8000000080008008L
    };

    /** rho 的旋转量，按 lane 次序 L = x + 5*y 展平。 */
    private static final int[] RHO_L = new int[25];
    /** pi 的目标 lane 下标，同样按 L = x + 5*y 展平。 */
    private static final int[] PI_L = new int[25];

    private static final int[][] RHO_OFF = {
            {0, 36, 3, 41, 18}, {1, 44, 10, 45, 2}, {62, 6, 43, 15, 61},
            {28, 55, 25, 21, 56}, {27, 20, 39, 8, 14}
    };

    static {
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                int l = x + 5 * y;
                RHO_L[l] = RHO_OFF[x][y];
                PI_L[l] = y + 5 * ((2 * x + 3 * y) % 5);
            }
        }
    }

    private static final int RATE = 136;   // 1088 bit：rate 部分，每次吸收 136 字节

    /** 吸收消息后再跑 23 轮置换，挤出 32 字节摘要。 */
    public static byte[] hash(byte[] msg) {
        long[] a = new long[25];
        absorb(msg, a);
        // squeeze：前 4 个 lane 小端拼起来就是 32 字节摘要
        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            long v = a[i];
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (v >>> (8 * b));
            }
        }
        return out;
    }

    /** 十六进制小写摘要，长度固定 64 字符，用于和 challenge 字符串直接比较。 */
    public static String hex(byte[] msg) {
        return toHex(hash(msg));
    }

    private static String toHex(byte[] d) {
        char[] s = new char[64];
        for (int i = 0; i < 32; i++) {
            int v = d[i] & 0xff;
            s[i * 2] = HEX[v >>> 4];
            s[i * 2 + 1] = HEX[v & 0xf];
        }
        return new String(s);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 吸收 + 填充：把消息按 rate 分块 XOR 进状态，每块之后跑一次置换。
     *
     * <p>填充与 SHA3 一致：先补 0x06，再补 0，最后一字节 XOR 0x80（pad10*1）。
     */
    private static void absorb(byte[] msg, long[] a) {
        int len = msg.length;
        // 需要填充到的总长度：向上取整到 rate 的整数倍（至少一块）
        int total = ((len + 1 + RATE - 1) / RATE) * RATE;
        byte[] buf = new byte[total];
        System.arraycopy(msg, 0, buf, 0, len);
        buf[len] ^= 0x06;                       // 域分隔符起始位
        buf[total - 1] ^= (byte) 0x80;          // pad10*1 的末尾 1

        for (int off = 0; off < total; off += RATE) {
            // 每块拆成 17 个 64 位小端 lane，XOR 进状态
            for (int i = 0; i < RATE / 8; i++) {
                int p = off + i * 8;
                long v = ((long) (buf[p] & 0xff))
                        | (((long) (buf[p + 1] & 0xff)) << 8)
                        | (((long) (buf[p + 2] & 0xff)) << 16)
                        | (((long) (buf[p + 3] & 0xff)) << 24)
                        | (((long) (buf[p + 4] & 0xff)) << 32)
                        | (((long) (buf[p + 5] & 0xff)) << 40)
                        | (((long) (buf[p + 6] & 0xff)) << 48)
                        | (((long) (buf[p + 7] & 0xff)) << 56);
                a[i] ^= v;
            }
            keccakF23(a);
        }
    }

    /**
     * Keccak-f[1600]，只跑 r = 1..23（跳过第 0 轮）。就地修改状态。
     *
     * <p>顺序 θ ρπ χ ι；其中 ρ 与 π 合并成一次「旋转 + 换位」写入临时数组 b。
     */
    private static void keccakF23(long[] a) {
        final long[] b = new long[25];
        for (int r = 1; r < 24; r++) {
            // ── θ：5 列的奇偶校验，再用相邻列旋 1 位混入本列 ──
            long c0 = a[0] ^ a[5] ^ a[10] ^ a[15] ^ a[20];
            long c1 = a[1] ^ a[6] ^ a[11] ^ a[16] ^ a[21];
            long c2 = a[2] ^ a[7] ^ a[12] ^ a[17] ^ a[22];
            long c3 = a[3] ^ a[8] ^ a[13] ^ a[18] ^ a[23];
            long c4 = a[4] ^ a[9] ^ a[14] ^ a[19] ^ a[24];
            long d0 = c4 ^ Long.rotateLeft(c1, 1);
            long d1 = c0 ^ Long.rotateLeft(c2, 1);
            long d2 = c1 ^ Long.rotateLeft(c3, 1);
            long d3 = c2 ^ Long.rotateLeft(c4, 1);
            long d4 = c3 ^ Long.rotateLeft(c0, 1);
            for (int y = 0; y < 5; y++) {
                a[0 + 5 * y] ^= d0;
                a[1 + 5 * y] ^= d1;
                a[2 + 5 * y] ^= d2;
                a[3 + 5 * y] ^= d3;
                a[4 + 5 * y] ^= d4;
            }
            // ── ρ + π：b[PI[l]] = rol(a[l], RHO[l]) ──
            for (int l = 0; l < 25; l++) {
                int n = RHO_L[l];
                b[PI_L[l]] = n == 0 ? a[l] : Long.rotateLeft(a[l], n);
            }
            // ── χ：唯一的非线性步 ──
            for (int y = 0; y < 5; y++) {
                int o = 5 * y;
                long a0 = b[o], a1 = b[o + 1], a2 = b[o + 2], a3 = b[o + 3], a4 = b[o + 4];
                a[o] = a0 ^ (~a1 & a2);
                a[o + 1] = a1 ^ (~a2 & a3);
                a[o + 2] = a2 ^ (~a3 & a4);
                a[o + 3] = a3 ^ (~a4 & a0);
                a[o + 4] = a4 ^ (~a0 & a1);
            }
            // ── ι：轮常量只改 lane[0] ──
            a[0] ^= RC[r];
        }
    }

    /**
     * 在 [0, difficulty) 里找一个 i，使 hash(prefix + i) == challenge。
     *
     * <p>手机端的关键路径。纯 Java 单线程大约每秒几十万次，难度上限 144000 时
     * 最坏一秒上下、平均只要一半。多线程分段并行，谁先命中就返回。
     *
     * @param prefix    {@code f"{salt}_{expire_at}_"} 的字节
     * @param challenge 64 字符的小写十六进制摘要
     * @param workers   并行线程数；{@code <=1} 时串行
     * @return 命中的 answer；窗口内无解返回 -1
     */
    public static long solve(byte[] prefix, String challenge, int difficulty, int workers) {
        if (workers <= 1 || difficulty < 4096) {
            return solveRange(prefix, challenge, 0, difficulty, 1);
        }
        final int n = Math.min(workers, Math.max(1, difficulty / 4096));
        final long[] found = {-1};
        Thread[] ts = new Thread[n];
        for (int w = 0; w < n; w++) {
            final int start = (int) ((long) difficulty * w / n);
            final int stop = (int) ((long) difficulty * (w + 1) / n);
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    for (int i = start; i < stop; i++) {
                        if (found[0] >= 0) return;
                        if (hex(prefix, i).equals(challenge)) {
                            synchronized (found) {
                                if (found[0] < 0) found[0] = i;
                            }
                            return;
                        }
                    }
                }
            }, "ds-pow-" + w);
            t.setDaemon(true);
            ts[w] = t;
            t.start();
        }
        for (Thread t : ts) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return found[0];
    }

    /** 串行版本（便于测试与低难度场景）。 */
    public static long solveRange(byte[] prefix, String challenge, int from, int to, int step) {
        for (int i = from; i < to; i += step) {
            if (hex(prefix, i).equals(challenge)) return i;
        }
        return -1;
    }

    /**
     * 拼 {@code prefix + 十进制(i)} 后求摘要。
     *
     * <p>单独抽出来是为了避开拼接字符串再转字节的临时对象开销——这是 PoW 最热的路径。
     */
    public static String hex(byte[] prefix, int i) {
        byte[] digit = new byte[12];
        int n = 0;
        if (i == 0) {
            digit[n++] = '0';
        } else {
            int v = i;
            while (v > 0) {
                digit[n++] = (byte) ('0' + (v % 10));
                v /= 10;
            }
        }
        byte[] msg = new byte[prefix.length + n];
        System.arraycopy(prefix, 0, msg, 0, prefix.length);
        for (int k = 0; k < n; k++) msg[prefix.length + k] = digit[n - 1 - k];
        return toHex(hash(msg));
    }
}
