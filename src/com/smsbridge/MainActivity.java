package com.smsbridge;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 短信桥 —— 手机收到的短信自动送到电脑上的收码服务。
 *
 * 用户视角只有两步：① 点「允许读取短信」 ② 完事。
 * 地址预置好了，平时不用碰；只有换 Wi-Fi / 电脑 IP 变了才需要改输入框。
 *
 * 界面全部用代码搭，没有 layout XML —— 一共几个控件，另立一份 XML 不划算。
 *
 * ⚠ 这里刻意**一个匿名内部类都不用**：监听器由 Activity 自己实现。
 *   原因是这条手工编译链（d8）处理匿名类时会内部崩溃，详见 PostTask.java 顶部注释。
 */
public class MainActivity extends Activity implements View.OnClickListener {

    static final String PREF     = "smsbridge";
    static final String KEY_HOST = "host";
    static final String KEY_LAST = "last";

    TextView status;
    TextView logText;
    TextView diagText;
    EditText hostEdit;
    Button   grantBtn;
    Button   notifBtn;
    Button   bootBtn;
    Button   saveBtn;
    Button   testBtn;

    /** ticker 是否在跑：onPause 置 false，Ticker 下一轮看到就自己停 */
    boolean ticking;

    /**
     * 界面可见时**额外**动态注册一个接收器。
     * 静态注册在部分厂商 ROM 上会被拦（尤其是被"省电/自启动"管控之后），
     * 而动态注册只要进程活着就一定收得到 —— 两条通道互为保险，
     * 万一撞车收到两份，由 SmsReceiver 里的去重吃掉。
     */
    SmsReceiver dynRecv;
    boolean    dynLogged;

    /** 诊断只自动上报一次（每次 App 启动算一次），免得刷屏 */
    boolean diagSent;

    // ---- 收件箱通道（第二条路）的状态 ----
    /** 已经处理过的收件箱最新一条：时间戳 + 正文。用来判断"有没有新的"。 */
    long   inboxTs   = -1;    // -1 = 还没建立基线
    String inboxBody = "";
    /** 收件箱读不到时把原因留在界面上（通常是没给 READ_SMS） */
    String inboxNote = "";
    /** 轮询节流：每 2 个 tick 查一次（约 3 秒），别每 1.5 秒都去翻数据库 */
    int    pollTick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        int pad = dp(18);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("短信桥");
        title.setTextSize(24);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("手机收到短信 → 自动送到电脑");
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, 0);
        root.addView(sub);

        // ---- 电脑地址（唯一需要用户知道的东西）----
        root.addView(label("电脑地址（IP:端口）"));
        hostEdit = new EditText(this);
        hostEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        hostEdit.setSingleLine(true);
        hostEdit.setText(host());
        root.addView(hostEdit);

        // ---- 授权 ----
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);

        grantBtn = new Button(this);
        grantBtn.setOnClickListener(this);
        root.addView(grantBtn, lp);

        // ---- 通知读取（第三条路：什么消息都能捞到）----
        notifBtn = new Button(this);
        notifBtn.setOnClickListener(this);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(8);
        root.addView(notifBtn, lp2);

        // ---- 后台常驻（防止息屏闲置时被省电策略冻掉）----
        bootBtn = new Button(this);
        bootBtn.setOnClickListener(this);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = dp(8);
        root.addView(bootBtn, lp3);

        // ---- 保存 / 测试 ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        saveBtn = new Button(this);
        saveBtn.setText("保存地址");
        saveBtn.setOnClickListener(this);
        row.addView(saveBtn);

        testBtn = new Button(this);
        testBtn.setText("发条测试");
        testBtn.setOnClickListener(this);
        row.addView(testBtn);
        root.addView(row);

        // ---- 状态 ----
        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status);

        // ---- 收到的短信流水 ----
        // App 自己记的，唯一目的是回答"到底读到没"：短信一来，
        // 这里会先多一行「读到」，紧接着一行转发结果。
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setPadding(0, dp(16), 0, 0);
        logText.setTextIsSelectable(true);   // 长按可复制，方便念给电脑那端
        root.addView(logText);

        // ---- 诊断 ----
        // 专门给"收不到"这种情况用的：把系统层面的真实状态摊开，
        // 而且会**自动发到电脑**，用户不用看懂、也不用截图转述。
        diagText = new TextView(this);
        diagText.setTextSize(12);
        diagText.setPadding(0, dp(16), 0, 0);
        diagText.setTextIsSelectable(true);
        root.addView(diagText);

        TextView tip = new TextView(this);
        tip.setTextSize(12);
        tip.setPadding(0, dp(16), 0, 0);
        tip.setText("装上之后三步，都点一下就行：\n"
                + "① 允许读取短信（一个弹窗里两个权限，都允许）\n"
                + "② 开启通知读取 → 在跳出的列表里找到「短信桥」并打开\n"
                + "③ 允许后台常驻 → 系统问「是否允许始终在后台运行」时选允许\n\n"
                + "前两步是三条互相独立的通道，多开不冲突：\n"
                + "   广播 / 直读收件箱 / 读通知。哪条通了日志里一眼能看出来。\n\n"
                + "★ 「② 通知读取」是最稳的一条：它连小米的「免费网络短信」都能捞到\n"
                + "（那种消息走网络、不是运营商短信，既不进短信库也不发短信广播），\n"
                + "而且它是系统绑定的服务，App 被清理后台也会被系统重新拉起。\n\n"
                + "★ 「③」别跳过：手机息屏闲置一段时间后，\n"
                + "小米/华为正好挑这种时候把 App 冻掉，转发会静默停止、现场看不出任何异常。\n\n"
                + "只有换了 Wi-Fi、或电脑的 IP 变了，才需要改上面那个地址。\n\n"
                + "下面的流水会实时刷新：有消息来了会先出现一行「读到」，\n"
                + "紧接着一行转发结果。");
        root.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        selfCheck();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();

        // 界面可见时每 1.5 秒自刷一次：短信一到，屏幕上的流水当场就变，
        // 不用用户关掉再重开才知道 App 有没有反应。
        if (!ticking) {
            ticking = true;
            getWindow().getDecorView().postDelayed(new Ticker(this), 1500);
        }

        // 再挂一条动态通道（见字段注释）。挂不上就算了，静态那条是主路。
        if (dynRecv == null) {
            try {
                dynRecv = new SmsReceiver();
                registerReceiver(dynRecv,
                        new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));
                if (!dynLogged) {
                    dynLogged = true;
                    MsgLog.add(this, PostTask.now() + " 已挂上前台动态接收器（双保险）");
                }
            } catch (Throwable t) {
                dynRecv = null;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticking = false;   // Ticker 下一轮看到就停，不在后台空转

        if (dynRecv != null) {
            try { unregisterReceiver(dynRecv); } catch (Throwable ignore) { }
            dynRecv = null;
        }
    }

    // ------------------------------------------------------------ 点击分发

    @Override
    public void onClick(View v) {
        if (v == grantBtn) {
            askSms();
        } else if (v == notifBtn) {
            // 通知使用权没有运行时权限对话框，只能跳到系统设置页手动勾选
            if (hasNotifAccess()) {
                toast("通知读权已经有了 ✔");
            } else {
                try {
                    startActivity(new Intent(
                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    toast("在列表里找到「短信桥」，打开它");
                } catch (Throwable t) {
                    toast("请手动进：设置 → 通知 → 通知使用权");
                }
            }
        } else if (v == bootBtn) {
            // 让用户亲手在系统弹窗上选「允许后台运行」——比自己进"省电策略"里翻好找得多，
            // 而且这个弹窗给的正是我们要的那个开关。
            if (ignoringBattery()) {
                toast("已经有了 ✔");
            } else {
                try {
                    Intent i = new Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    // 有些 ROM 屏蔽了这个直达弹窗 → 退到电池优化列表页，让用户自己找
                    try {
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Throwable t2) {
                        toast("请手动进：设置 → 应用 → 短信桥 → 省电策略 → 无限制");
                    }
                }
            }
        } else if (v == saveBtn) {
            prefs().edit().putString(KEY_HOST, hostEdit.getText().toString().trim()).apply();
            toast("已保存");
            refresh();
        } else if (v == testBtn) {
            // ★ 先把输入框里的地址落盘，再发。否则用户在框里改了地址却忘了点「保存地址」，
            //   测试用的还是旧地址 —— 又是一次「界面和实际不一致」的坑。
            prefs().edit().putString(KEY_HOST, hostEdit.getText().toString().trim()).apply();
            toast("发往 " + PostTask.hostPort(host()) + " …");
            refresh();
            // 内容刻意带「自测」字样：这样 App 流水和电脑端日志里都能一眼分辨
            // 这条是按钮造的假短信、还是真收到的那条。
            new PostTask(this, "【短信桥·自测】验证码 123456，5分钟内有效", "1069").start();
        }
    }

    // ------------------------------------------------------------ 权限

    void askSms() {
        if (hasSms() && hasRead()) { toast("两个权限都有了 ✔"); refresh(); return; }
        if (Build.VERSION.SDK_INT >= 23) {
            // 两个一起要：RECEIVE_SMS 走广播、READ_SMS 直读收件箱。
            // 少一个就少一条通道 —— 小米的「免费网络短信」和部分 ROM 的静默过滤
            // 都专门打广播这条路，所以备路不是可选项。
            requestPermissions(new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS}, 1);
        }
    }

    boolean hasSms() {
        if (Build.VERSION.SDK_INT < 23) return true;   // 6.0 以前安装即授予
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasRead() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 通知使用权有没有给。
     *
     * 这个没有运行时权限 API，系统把已授权的包名列表存在
     * Settings.Secure 的 enabled_notification_listeners 里（字符串，
     * 形如 "pkg/com.pkg.Service:另一个包/…"），查自己包名在不在里面即可。
     */
    boolean hasNotifAccess() {
        try {
            String s = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 系统是否已把我们排除在电池优化之外。
     *
     * 为什么这条重要：手机息屏闲置一段时间后，省电策略恰好挑这种时候把 App 冻掉 ——
     * 通知监听服务被回收、转发静默停止，而现场看什么都正常。
     * 这是这类工具在国产 ROM 上的头号死因。
     */
    boolean ignoringBattery() {
        try {
            android.os.PowerManager pw = (android.os.PowerManager)
                    getSystemService(POWER_SERVICE);
            return pw != null && pw.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean recv = hasSms();
        boolean read = hasRead();
        toast(recv && read ? "授权成功 ✔"
                : "广播=" + (recv ? "有" : "无") + "，收件箱=" + (read ? "有" : "无"));
        inboxTs = -1;   // 权限可能刚给上：丢掉旧基线，下一轮重新取
        refresh();
    }

    // ------------------------------------------------------------ 状态

    void refresh() {
        boolean okRecv = hasSms();
        boolean okRead = hasRead();
        boolean ok     = okRecv && okRead;
        grantBtn.setText(ok ? "① 已允许读取短信 ✔" : "① 允许读取短信（两个权限）");
        grantBtn.setEnabled(!ok);

        boolean notif = hasNotifAccess();
        notifBtn.setText(notif ? "② 通知读权已开启 ✔" : "② 开启通知读取（推荐，最稳）");
        notifBtn.setEnabled(!notif);

        boolean boot = ignoringBattery();
        bootBtn.setText(boot ? "③ 后台常驻已允许 ✔" : "③ 允许后台常驻（防息屏被冻）");
        bootBtn.setEnabled(!boot);

        // ★ 显示的必须就是实际会连的（走 PostTask.hostPort 归一化），不能自己再硬拼端口 ——
        //   之前这里写的是 host() + ":8800"，显示 8800、实际连 80，白查了半天防火墙。
        String s = "短信权限：广播=" + (okRecv ? "有 ✔" : "无 ✗")
                 + "  收件箱=" + (okRead ? "有 ✔" : "无 ✗") + "\n"
                 + "通知读权：" + (notif ? "已开启 ✔" : "未开启 ✗") + "\n"
                 + "后台常驻：" + (boot ? "已允许 ✔" : "未允许 ✗") + "\n"
                 + "电脑地址：" + PostTask.hostPort(host()) + "\n"
                 + "收件箱最新：" + inboxSummary() + "\n"
                 + "最近转发：" + prefs().getString(KEY_LAST, "（还没转发过）");
        // 只在内容真变了才 setText —— ticker 每 1.5 秒来一次，无脑重设会闪
        if (!s.equals(plain(status))) status.setText(s);

        String lg = "收到的短信：\n" + MsgLog.all(prefs());
        if (!lg.equals(plain(logText))) logText.setText(lg);

        pollInbox();
    }

    /**
     * 收件箱通道：定期看一眼 content://sms/inbox 的最近一条，是新的就转发。
     *
     * 这条路**完全不依赖 SMS_RECEIVED 广播投递** —— 短信是默认短信 App 写进
     * 数据库的，只要能读库就能拿到。专门对付两种广播不到的场合：
     * 小米「免费网络短信」（压根不是 SMS，我们本来也不必管）
     * 以及 ROM 对广播的静默过滤。
     */
    void pollInbox() {
        if ((++pollTick % 2) != 0) return;      // 约 3 秒一次，别每 1.5 秒都翻库

        if (!hasRead()) { inboxNote = "读不了（没给「读取短信」权限）"; return; }

        String row = Inbox.latest(this);
        String[] p = row.split(Inbox.SEP, 3);
        if (p.length < 3) { inboxNote = row; return; }   // 出错原因原样留在界面上

        long ts;
        try { ts = Long.parseLong(p[0]); }
        catch (Throwable t) { inboxNote = "时间戳解析失败"; return; }

        String addr = p[1];
        String body = p[2];
        if (ts == inboxTs && body.equals(inboxBody)) { inboxNote = ""; return; }  // 没变化

        boolean baseline = (inboxTs < 0);
        inboxTs   = ts;
        inboxBody = body;
        inboxNote = "";

        if (baseline) {
            // 首次只记基线：否则一装上就会把历史上百条短信全灌到电脑
            MsgLog.add(this, PostTask.now() + " 收件箱通道就绪（现存最近一条："
                    + PostTask.cut(body, 18) + "）");
            return;
        }

        MsgLog.add(this, PostTask.now() + " [收件箱·" + addr + "] " + PostTask.cut(body, 26));
        new PostTask(this, body, addr).start();
    }

    /** 界面上那行「收件箱最新」——读不了就把原因写出来，别只显示空白让人猜 */
    String inboxSummary() {
        if (!hasRead())  return "读不了（没给权限）";
        if (inboxNote.length() > 0) return inboxNote;
        if (inboxTs < 0) return "（还没读到）";
        return Inbox.clock(inboxTs) + "  " + PostTask.cut(inboxBody, 14);
    }

    static String plain(TextView t) {
        return t.getText() == null ? "" : t.getText().toString();
    }

    /**
     * 启动自检：把被系统/安全软件禁用的短信接收组件重新启用。
     *
     * 这种情况最迷惑人 —— 权限明明给了、App 也活着、测试按钮也能发出去，
     * 但短信一条都收不到，因为接收组件在系统里被 disable 掉了。
     * getComponentEnabledSetting 能看出这个状态，顺手修好并记一笔流水。
     */
    void selfCheck() {
        try {
            ComponentName cn = new ComponentName(this, SmsReceiver.class);
            PackageManager pm = getPackageManager();
            int cur = pm.getComponentEnabledSetting(cn);
            if (cur != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    && cur != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                pm.setComponentEnabledSetting(cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
                MsgLog.add(this, PostTask.now()
                        + " ★ 短信接收组件原为禁用(" + cur + ")，已重新启用");
            }
        } catch (Throwable t) {
            MsgLog.add(this, PostTask.now() + " 自检出错：" + t.getClass().getSimpleName());
        }
        showDiag();
    }

    /**
     * 把系统层面的真实状态摊出来，并**自动发一份到电脑**。
     *
     * ── 为什么非要这个 ──
     * `checkSelfPermission` 说"已授予"**不等于**广播会投给我们。
     * MIUI / HyperOS 这类 ROM 会在 **AppOps 层**再拦一道：
     * 权限显示已授予、App 活着、前台动态注册也挂上了，广播却仍被静默过滤，
     * 不留任何痕迹。`checkOpNoThrow` 才反映真实的放行状态。
     *
     * 自动上报是刻意的：这类信息用户看不懂、也不好转述，
     * 直接 POST 到电脑那端，在日志里就是一行行字。
     */
    void showDiag() {
        StringBuilder sb = new StringBuilder("诊断 Android ");
        sb.append(Build.VERSION.RELEASE)
          .append("/API ").append(Build.VERSION.SDK_INT)
          .append(" | RECEIVE_SMS=").append(hasSms() ? "GRANTED" : "DENIED")
          .append(" | READ_SMS=").append(hasRead() ? "GRANTED" : "DENIED")
          .append(" | 通知读权=").append(hasNotifAccess() ? "ON" : "OFF")
          .append(" | 电池优化=").append(ignoringBattery() ? "已豁免" : "未豁免");

        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int op = aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_RECEIVE_SMS,
                    android.os.Process.myUid(), getPackageName());
            sb.append(" | AppOps.receive_sms=").append(opName(op));
        } catch (Throwable t) {
            sb.append(" | AppOps=查询失败(").append(t.getClass().getSimpleName()).append(")");
        }

        try {
            ComponentName cn = new ComponentName(this, SmsReceiver.class);
            sb.append(" | 接收组件=")
              .append(getPackageManager().getComponentEnabledSetting(cn))
              .append("(0默认1启用2禁用)");
        } catch (Throwable t) { /* 查不到就算了 */ }

        try {
            android.content.Intent probe =
                    new android.content.Intent("android.provider.Telephony.SMS_RECEIVED");
            java.util.List<android.content.pm.ResolveInfo> rs =
                    getPackageManager().queryBroadcastReceivers(probe, 0);
            boolean me = false;
            for (int i = 0; i < rs.size(); i++) {
                android.content.pm.ResolveInfo ri = rs.get(i);
                if (ri != null && ri.activityInfo != null
                        && getPackageName().equals(ri.activityInfo.packageName)) me = true;
            }
            sb.append(" | 系统内注册此广播=").append(rs.size()).append("个,本机在列=")
              .append(me ? "是" : "否★");
        } catch (Throwable t) { /* 查不到就算了 */ }

        try {
            sb.append(" | 默认短信App=")
              .append(android.provider.Telephony.Sms.getDefaultSmsPackage(this));
        } catch (Throwable t) { /* 查不到就算了 */ }

        // ★ 最有价值的一项：收件箱里到底有什么。
        //   如果手机"明明收到了短信"而这里查不到 → 那条消息压根不是 SMS
        //   （小米「免费网络短信」就是这样：走网络，不进短信库，也不发广播）。
        try {
            sb.append(" | 收件箱最近3条=")
              .append(hasRead() ? Inbox.dump(this, 3) : "无 READ_SMS，读不了");
        } catch (Throwable t) { sb.append(" | 收件箱=查询异常"); }

        diagText.setText(sb.toString());

        if (!diagSent) {
            diagSent = true;
            new PostTask(this, sb.toString(), "SMSBRIDGE-DIAG").start();
        }
    }

    static String opName(int m) {
        if (m == 0) return "ALLOWED(0)✔";
        if (m == 1) return "IGNORED(1)★就是它";
        if (m == 2) return "ERRORED(2)✗";
        if (m == 3) return "DEFAULT(3)";
        return "未知(" + m + ")";
    }

    String host() {
        return prefs().getString(KEY_HOST, PostTask.DEFAULT_HOST);
    }

    SharedPreferences prefs() { return getSharedPreferences(PREF, MODE_PRIVATE); }

    TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
