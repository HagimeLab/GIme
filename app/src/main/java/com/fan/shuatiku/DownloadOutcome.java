package com.fan.shuatiku;

/**
 * 下载器状态 → 界面动作的判定。
 *
 * 单独成类是因为它必须能在桌面 javac 上被测试：这条规则一旦退化成
 * 「未知状态先等等看」，界面就会永远停在 0%，用户只会觉得"点了没反应"。
 * 历史事故：DownloadManager 的 WAITING_TO_RETRY(194) 落进了"还在下"分支。
 */
public final class DownloadOutcome {

    /** DownloadManager.STATUS_WAITING_TO_RETRY，框架里是 @hide 常量。 */
    static final int WAITING_TO_RETRY = 194;

    static final String PROGRESS = "progress";
    static final String ERROR = "error";
    static final String INSTALL = "install";
    static final String WAIT = "wait";

    private DownloadOutcome() {
    }

    /**
     * @param status DownloadManager.COLUMN_STATUS 的取值
     * @return PROGRESS 继续显示进度 / ERROR 上报失败 / INSTALL 拉起安装 / WAIT 暂不动作
     */
    public static String of(int status) {
        switch (status) {
            case 1:   // STATUS_PENDING
            case 2:   // STATUS_RUNNING
                return PROGRESS;
            case WAITING_TO_RETRY:
            case 4:   // STATUS_PAUSED
            case 16:  // STATUS_FAILED
                return ERROR;
            case 8:   // STATUS_SUCCESSFUL
                return INSTALL;
            default:
                return WAIT;
        }
    }
}
