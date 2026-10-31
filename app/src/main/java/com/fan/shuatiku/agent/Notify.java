package com.fan.shuatiku.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 提醒通知的落地。
 *
 * <p>移植自 agent 项目的 Notify，改了两处：
 * 渠道 id 换成 GIme 自己的（原来叫 "agent"，两个 App 装在一台机器上会串），
 * 点通知回到 GIme 的方案页而不是首页。
 */
final class Notify {

    /** 通知渠道 id。改了它等于换一条新渠道，用户之前的静音设置会失效，别随手动。 */
    static final String CHANNEL = "gime-plan";

    private Notify() { }

    /** 建渠道。SDK < 26 没有渠道概念，直接返回。重复调用是安全的。 */
    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "方案与提醒",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("方案更新、每日提醒与助理通知");
        nm.createNotificationChannel(ch);
    }

    /** 弹一条通知。id 用不同值可以并存，同值会覆盖。 */
    static void show(Context c, int id, String title, String body) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        channel(c);

        Intent i = new Intent(c, com.fan.shuatiku.MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, id, i, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title == null ? "" : title)
                .setContentText(body == null ? "" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xff111113);
        try {
            nm.notify(id, b.build());
        } catch (Throwable ignored) {
            /* 通知权限被拒时这里会抛，提醒失败不该把 App 带崩 */
        }
    }
}
