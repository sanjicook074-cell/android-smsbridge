package com.smsbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.widget.Toast;

/**
 * 收到短信 → 原文交给 PostTask 发到电脑。
 *
 * 在 AndroidManifest 里静态注册，所以 App 没被打开时也能收到。
 * SMS_RECEIVED 在 Android 8.0 隐式广播限制的豁免名单里，静态注册有效。
 *
 * ⚠ manifest 里**不能**给这个 receiver 加
 *   android:permission="android.permission.BROADCAST_SMS" ——
 *   那是「发送方必须持有该权限」的意思，而它是 signature 级权限，
 *   厂商 ROM 发短信广播的进程未必持有，于是广播被静默过滤、一点痕迹都不留。
 *   详见 AndroidManifest.xml 里的注释（这是 2026-10-03 查了很久的坑）。
 *
 * 这里**不做任何解析**：抽验证码的逻辑留在电脑那端（sms_hub.mjs）。
 * 手机上越少逻辑，出问题时越好查。
 *
 * ★ 但诊断信息要留足：广播到没到、pdu 解没解开、转发成没成，
 *   每一层失败都往 MsgLog 里记一笔 —— 否则出问题时全是黑盒。
 */
public class SmsReceiver extends BroadcastReceiver {

    /** 去重：静态注册 + 前台动态注册会同时收到同一条广播，别报两次 */
    private static String lastKey = "";
    private static long   lastAt  = 0L;

    @Override
    public void onReceive(Context ctx, Intent it) {
        Context app = ctx.getApplicationContext();
        String  at  = PostTask.now();
        String  action = (it == null) ? "(intent=null)" : String.valueOf(it.getAction());

        // ★★ 第一件事就是记账，**在任何解析之前**。
        //    只要广播真的到了本 App，这一行必定出现 —— 哪怕后面 pdu 解不开、
        //    哪怕网络发不出去。这是"广播到底到没到"唯一的硬证据，
        //    也是把「ROM 拦截」和「解析失败」分开的唯一办法。
        MsgLog.add(app, at + " ▼ 收到广播 " + action);

        if (it == null) return;
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(it.getAction())) return;

        Bundle b = it.getExtras();
        if (b == null) { MsgLog.add(app, "     ✗ 广播没带 extras"); return; }

        // 一条长短信会被拆成多个 pdu，逐个还原后分别上报
        Object[] pdus = (Object[]) b.get("pdus");
        if (pdus == null || pdus.length == 0) {
            MsgLog.add(app, "     ✗ extras 里没有 pdus");
            return;
        }

        // 有些 ROM 不给 format；给个兜底，别让 createFromPdu 直接抛
        String fmt = b.getString("format");
        if (fmt == null) fmt = "3gpp";

        for (Object pdu : pdus) {
            if (!(pdu instanceof byte[])) continue;

            SmsMessage m;
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    m = SmsMessage.createFromPdu((byte[]) pdu, fmt);
                } else {
                    m = SmsMessage.createFromPdu((byte[]) pdu);
                }
            } catch (Throwable t) {
                // 解不开也要留痕：否则在用户眼里"什么都没发生"，无从下手
                MsgLog.add(app, "     ✗ pdu 解析抛异常：" + t.getClass().getSimpleName());
                continue;
            }
            if (m == null) { MsgLog.add(app, "     ✗ pdu 解析返回 null"); continue; }

            String body = m.getMessageBody();
            String from = m.getOriginatingAddress();
            if (from == null) from = "未知号码";

            if (dup(from, body)) {
                MsgLog.add(app, "     ↷ 重复广播，已跳过");
                continue;
            }

            MsgLog.add(app, "     [" + from + "] " + PostTask.cut(body, 26));

            // 顺手弹一条系统提示 —— App 在后台也能当场看见。
            // 收到了短信却没弹这个、流水也没动 → onReceive 压根没跑。
            // onReceive 本身就跑在主线程，可以直接 show。
            try {
                Toast.makeText(app, "短信桥读到：" + PostTask.cut(body, 24),
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignore) { /* 提示弹不出来不该影响转发 */ }

            // 必须离开主线程发网络请求：BroadcastReceiver 跑在主线程，
            // 直接在 onReceive 里发会 NetworkOnMainThreadException。
            new PostTask(app, body, from).start();
        }
    }

    /** 同一条短信 5 秒内只处理一次（来源 + 内容相同即视为同一条） */
    private static boolean dup(String from, String body) {
        String k = from + "\u0000" + body;
        long now = System.currentTimeMillis();
        if (k.equals(lastKey) && (now - lastAt) < 5000L) return true;
        lastKey = k;
        lastAt  = now;
        return false;
    }
}
