package com.smsbridge;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * 直读系统短信库（content://sms/inbox）—— 短信广播之外的**第二条通道**。
 *
 * ── 为什么非要这条（2026-10-03 加）──
 * 广播这条路有两个「看不见的断点」，都跟权限无关：
 *
 *   ① **小米 / 红米的「免费网络短信」**
 *      消息走的是网络（Wi-Fi/流量），**根本不是运营商短信**。
 *      手机短信界面照样显示、收件箱里照样有一来一回，但
 *      SMS_RECEIVED 广播从头到尾不会发出 —— 所有第三方 App 都读不到。
 *      （实锤：小米之间互发时气泡下方会写「免费网络短信」。）
 *
 *   ② 某些 ROM 的静默过滤
 *      权限全绿、AppOps=ALLOWED、前台动态注册也挂上了，广播就是不来。
 *
 * 而短信本身是**默认短信 App 写进 content://sms 的**，只要有 READ_SMS
 * 就能直接读出来，完全不依赖广播投递。官方文档《Telephony》原话：
 * 非默认短信 App「只能读 SMS Provider，但也可以通过监听 SMS_RECEIVED_ACTION
 * 广播得到新短信通知」—— 读库是被允许的正路，不是歪门。
 *
 * ── 它同时还是个判别器 ──
 * 「收件箱里到底有没有这条」直接回答了「我该不该指望广播」：
 * 库里查得到 = 真短信、只是广播没到；库里查不到 = 那条压根不是 SMS。
 *
 * ★ 这个文件里**没有一个匿名内部类**（d8 会崩），也不定义自定义内部类
 *   （保险起见），一律只返回 String，让调用方自己 split。
 */
public class Inbox {

    /** 字段分隔符。\u0001 是控制字符，短信正文和号码里都不可能存在，不会撞。 */
    static final String SEP = "\u0001";

    static final Uri INBOX = Uri.parse("content://sms/inbox");

    /** 最近一条 */
    static String latest(Context ctx) {
        return one(ctx, 0);
    }

    /**
     * 取第 i 近的一条（0 = 最近）。
     * 成功 → "时间戳\u0001号码\u0001正文"（正好 3 段）
     * 失败 → 一句人话（不含分隔符，所以 split 出来不足 3 段，调用方据此判断）
     */
    static String one(Context ctx, int i) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(INBOX,
                    new String[] { "date", "address", "body" },
                    null, null, "date DESC");
            if (c == null) return "读收件箱失败：query 返回 null";
            if (c.getCount() <= i) return "收件箱一共只有 " + c.getCount() + " 条";
            if (!c.moveToPosition(i)) return "读收件箱失败：定位第 " + i + " 条失败";
            return c.getLong(0) + SEP + nz(c.getString(1)) + SEP + nz(c.getString(2));
        } catch (Throwable t) {
            // 没给 READ_SMS 时就是在这里抛 SecurityException —— 要让它显形
            return "读收件箱失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ("：" + t.getMessage()));
        } finally {
            if (c != null) c.close();
        }
    }

    /** 最近 n 条拼成一行，专门给诊断上报用（用户在界面上看不懂，电脑端一眼就懂） */
    static String dump(Context ctx, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String row = one(ctx, i);
            String[] p = row.split(SEP, 3);
            if (p.length < 3) { sb.append(row); break; }   // 出错就原样带上，别吞

            long ts = 0L;
            try { ts = Long.parseLong(p[0]); } catch (Throwable ignore) { }
            sb.append('[').append(clock(ts)).append(' ').append(p[1]).append(' ')
              .append(PostTask.cut(p[2], 16)).append("] ");
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    static String nz(String s) { return s == null ? "" : s; }

    /** 毫秒 → "MM-dd HH:mm:ss"。短信库跨天很常见，必须带日期，不能只给时分秒。 */
    static String clock(long ms) {
        if (ms <= 0L) return "?";
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss")
                .format(new java.util.Date(ms));
    }
}
