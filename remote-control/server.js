// Локальный сервер веб-панели управления Android через ADB.
// Запуск: npm start  (по умолчанию http://localhost:8787)

import express from 'express';
import multer from 'multer';
import { WebSocketServer } from 'ws';
import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
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

// ---- Сервер + WebSocket ----
const server = http.createServer(app);
// Два WebSocket-канала на одном сервере: /ws (управление панелью) и
// /camera (поток камеры телефона: role=phone публикует, role=viewer смотрит).
const wss = new WebSocketServer({ noServer: true });
const wssCamera = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  if (pathname === '/ws') {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  } else if (pathname === '/camera') {
    wssCamera.handleUpgrade(req, socket, head, (ws) => wssCamera.emit('connection', ws, req));
  } else {
    socket.destroy();
  }
});

// Релей камеры: телефон (role=phone) шлёт бинарные JPEG-кадры,
// панель (role=viewer) их получает. Храним последний кадр и статус телефона.
const cameraViewers = new Set();
let phoneSocket = null;

function notifyViewers(obj) {
  const data = JSON.stringify(obj);
  for (const v of cameraViewers) if (v.readyState === v.OPEN) v.send(data);
}

wssCamera.on('connection', (ws, req) => {
  const role = new URL(req.url, 'http://localhost').searchParams.get('role') || 'viewer';
  if (role === 'phone') {
    phoneSocket = ws;
    notifyViewers({ type: 'phone', connected: true });
    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        for (const v of cameraViewers) if (v.readyState === v.OPEN) v.send(data, { binary: true });
      }
    });
    ws.on('close', () => { if (phoneSocket === ws) phoneSocket = null; notifyViewers({ type: 'phone', connected: false }); });
  } else {
    cameraViewers.add(ws);
    ws.send(JSON.stringify({ type: 'phone', connected: !!phoneSocket }));
    ws.on('close', () => cameraViewers.delete(ws));
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
