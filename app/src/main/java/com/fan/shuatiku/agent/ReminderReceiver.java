package com.fan.shuatiku.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 闹钟到点、或者开机后系统广播进来，都在这里数一遍「今天还剩几项」再决定要不要响。
 *
 * <p>移植自 agent 项目的 ReminderReceiver。改动：
 * 计划库路径从 {@code getFilesDir()} 现算（agent 版把 Store 的构造塞进 Context，
 * 这里 PlanStore 只吃 File，方便桌面单测）；
 * 另外加了一条「开机重挂」分支 —— agent 原版漏了，重启手机后闹钟会永远不再响。
 */
public class ReminderReceiver extends BroadcastReceiver {

    /** 早上/晚上，取 {@link Reminder#MORNING} / {@link Reminder#EVENING}。 */
    public static final String EXTRA_SLOT = "slot";

    /** 开机广播：闹钟在重启后会被系统清空，必须重新挂一遍。 */
    public static final String ACTION_BOOT = "android.intent.action.BOOT_COMPLETED";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (c == null) return;
        String act = intent == null ? null : intent.getAction();

        /* 开机后第一件事就是把两个时段重新排上，不然从此再也收不到提醒 */
        if (ACTION_BOOT.equals(act)) {
            Reminder.arm(c);
            return;
        }

        int slot = intent == null ? Reminder.MORNING
                : intent.getIntExtra(EXTRA_SLOT, Reminder.MORNING);

        String title;
        String body;
        try {
            PlanStore store = new PlanStore(new File(c.getFilesDir(), "plans"));
            JSONArray plans = store.list();
            int undone = 0;
            StringBuilder first = new StringBuilder();
            for (int i = 0; i < plans.length(); i++) {
                JSONObject p = plans.optJSONObject(i);
                if (p == null) continue;
                /* 关掉的、或者没开提醒的方案不打扰 */
                if (!p.optBoolean("enabled", true)) continue;
                if (!p.optBoolean("remind", false)) continue;
                JSONArray today = p.optJSONArray("today");
                if (today == null) continue;
                for (int k = 0; k < today.length(); k++) {
                    JSONObject it = today.optJSONObject(k);
                    if (it == null || it.optBoolean("done", false)) continue;
                    undone++;
                    /* 通知正文只放得下两行，攒到 48 字就够用户判断是什么事了 */
                    if (first.length() < 48) {
                        if (first.length() > 0) first.append("、");
                        first.append(it.optString("text", ""));
                    }
                }
            }
            if (undone == 0) return;
            title = slot == Reminder.EVENING
                    ? "还有 " + undone + " 项没完成"
                    : "今天有 " + undone + " 项任务";
            body = first.toString() + (undone > 2 ? " 等" : "");
        } catch (Throwable t) {
            return;
        }
        Notify.show(c, 100 + slot, title, body);
    }
}
