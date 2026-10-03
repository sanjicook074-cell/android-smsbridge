package com.smsbridge;

import android.view.View;

/**
 * 每 1.5 秒把主界面刷一次，让「刚收到的那条短信」当场出现在屏幕上。
 *
 * ── 为什么需要 ──
 * 状态区原本只在 onCreate / onResume 刷新。用户开着 App、收到一条短信，
 * 界面纹丝不动 —— 看上去跟"App 死了"没区别，于是分不清是没读到、
 * 还是读到了没转发。（2026-10-03 实测就是这么卡住的。）
 *
 * ★ 顶层命名类，**不是匿名 Runnable**：这条手工编译链（d8）处理匿名内部类
 *   时会内部 NPE 崩溃，详见 PostTask.java 顶部注释。
 */
public class Ticker implements Runnable {

    private final MainActivity act;

    public Ticker(MainActivity act) { this.act = act; }

    @Override
    public void run() {
        // act.ticking 由 Activity 在 onPause 里置 false —— 界面看不见时自己停，
        // 免得在后台空转耗电。
        if (act == null || act.isFinishing() || !act.ticking) return;

        act.refresh();

        View root = (act.getWindow() == null) ? null : act.getWindow().getDecorView();
        if (root != null) root.postDelayed(this, 1500);
    }
}
