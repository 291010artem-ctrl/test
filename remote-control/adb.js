// Тонкая обёртка над adb (Android Debug Bridge).
// Все функции работают с одним «текущим» устройством (serial),
// который можно задать через setSerial(). Если serial не задан,
// adb сам выберет единственное подключённое устройство.

import { spawn, execFile } from 'node:child_process';

const ADB = process.env.ADB_PATH || 'adb';
let currentSerial = null;

export function setSerial(serial) {
  currentSerial = serial || null;
}

export function getSerial() {
  return currentSerial;
}

// Добавляет "-s <serial>" перед аргументами, если устройство выбрано.
function withTarget(args) {
  return currentSerial ? ['-s', currentSerial, ...args] : args;
}

// Запускает adb и возвращает stdout как строку (utf8).
export function adb(args, { timeout = 15000 } = {}) {
  return new Promise((resolve, reject) => {
    execFile(ADB, withTarget(args), { timeout, maxBuffer: 32 * 1024 * 1024 },
      (err, stdout, stderr) => {
        if (err) {
          err.stderr = stderr;
          return reject(err);
        }
        resolve(stdout);
      });
  });
}

// Запускает adb и возвращает stdout как Buffer (для бинарных данных, напр. скриншот).
export function adbBinary(args, { timeout = 15000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(ADB, withTarget(args));
    const chunks = [];
    const errChunks = [];
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error('adb timeout'));
    }, timeout);
    child.stdout.on('data', (d) => chunks.push(d));
    child.stderr.on('data', (d) => errChunks.push(d));
    child.on('error', (e) => { clearTimeout(timer); reject(e); });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code !== 0) {
        return reject(new Error(Buffer.concat(errChunks).toString() || `adb exited ${code}`));
      }
      resolve(Buffer.concat(chunks));
    });
  });
}

// adb shell <cmd...> -> строка
export function shell(cmd, opts) {
  const args = Array.isArray(cmd) ? cmd : [cmd];
  return adb(['shell', ...args], opts);
}

// ---- Устройства ----

export async function listDevices() {
  const out = await adb(['devices', '-l'], { timeout: 8000 });
  return out.split('\n').slice(1)
    .map((l) => l.trim())
    .filter(Boolean)
    .map((line) => {
      const [serial, state, ...rest] = line.split(/\s+/);
      const info = {};
      for (const kv of rest) {
        const i = kv.indexOf(':');
        if (i > 0) info[kv.slice(0, i)] = kv.slice(i + 1);
      }
      return { serial, state, model: info.model || '', device: info.device || '' };
    });
}

// Подключение по Wi-Fi: adb connect <ip:port>
export function connect(hostPort) {
  return adb(['connect', hostPort], { timeout: 10000 });
}

export function disconnect(hostPort) {
  return adb(['disconnect', hostPort], { timeout: 8000 });
}

// ---- Экран ----

export function screencap() {
  // exec-out отдаёт «сырой» бинарный PNG без CRLF-искажений
  return adbBinary(['exec-out', 'screencap', '-p'], { timeout: 10000 });
}

let cachedSize = null;
export async function screenSize() {
  if (cachedSize) return cachedSize;
  const out = await shell(['wm', 'size']);
  // "Physical size: 1080x2400" и опционально "Override size: ..."
  const override = out.match(/Override size:\s*(\d+)x(\d+)/);
  const physical = out.match(/Physical size:\s*(\d+)x(\d+)/);
  const m = override || physical;
  cachedSize = m ? { width: +m[1], height: +m[2] } : { width: 1080, height: 1920 };
  return cachedSize;
}

export function clearSizeCache() { cachedSize = null; }

// ---- Ввод ----

export function tap(x, y) {
  return shell(['input', 'tap', String(Math.round(x)), String(Math.round(y))]);
}

export function swipe(x1, y1, x2, y2, ms = 200) {
  return shell(['input', 'swipe',
    String(Math.round(x1)), String(Math.round(y1)),
    String(Math.round(x2)), String(Math.round(y2)), String(Math.round(ms))]);
}

export function keyevent(code) {
  return shell(['input', 'keyevent', String(code)]);
}

// input text не понимает пробелы напрямую — заменяем на %s,
// а спецсимволы экранируем.
export function inputText(text) {
  const escaped = String(text)
    .replace(/ /g, '%s')
    .replace(/([()<>|;&*\\~"'`$])/g, '\\$1');
  return shell(['input', 'text', escaped]);
}

// ---- Дашборд / информация ----

export async function battery() {
  const out = await shell(['dumpsys', 'battery']);
  const num = (k) => { const m = out.match(new RegExp(k + ':\\s*(-?\\d+)')); return m ? +m[1] : null; };
  const str = (k) => { const m = out.match(new RegExp(k + ':\\s*(\\w+)')); return m ? m[1] : null; };
  const statusMap = { 1: 'unknown', 2: 'charging', 3: 'discharging', 4: 'not charging', 5: 'full' };
  return {
    level: num('level'),
    scale: num('scale') || 100,
    temperature: num('temperature') != null ? num('temperature') / 10 : null, // °C
    voltage: num('voltage'),
    status: statusMap[num('status')] || 'unknown',
    plugged: num('AC powered') ? 'AC' : (num('USB powered') ? 'USB' : (num('Wireless powered') ? 'Wireless' : 'none')),
    health: str('health'),
  };
}

export async function deviceInfo() {
  const out = await shell(['getprop']);
  const get = (k) => { const m = out.match(new RegExp('\\[' + k.replace(/\./g, '\\.') + '\\]:\\s*\\[([^\\]]*)\\]')); return m ? m[1] : ''; };
  const size = await screenSize().catch(() => null);
  return {
    manufacturer: get('ro.product.manufacturer'),
    model: get('ro.product.model'),
    androidVersion: get('ro.build.version.release'),
    sdk: get('ro.build.version.sdk'),
    serialno: get('ro.serialno') || get('ro.boot.serialno'),
    resolution: size ? `${size.width}x${size.height}` : '',
  };
}

// ---- Приложения ----

export async function listApps(userOnly = true) {
  const args = ['pm', 'list', 'packages'];
  if (userOnly) args.push('-3'); // только сторонние приложения
  const out = await shell(args);
  return out.split('\n')
    .map((l) => l.replace('package:', '').trim())
    .filter(Boolean)
    .sort();
}

export function launchApp(pkg) {
  return shell(['monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1']);
}

export function forceStop(pkg) {
  return shell(['am', 'force-stop', pkg]);
}

// ---- Уведомления ----

export async function notifications() {
  const out = await shell(['dumpsys', 'notification', '--noredact']).catch(() => '');
  const items = [];
  // Разбираем блоки NotificationRecord
  const re = /pkg=(\S+)[\s\S]*?(?:android\.title=([^\n]*))?(?:[\s\S]*?android\.text=([^\n]*))?/g;
  const blocks = out.split(/NotificationRecord\(/).slice(1);
  for (const b of blocks) {
    const pkg = (b.match(/pkg=(\S+)/) || [])[1] || '';
    const title = (b.match(/android\.title=(?:String \()?([^\)\n]*)/) || [])[1] || '';
    const text = (b.match(/android\.text=(?:String \()?([^\)\n]*)/) || [])[1] || '';
    if (pkg) items.push({ pkg, title: title.trim(), text: text.trim() });
    if (items.length >= 40) break;
  }
  return items;
}

// Отправить (создать) уведомление на телефоне через cmd notification
export function postNotification(title, text) {
  // Работает на многих устройствах: cmd notification post
  const t = String(title).replace(/'/g, "");
  const body = String(text).replace(/'/g, "");
  return shell(['cmd', 'notification', 'post', '-t', `'${t}'`, "'remote_panel'", `'${body}'`]);
}

// ---- Файлы ----

export async function listFiles(path = '/sdcard') {
  const out = await shell(['ls', '-lA', `'${path.replace(/'/g, "'\\''")}'`]).catch((e) => { throw e; });
  const entries = [];
  for (const line of out.split('\n')) {
    const l = line.trim();
    if (!l || l.startsWith('total')) continue;
    const parts = l.split(/\s+/);
    if (parts.length < 8) continue;
    const isDir = l[0] === 'd';
    const name = parts.slice(7).join(' ');
    if (name === '.' || name === '..') continue;
    entries.push({ name, isDir, size: parts[4], perms: parts[0] });
  }
  entries.sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name));
  return entries;
}

export function pushFile(localPath, remotePath) {
  return adb(['push', localPath, remotePath], { timeout: 120000 });
}

export function pullFile(remotePath, localPath) {
  return adb(['pull', remotePath, localPath], { timeout: 120000 });
}

// Коды клавиш Android (часто используемые)
export const KEYCODES = {
  BACK: 4,
  HOME: 3,
  RECENTS: 187,
  POWER: 26,
  VOLUME_UP: 24,
  VOLUME_DOWN: 25,
  MENU: 82,
  ENTER: 66,
  DEL: 67,
  TAB: 61,
  SEARCH: 84,
};
