// sms_hub.mjs — 电脑端收码服务。
//
// 手机上的「短信桥」把收到的消息 POST 过来，这里打屏、落盘、顺手抽出验证码。
// 零依赖，只要有 node 就能跑。
//
// 用法：
//   node server/sms_hub.mjs                      # 默认监听 8800
//   node server/sms_hub.mjs --port 8800 --token xxxxxxxx
//
// 令牌取用顺序：--token > 环境变量 SMSBRIDGE_TOKEN > 项目根的 .smsbridge_token
// 文件 > 自动生成一个写进去。build.sh 用的是**同一个文件**，
// 所以编译出来的 APK 和这个服务天然用同一个令牌，不用手工对齐两处。
//
// 手机端怎么发过来（三条通道都发同一个接口，用 from 区分来源）：
//   POST http://<本机局域网IP>:8800/sms?token=<令牌>
//   body: {"text":"【某某】验证码 123456，5分钟内有效","from":"1069"}
//   也接受纯文本 body，或 form-urlencoded 的 text=...&from=...

import { createServer, get as httpGet } from 'node:http';
import { appendFileSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { networkInterfaces } from 'node:os';
import { randomBytes } from 'node:crypto';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TOKEN_FILE = join(ROOT, '.smsbridge_token');
const OUT_FILE = join(ROOT, 'last_code.json');
const LOG_FILE = join(ROOT, 'server', 'sms_hub.log');

const argv = process.argv.slice(2);
const opt = { port: 8800, token: '' };
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--port') opt.port = Number(argv[++i]) || 8800;
  else if (argv[i] === '--token') opt.token = argv[++i] || '';
}

if (!opt.token) opt.token = process.env.SMSBRIDGE_TOKEN || '';
if (!opt.token && existsSync(TOKEN_FILE)) opt.token = readFileSync(TOKEN_FILE, 'utf8').trim();
if (!opt.token) {
  opt.token = randomBytes(9).toString('base64url').slice(0, 12);
  writeFileSync(TOKEN_FILE, opt.token, 'utf8');
  console.log(`[i] 没找到令牌，已生成一个并写进 ${TOKEN_FILE}`);
}

const nowText = () => new Date().toLocaleString('zh-CN', { hour12: false });

const lanIps = () => {
  const out = [];
  for (const [name, list] of Object.entries(networkInterfaces())) {
    for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal) out.push({ name, addr: a.address });
    }
  }
  return out;
};

function logLine(line) {
  const t = `[${nowText()}] ${line}`;
  console.log(t);
  try { appendFileSync(LOG_FILE, t + '\n', 'utf8'); } catch { /* 写不进日志不该影响收码 */ }
}

/**
 * 从一段文本里抽出验证码。抽不到就返回 null。
 *
 * ★ 原则：**宁可不填，不可填错。**
 *   填错了会让对方走「验证失败」的分支，比没反应更糟（很多平台把它计入风控）。
 *   所以只认**带锚点**的码 —— 光有一串数字不算数：
 *     「订单 123456 已发货」→ null（不猜）
 *     「验证码 123456」      → 123456
 *   想接更花的短信格式，就把锚点词往 CODE_WORDS 里加。
 */
const CODE_WORDS = '验证码|校验码|动态码|确认码|安全码|口令|code|otp|pin';

function pickCode(text) {
  if (!text) return null;
  const s = String(text);
  // ① 锚点在前：「验证码 123456」/「验证码是 123456」/「code: 123456」
  //    中间容 12 个非数字字符，够放下「是」「：」「为」之类
  let m = s.match(new RegExp(`(?:${CODE_WORDS})[^0-9]{0,12}(\\d{4,8})`, 'i'));
  if (m) return m[1];
  // ② 锚点在后：「123456 是您的验证码」
  m = s.match(new RegExp(`(?:^|[^0-9])(\\d{4,8})[^0-9]{0,8}(?:${CODE_WORDS})`, 'i'));
  if (m) return m[1];
  return null;
}

function parseBody(buf, ctype) {
  const s = buf.toString('utf8').trim();
  if (!s) return {};
  if (ctype.includes('json') || s.startsWith('{') || s.startsWith('[')) {
    try {
      const j = JSON.parse(s);
      return Array.isArray(j) ? (j[0] || {}) : j;
    } catch { return { text: s }; }
  }
  if (ctype.includes('form-urlencoded') || /^[^=&\s]+=[^&]*/.test(s)) {
    try { return Object.fromEntries(new URLSearchParams(s)); } catch { return { text: s }; }
  }
  return { text: s };   // 直接把短信原文当 body 发过来 —— 最省事的一种
}

/** 各家转发工具字段名不一样，能认的都认一遍 */
function takeFrom(obj) {
  const g = (...keys) => {
    for (const k of keys) {
      for (const kk of Object.keys(obj)) if (kk.toLowerCase() === k) return obj[kk];
    }
    return undefined;
  };
  return {
    text: g('text', 'msg', 'message', 'content', 'body', 'sms', 'shortmessage'),
    from: g('from', 'sender', 'phone', 'number', 'address'),
    deviceTs: g('ts', 'time', 'timestamp', 'date', 'receivetime'),
  };
}

// ★ 控制报文不当短信看。
//   短信桥 App 会拿同一个接口上报诊断信息，而诊断里含「收件箱最近几条」——
//   那里面天然带着别处的验证码文本，会被抽成一个**错的**码。
//   这类报文只记日志，不落码。
const CONTROL_FROM = new Set(['SMSBRIDGE-DIAG', 'notif-connect']);

const server = createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const token = req.headers['x-token'] || url.searchParams.get('token') || '';
  const json = (code, obj) => {
    res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
  };

  if (url.pathname === '/health') {
    return json(200, { ok: true, service: 'smsbridge-hub', uptime: Math.round(process.uptime()) });
  }

  // 令牌不对一律 401 —— 这是局域网里的一个公开端口，不能变成谁都能写的垃圾桶。
  if (token !== opt.token) {
    logLine(`⚠ 令牌不对的请求：${req.method} ${url.pathname}，来自 ${req.socket.remoteAddress}`);
    return json(401, { ok: false, error: 'token 不对（拼错了？还是手机端没配？）' });
  }

  if (url.pathname === '/latest') {
    let j = null;
    try { j = JSON.parse(readFileSync(OUT_FILE, 'utf8')); } catch { /* 还没收到过 */ }
    return json(200, j || { code: null });
  }

  if (url.pathname === '/sms' && req.method === 'POST') {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > 64 * 1024) { req.destroy(); return; }   // 短信不会有 64KB，超了就是打错地址了
      chunks.push(c);
    });
    req.on('end', () => {
      const obj = parseBody(Buffer.concat(chunks), String(req.headers['content-type'] || ''));
      const { text, from, deviceTs } = takeFrom(obj);
      const body = String(text || '').trim();
      const src = from ? String(from) : '未知来源';
      const head = src.replace(/^notif:/, '通知·');

      if (CONTROL_FROM.has(String(from || ''))) {
        logLine(`[控制报文·${head}] ${body.slice(0, 400) || '(空)'}`);
        return json(200, { ok: false, control: true, error: '控制报文，不抽码' });
      }

      const code = pickCode(body);
      if (!code) {
        logLine(`[${head}] 收到一条，但没抽出验证码：${body.slice(0, 200) || '(空 body)'}`);
        return json(200, { ok: false, error: '没抽出验证码', got: body.slice(0, 400) });
      }

      const rec = {
        code,
        text: body,
        from: src,
        ts: Date.now(),                                     // ★ 服务器时间（见下）
        deviceTs: deviceTs ? String(deviceTs) : '',
        at: nowText(),
      };
      writeFileSync(OUT_FILE, JSON.stringify(rec), 'utf8');
      logLine(`★ 验证码 ${code}  来自 ${head}｜原文：${body.slice(0, 200)}`);
      return json(200, { ok: true, code });
    });
    return;
  }

  json(404, { ok: false, error: `没有这个路由：${req.method} ${url.pathname}` });
});

// ★ 端口不能「绑上就算数」。
//
// 实测踩过：某台机器上有个第三方程序占着 127.0.0.1:8800，而 **Windows 允许
// 0.0.0.0:8800 和 127.0.0.1:8800 同时监听** —— 地址更具体的那个赢。
// 于是 listen 回调照常触发、日志照常打印「已监听 0.0.0.0:8800」，
// 但本机请求全被那个程序接走，返回一个没有 content-type 的空 404。
// 光看日志会以为一切正常，实际一个包都收不到。
//
// 所以：绑上之后**必须自测一次**（真打一发 /health 并核对响应内容），
// 通了才算这个端口是我们的；不通就换下一个。
function selfTest(port, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = httpGet({ host: '127.0.0.1', port, path: '/health', timeout: timeoutMs }, (res) => {
      let b = '';
      res.on('data', (c) => (b += c));
      res.on('end', () => {
        try { resolve(JSON.parse(b).service === 'smsbridge-hub'); } catch { resolve(false); }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.on('error', () => resolve(false));
  });
}

let PORT = null;
for (let p = opt.port; p < opt.port + 20; p++) {
  const bound = await new Promise((r) => {
    const onErr = () => r(false);
    server.once('error', onErr);
    server.listen(p, '0.0.0.0', () => { server.off('error', onErr); r(true); });
  });
  if (!bound) continue;
  if (await selfTest(p)) { PORT = p; break; }
  console.log(`[!] ${p} 绑上了但自测打不通 —— 大概率被别的程序占着 127.0.0.1:${p}，换一个`);
  await new Promise((r) => server.close(r));
}

if (PORT === null) {
  console.log(`[x] ${opt.port}~${opt.port + 19} 都被占了或打不通，服务起不来`);
  process.exit(1);
}

logLine(`收码服务已起：监听 0.0.0.0:${PORT}（自测通过 ✔）`);
logLine(`验证码落盘到：${OUT_FILE}`);
logLine(`令牌文件：${TOKEN_FILE}`);
logLine('');
logLine('手机端「短信桥」里的电脑地址填下面这个（挑一个你手机能连上的网段）：');
const ips = lanIps();
if (!ips.length) logLine('  ⚠ 没找到非回环的 IPv4 地址 —— 手机可能连不上，检查一下网络');
for (const { name, addr } of ips) logLine(`  ${addr}:${PORT}    [${name}]`);
logLine('');
logLine(`自测：curl -X POST "http://127.0.0.1:${PORT}/sms?token=${opt.token}" -d "【某某】验证码 123456，5分钟内有效。"`);
logLine('');
logLine('⚠ Windows 防火墙默认拦入站，手机连不上就先放行端口（需要管理员）：');
logLine(`  netsh advfirewall firewall add rule name="smsbridge-hub" dir=in action=allow protocol=TCP localport=${PORT}`);

process.on('SIGINT', () => { logLine('收到 Ctrl+C，退出'); process.exit(0); });
