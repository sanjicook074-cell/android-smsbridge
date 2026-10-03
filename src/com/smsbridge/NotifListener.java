package com.smsbridge;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 通知通道 —— 第三条路，而且是「什么都能捞到」的那条。
 *
 * ── 为什么要有它 ──
 * 前面两条路都有各自的盲区：
 *   · SMS_RECEIVED 广播：小米/红米的「免费网络短信」走网络、压根不是运营商短信，
 *     不发这个广播；某些 ROM 还会静默过滤。
 *   · 直读收件箱：只覆盖真正进了 content://sms 的短信。
 *
 * 但**不管哪种消息，短信 App 都会弹一条通知**。读通知这条路把三种
 * 情况一次全覆盖：运营商真短信 / 小米免费网络短信 / RCS(5G消息)。
 *
 * ── 手机端只做搬运，不做判断 ──
 * 这里**不判断"是不是验证码"**，原文（标题 | 正文，带来源包名）直接 POST 到电脑，
 * 由电脑那端统一筛。手机上逻辑越少，出问题时越好查。
 *
 * ── 为什么这条通道最稳 ──
 * 它是**被系统绑定的 Service**：进程被清理后系统会重新拉起，
 * 不像静态广播那样容易受"自启动/省电"管控影响。SmsForwarder 那类
 * 成熟工具在小米上靠的就是这个。
 *
 * ⚠ 注意本节里这个 Service 的 android:permission 是**必需的、正确的**写法：
 *   它的含义是「只有持有 BIND_NOTIFICATION_LISTENER_SERVICE 的调用者才能 bind 我」，
 *   而系统进程持有该权限 —— 这正是我们想要的。
 *   （跟 SmsReceiver 上那个 BROADCAST_SMS 完全不是一回事：
 *     那个管的是"谁能给我发广播"，写上去反而把系统挡在外面。）
 *
 * ★ 老规矩：**一个匿名内部类都不写** —— 这条手工编译链（d8）遇到匿名类会崩，
 *   详见 PostTask.java 顶部注释。
 */
public class NotifListener extends NotificationListenerService {

    /** 去重：同一条通知在 15 秒内只报一次（通知栏会重复 post 更新） */
    private static String lastKey = "";
    private static long   lastAt  = 0L;

    /**
     * 被系统绑上时回调。这一行是「通知使用权真的生效了」的铁证 ——
     * 只要它出现，后面收不到通知就不再是权限问题。
     */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        MsgLog.add(this, PostTask.now() + " ✔ 通知通道已连接（此后手机在后台也能转发）");
        // 必须离开主线程：onListenerConnected 跑在主线程
        new PostTask(this, "【短信桥·通知通道】已连上系统通知，手机在后台也能转发", "notif-connect").start();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            String pkg = sbn.getPackageName();

            // 别把自己的通知再发一遍（防自激）
            if (getPackageName().equals(pkg)) return;

            // 常驻通知（音乐播放器、下载进度、充电动画…）不是消息，且会反复更新
            if (sbn.isOngoing()) return;

            Notification n = sbn.getNotification();
            if (n == null) return;
            Bundle ex = n.extras;
            if (ex == null) return;

            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text  = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            if (text.length() == 0) text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.length() == 0) text = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));

            // 标题正文都空的通知没有信息量（纯图标类）
            if (title.length() == 0 && text.length() == 0) return;

            String body = title + " | " + text;
            if (dup(pkg, body)) {
                MsgLog.add(this, PostTask.now() + "     ↷ 通知重复，已跳过");
                return;
            }

            MsgLog.add(this, PostTask.now() + " [通知·" + tail(pkg) + "] " + PostTask.cut(body, 26));

            // 原文照发，抽验证码的事留在电脑那端
            new PostTask(this, body, "notif:" + pkg).start();

        } catch (Throwable t) {
            // 解析出错也要留痕，否则又是一次"什么都没有发生"的黑盒
            MsgLog.add(this, PostTask.now() + " 通知解析出错：" + t.getClass().getSimpleName());
        }
    }

    /** 同一条通知 15 秒内只处理一次（来源 + 内容相同即视为同一条） */
    private static boolean dup(String pkg, String body) {
        String k = pkg + "\u0000" + body;
        long now = System.currentTimeMillis();
        if (k.equals(lastKey) && (now - lastAt) < 15000L) return true;
        lastKey = k;
        lastAt  = now;
        return false;
    }

    static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    /** com.android.mms → mms（只为了日志里短一点，不维护映射表） */
    static String tail(String pkg) {
        if (pkg == null) return "?";
        int i = pkg.lastIndexOf('.');
        return i < 0 ? pkg : pkg.substring(i + 1);
    }
}
