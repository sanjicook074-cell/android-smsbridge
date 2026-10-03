package com.smsbridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把一条短信原文 POST 给电脑上的收码服务。
 *
 * ── 为什么单独写成一个顶层类，而不是 new Thread(new Runnable(){ ... }) ──
 *
 * ★ 实测踩坑（2026-10-03）：
 *   d8（build-tools 33 的 3.3.20、build-tools 34 的 8.2.2 **都**试过）
 *   在把「**匿名内部类**」转 dex 时会内部崩溃：
 *
 *     Error in ...\MainActivity$1.class:
 *     java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
 *       at com.android.tools.r8.graph.u2.<init>
 *
 *   主类、以及普通命名类都正常，只有 `Xxx$1.class` 这类匿名类必崩；
 *   把匿名类实现的接口换成自己的（而不是 android.jar 里的）就不会崩 ——
 *   但它实现 android.view.View$OnClickListener 这种**库接口**时必崩。
 *
 *   没有深挖到 R8 源码，但结论足够指导实践：
 *   **这条手工编译链下不要用匿名内部类**，改成命名类或让外层类实现接口。
 *   （一旦改用 gradle 构建，这条限制就不存在了，那时可以随便写。）
 */
public class PostTask extends Thread {

    /** ★ 编译时由 build.sh 把 __TOKEN__ 换成真令牌（源码里永远只有占位符） */
    private static final String TOKEN = "__TOKEN__";

    static final String PREF     = "smsbridge";
    static final String KEY_HOST = "host";
    static final String KEY_LAST = "last";

    /** 收码服务默认端口。地址里没写端口时补这个。 */
    static final int DEFAULT_PORT = 8800;

    /**
     * 默认的电脑地址 —— 只是个**初始值**，装好后在界面上改成你自己电脑的 IP 就行
     * （改完点「保存地址」，下次启动就是它了）。
     *
     * 想在编译期就烧死一个固定地址（批量装机、不想让用户改）：
     *   SMSBRIDGE_HOST=192.168.1.50:8800 ./build.sh
     * build.sh 会把下面的 __HOST__ 换成你给的值。
     */
    static final String DEFAULT_HOST = "__HOST__";

    private final Context ctx;
    private final String  body;
    private final String  from;

    /**
     * 跨通道去重：同一条内容 10 秒内只真正发一次。
     *
     * 现在同一条短信有**两条**上报路径（SMS_RECEIVED 广播 / 直读收件箱），
     * 两条都通时必然会各发一次 —— 电脑端会连着收到两条一模一样的报文，
     * 日志刷屏、也容易误判成"收到两条短信"。所以在最后出口这里掐掉。
     *
     * 之前 SmsReceiver 里那份去重只挡得住"静态 + 动态注册各收到一次广播"，
     * 挡不住跨通道，所以挪到 PostTask —— 出口只有一个，这里挡住最省事。
     */
    private static final Object LOCK     = new Object();
    private static String lastBody = "";
    private static long   lastAt   = 0L;

    public PostTask(Context ctx, String body, String from) {
        this.ctx  = ctx.getApplicationContext();
        this.body = body;
        this.from = from;
    }

    @Override
    public void run() {
        synchronized (LOCK) {
            long t = System.currentTimeMillis();
            if (body != null && body.length() > 0
                    && body.equals(lastBody) && (t - lastAt) < 10000L) {
                // 记一笔而不是静默丢弃：否则界面上会出现「读到了」却没有结果行，
                // 看上去又像"读到了发不出去"，白绕一圈。
                MsgLog.add(ctx, "   ↺ 重复（10 秒内同一条），已跳过");
                return;
            }
            lastBody = body;
            lastAt   = t;
        }

        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String url = buildUrl(sp.getString(KEY_HOST, DEFAULT_HOST));

        HttpURLConnection c = null;
        String line;
        String mark;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] out = ("{\"text\":" + q(body) + ",\"from\":" + q(from) + "}").getBytes("UTF-8");
            // 定长：避免 chunked 编码，服务端那边是手写的 body 读取
            c.setFixedLengthStreamingMode(out.length);
            OutputStream os = c.getOutputStream();
            os.write(out);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            boolean ok = (code == 200);
            line = (ok ? "已送达 ✔ " : "服务器回了 " + code + " ✗ ") + now();
            mark = (ok ? "   ✓ 已送达 " : "   ✗ 服务器回了 " + code + " ") + now();

            try {
                InputStream is = (code == 200 ? c.getInputStream() : c.getErrorStream());
                if (is != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] tmp = new byte[512];
                    int n;
                    while ((n = is.read(tmp)) > 0) bo.write(tmp, 0, n);
                    is.close();
                    String resp = new String(bo.toByteArray(), "UTF-8").trim();
                    if (resp.length() > 0) line += "  " + cut(resp, 60);
                }
            } catch (Throwable ignore) { /* 读返回失败不影响「已送达」这个判断 */ }

            line += "\n   ← " + cut(body, 40);
        } catch (Exception e) {
            String name = e.getClass().getSimpleName();
            line = "转发失败 ✗ " + now() + "  " + name
                    + (e.getMessage() != null ? ("：" + e.getMessage()) : "");
            mark = "   ✗ 转发失败 " + now() + "  " + name;
        } finally {
            if (c != null) c.disconnect();
        }

        sp.edit().putString(KEY_LAST, line).apply();
        // 同一条事件的第二行：上一行是 SmsReceiver 记的「读到了」，这行是「发出去的结果」。
        // 只有前者没有后者 = 读到了但发不出去；两行都没有 = 根本没读到。
        MsgLog.add(sp, mark);
    }

    static String buildUrl(String host) {
        return "http://" + hostPort(host) + "/sms?token=" + TOKEN;
    }

    /**
     * 把用户填的东西整理成 "IP:端口"。
     *
     * ★ 为什么非要有这个函数（2026-10-03 实测踩坑）：
     *   App 界面上显示的是「电脑地址：192.168.1.100:8800」，
     *   但 SharedPreferences 里存的、以及默认值其实**只有 IP、没有端口**。
     *   直接拼 URL 就变成 http://192.168.1.100/sms → **80 端口**，
     *   而收码服务在 8800 —— 于是连不上。
     *
     *   最坑的是报错形式：native 的 connect 超时抛
     *     SocketTimeoutException: failed to connect to /x.x.x.x (port …) after 8000ms
     *   **是「超时」而不是「拒绝」**，看上去特别像"被防火墙拦了"，
     *   于是会一路往防火墙/安全软件上查（我就查了半天），方向全错。
     *   实际上只是那个端口没人听。
     *
     *   所以：端口一律在这里补，界面显示也走这个函数 —— 让「显示」和「实际连的」永远一致。
     */
    static String hostPort(String host) {
        String h = (host == null ? "" : host.trim());
        if (h.startsWith("http://"))  h = h.substring(7);
        if (h.startsWith("https://")) h = h.substring(8);
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        if (h.length() == 0) return DEFAULT_HOST;

        // 已经是 "IP:端口"（最后一段冒号后全是数字）就原样用
        int i = h.lastIndexOf(':');
        if (i > 0 && i < h.length() - 1) {
            boolean allDigits = true;
            for (int k = i + 1; k < h.length(); k++) {
                if (!Character.isDigit(h.charAt(k))) { allDigits = false; break; }
            }
            if (allDigits) return h;
        }
        return h + ":" + DEFAULT_PORT;
    }

    /** 最小 JSON 字符串转义，够用（短信里不会有什么花活） */
    static String q(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ");
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    static String now() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
    }
}
