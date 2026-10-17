package com.fan.shuatiku;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 题库文件的安全替换。
 *
 * 抽成不依赖 Android 的纯类有两个原因：一是这些路径要能在桌面 javac 直接跑测试，
 * 二是「失败时保留旧文件」这件事必须能被注入失败来验证——光看代码看不出顺序错。
 */
public final class BankStore {

    /** 把 from 挪成 to。抽成接口只为测试能注入失败。 */
    interface Mover {
        boolean move(File from, File to);
    }

    private static final Mover REAL = new Mover() {
        @Override public boolean move(File from, File to) { return from.renameTo(to); }
    };

    private BankStore() {
    }

    static boolean save(File dir, String id, byte[] bytes) {
        return save(dir, id, bytes, REAL);
    }

    /**
     * 先整份写进临时文件并落盘校验，再把旧文件挪成 .bak、新文件顶上；
     * 任何一步失败都回滚到旧文件。**旧文件绝不先删**——先删再 rename，
     * rename 一旦失败题库就没了。
     *
     * @return true 表示正式文件已经是新内容
     */
    static boolean save(File dir, String id, byte[] bytes, Mover mover) {
        String safe = safe(id);
        File target = new File(dir, safe + ".json");
        File tmp = new File(dir, safe + ".json.tmp");
        File bak = new File(dir, safe + ".json.bak");
        boolean hadOld = target.exists();
        try {
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(bytes);
                os.flush();
                os.getFD().sync();      // 落到磁盘再往下走，别让进程被杀时留下半截文件
            } finally {
                os.close();
            }
            if (tmp.length() != bytes.length) return false;

            if (hadOld) {
                if (bak.exists() && !bak.delete()) return false;
                if (!mover.move(target, bak)) return false;   // 挪走而不是删掉
            }
            if (!mover.move(tmp, target)) {
                if (hadOld) mover.move(bak, target);          // 新文件没顶上，把旧的放回去
                return false;
            }
            if (target.length() != bytes.length) {
                if (hadOld) {
                    target.delete();
                    mover.move(bak, target);
                }
                return false;
            }
            if (bak.exists()) bak.delete();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /**
     * 启动时的崩溃恢复。save() 的替换是「旧文件挪成 .bak → 新文件顶上 → 删 .bak」，
     * 进程可能死在中间任何一步，所以这里按落盘状态决定谁说了算：
     *
     *   .json 在、.bak 也在  → 替换已完成，.bak 是上一版，删掉
     *   .json 不在、.bak 在  → 死在「挪走旧文件」和「新文件顶上」之间：
     *                          .bak 是唯一完整副本，必须还原成 .json，
     *                          否则 loadBanks() 只认 *.json，题库会凭空消失
     *   .tmp                 → 写了一半的临时文件，永远不作数，直接删
     */
    static void cleanup(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        // 先还原孤儿 .bak：这是唯一会丢数据的阶段，必须排在删 .tmp 之前
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".bak")) continue;
            File target = new File(dir, n.substring(0, n.length() - 4));
            if (target.exists()) {
                f.delete();               // 替换已落定，旧版可以丢
            } else {
                f.renameTo(target);       // 把唯一完整副本放回正式位置
            }
        }

        // 再清临时文件：它们要么是没写完的半成品，要么已经被 rename 走了
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) f.delete();
        }
    }

    static String safe(String id) {
        return id == null ? "bad" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
