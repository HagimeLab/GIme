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

    /** 启动时清掉上次被打断留下的临时/备份文件，别让它们一直占着地方。 */
    static void cleanup(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".tmp") && !n.endsWith(".bak")) continue;
            // .bak 里是上一版内容，对应的正式文件还在就说明替换已经完成，可以丢
            String base = n.substring(0, n.length() - 4);
            if (n.endsWith(".bak") && !new File(dir, base).exists()) continue;
            f.delete();
        }
    }

    static String safe(String id) {
        return id == null ? "bad" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
