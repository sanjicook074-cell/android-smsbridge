package com.smsbridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App 侧的事件流水：读到了什么短信、转发成没成。
 *
 * ── 为什么要有这个 ──
 * 原来的界面只显示「最近一次转发结果」，于是这两种情况长得一模一样：
 *   a) 压根没读到短信（接收器没被唤起 / 没权限 / 被系统冻结）
 *   b) 读到了，但发不出去（网络、地址、令牌）
 * 分不清就没法查。所以把「读到」也记一笔，且**记在转发之前** ——
 * 只要界面上出现了这一行，就证明接收器是活的。
 *
 * 存成一个多行字符串，最新的在最上面，最多留 MAX 行。
 * 手机侧只做记录，不做任何判断（抽验证码的事留在电脑那端）。
 */
public class MsgLog {

    static final String KEY = "log";
    static final int MAX = 14;   // 够看最近七八条短信两边（读到 + 结果）

    static void add(Context ctx, String line) {
        add(ctx.getSharedPreferences(PostTask.PREF, Context.MODE_PRIVATE), line);
    }

    /** 新行插到最前面，并把总量截到 MAX。同进程内存共享，apply 就够了。 */
    static void add(SharedPreferences sp, String line) {
        if (line == null || line.length() == 0) return;

        String all = line + "\n" + sp.getString(KEY, "");
        String[] ls = all.split("\n");

        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < ls.length && n < MAX; i++) {
            if (ls[i] == null || ls[i].length() == 0) continue;
            if (n > 0) sb.append('\n');
            sb.append(ls[i]);
            n++;
        }
        sp.edit().putString(KEY, sb.toString()).apply();
    }

    static String all(SharedPreferences sp) {
        String s = sp.getString(KEY, "");
        return (s == null || s.length() == 0) ? "（还没有记录）" : s;
    }
}
