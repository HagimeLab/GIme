package com.fan.shuatiku.agent;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * 每日提醒的两个时段。
 *
 * <p>移植自 agent 项目的 Reminder，但只留「什么时候响」这一件事：
 * 到点后由 {@link ReminderReceiver} 自己去 PlanStore 里数今天还剩几项，
 * 不把计划内容提前塞进 Intent —— 计划随时会变，闹钟不该跟着改。
 *
 * <p>用 setInexactRepeating 而不是 setExactAndAllowWhileIdle：
 * 精确闹钟在 Android 12+ 要额外申请 SCHEDULE_EXACT_ALARM，
 * 一个刷题提醒不值得让用户去系统设置里点授权，交给系统合并省电即可。
 */
public final class Reminder {

    /** 早上这次：今天要做什么。 */
    public static final int MORNING = 1;
    /** 晚上这次：今天还剩多少没做。 */
    public static final int EVENING = 2;

    private Reminder() { }

    /** 重挂两个时段的闹钟。重复调用是安全的（FLAG_UPDATE_CURRENT 覆盖旧的）。 */
    public static void arm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        armAt(am, c, 8, 30, MORNING);
        armAt(am, c, 21, 0, EVENING);
    }

    /** 取消两个时段的闹钟（用户在设置里关掉提醒时用）。 */
    public static void disarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(c, MORNING));
        am.cancel(pending(c, EVENING));
    }

    private static void armAt(AlarmManager am, Context c, int hour, int minute, int slot) {
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        /* 今天这个点已经过了就排到明天，否则 setRepeating 会立刻补一次 */
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        try {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pending(c, slot));
        } catch (Throwable ignored) {
            /* 有的 ROM 省电策略下会直接抛，提醒失败不该把 App 带崩 */
        }
    }

    private static PendingIntent pending(Context c, int slot) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.putExtra(ReminderReceiver.EXTRA_SLOT, slot);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, slot, i, flags);
    }
}
