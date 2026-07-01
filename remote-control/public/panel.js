// Клиент веб-панели. Общается с сервером по REST (/api/*) и WebSocket (/ws).

const $ = (s) => document.querySelector(s);
const api = async (path, opts) => {
  const res = await fetch('/api/' + path, opts);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
};
const jpost = (path, body) => api(path, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

function toast(msg, isErr) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show' + (isErr ? ' err' : '');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (t.className = 'toast'), 2600);
}

// ---------- WebSocket: стрим экрана + ввод ----------
let ws = null;
let streaming = false;
let deviceSize = { width: 1080, height: 1920 };
let frameCount = 0, lastFpsTime = Date.now();

function connectWS() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.binaryType = 'arraybuffer';

  ws.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      const msg = JSON.parse(ev.data);
      if (msg.type === 'size') deviceSize = { width: msg.width, height: msg.height };
      else if (msg.type === 'error') toast(msg.message, true);
      return;
    }
    // Бинарный фрейм PNG
    const blob = new Blob([ev.data], { type: 'image/png' });
    const url = URL.createObjectURL(blob);
    const img = $('#screen');
    if (img.dataset.url) URL.revokeObjectURL(img.dataset.url);
    img.src = url;
    img.dataset.url = url;

    frameCount++;
    const now = Date.now();
    if (now - lastFpsTime >= 1000) {
      $('#fps').textContent = `${frameCount} fps`;
      frameCount = 0; lastFpsTime = now;
    }
  };

  ws.onclose = () => { streaming = false; $('#btnStream').textContent = '▶ Экран'; setTimeout(connectWS, 1500); };
  ws.onerror = () => {};
}

function wsSend(obj) { if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); }

$('#btnStream').onclick = () => {
  streaming = !streaming;
  if (streaming) {
    wsSend({ type: 'start-stream' });
    $('#btnStream').textContent = '⏸ Стоп';
    $('#screenHint').style.display = 'none';
  } else {
    wsSend({ type: 'stop-stream' });
    $('#btnStream').textContent = '▶ Экран';
  }
};

// Нормализованные координаты клика по картинке (учёт «letterbox» внутри contain)
function normFromEvent(e, img) {
  const rect = img.getBoundingClientRect();
  const natRatio = img.naturalWidth / img.naturalHeight;
  const boxRatio = rect.width / rect.height;
  let dispW = rect.width, dispH = rect.height, offX = 0, offY = 0;
  if (natRatio > boxRatio) { dispH = rect.width / natRatio; offY = (rect.height - dispH) / 2; }
  else { dispW = rect.height * natRatio; offX = (rect.width - dispW) / 2; }
  const x = (e.clientX - rect.left - offX) / dispW;
  const y = (e.clientY - rect.top - offY) / dispH;
  return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) };
}

// Тап / свайп мышью
let downPt = null, downTime = 0;
const screenEl = $('#screen');
screenEl.addEventListener('mousedown', (e) => {
  if (!screenEl.naturalWidth) return;
  downPt = normFromEvent(e, screenEl); downTime = Date.now(); e.preventDefault();
});
window.addEventListener('mouseup', (e) => {
  if (!downPt) return;
  const up = normFromEvent(e, screenEl);
  const dist = Math.hypot(up.x - downPt.x, up.y - downPt.y);
  const dt = Date.now() - downTime;
  if (dist < 0.02 && dt < 400) wsSend({ type: 'tap', x: downPt.x, y: downPt.y });
  else wsSend({ type: 'swipe', x1: downPt.x, y1: downPt.y, x2: up.x, y2: up.y, ms: Math.min(600, Math.max(100, dt)) });
  downPt = null;
});

// Клавиатура при фокусе на экране
screenEl.tabIndex = 0;
screenEl.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { wsSend({ type: 'key', name: 'ENTER' }); e.preventDefault(); }
  else if (e.key === 'Backspace') { wsSend({ type: 'key', name: 'DEL' }); e.preventDefault(); }
  else if (e.key === 'Tab') { wsSend({ type: 'key', name: 'TAB' }); e.preventDefault(); }
  else if (e.key.length === 1) { wsSend({ type: 'text', text: e.key }); e.preventDefault(); }
});

// Кнопки навигации (общие data-key)
document.querySelectorAll('[data-key]').forEach((btn) => {
  btn.onclick = () => wsSend({ type: 'key', name: btn.dataset.key });
});

// Отправка текста из поля
$('#sendText').onclick = () => {
  const v = $('#textInput').value;
  if (v) { wsSend({ type: 'text', text: v }); $('#textInput').value = ''; }
};
$('#textInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#sendText').click(); });

// ---------- Устройства ----------
async function loadDevices() {
  try {
    const { devices, current } = await api('devices');
    const sel = $('#deviceSelect');
    sel.innerHTML = '';
    if (!devices.length) {
      sel.innerHTML = '<option value="">нет устройств</option>';
      $('#connState').classList.remove('on');
      return;
    }
    for (const d of devices) {
      const opt = document.createElement('option');
      opt.value = d.serial;
      opt.textContent = `${d.model || d.serial} (${d.state})`;
      if (d.serial === current) opt.selected = true;
      sel.appendChild(opt);
    }
    const online = devices.some((d) => d.state === 'device');
    $('#connState').classList.toggle('on', online);
    if (!current && devices[0]) await selectDevice(devices[0].serial);
  } catch (e) { toast(e.message, true); }
}

async function selectDevice(serial) {
  await jpost('select', { serial });
  loadDashboard();
}

$('#deviceSelect').onchange = (e) => selectDevice(e.target.value);
$('#refreshDevices').onclick = loadDevices;

$('#wifiConnect').onclick = async () => {
  const hp = $('#wifiHost').value.trim();
  if (!hp) return;
  try { const r = await jpost('connect', { hostPort: hp }); toast(r.message || 'ok'); loadDevices(); }
  catch (e) { toast(e.message, true); }
};

// ---------- Вкладки ----------
document.querySelectorAll('.tab').forEach((tab) => {
  tab.onclick = () => {
    document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach((c) => c.classList.remove('active'));
    tab.classList.add('active');
    $('#tab-' + tab.dataset.tab).classList.add('active');
    if (tab.dataset.tab === 'apps') loadApps();
    if (tab.dataset.tab === 'files') loadFiles($('#filePath').value);
    if (tab.dataset.tab === 'notifs') loadNotifs();
    if (tab.dataset.tab === 'camera') startCameraView();
    if (tab.dataset.tab === 'build') loadBuildTab();
  };
});

// ---------- Трансляция камеры телефона ----------
let camWS = null;
function startCameraView() {
  if (camWS && (camWS.readyState === WebSocket.OPEN || camWS.readyState === WebSocket.CONNECTING)) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  camWS = new WebSocket(`${proto}://${location.host}/camera?role=viewer`);
  camWS.binaryType = 'arraybuffer';
  camWS.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      const m = JSON.parse(ev.data);
      if (m.type === 'phone') setPhoneStatus(m.connected);
      return;
    }
    const url = URL.createObjectURL(new Blob([ev.data], { type: 'image/jpeg' }));
    const img = $('#camImg');
    if (img.dataset.url) URL.revokeObjectURL(img.dataset.url);
    img.src = url; img.dataset.url = url;
    $('#camHint').style.display = 'none';
  };
  camWS.onclose = () => setPhoneStatus(false);
}
function setPhoneStatus(on) {
  const el = $('#camStatus');
  el.textContent = on ? 'телефон подключён' : 'телефон не подключён';
  el.classList.toggle('on', !!on);
}

// ---------- Билдинг APK ----------
async function loadBuildTab() {
  try {
    const info = await api('serverinfo');
    if (info.addresses && info.addresses.length && !$('#bServer').value) {
      $('#bServer').value = `${info.addresses[0].address}:${info.port}`;
    }
  } catch { /* ignore */ }
}

$('#buildBtn').onclick = async () => {
  const msg = $('#buildMsg');
  if (!$('#bServer').value.trim()) {
    msg.textContent = 'Укажи адрес панели — он зашивается в APK, чтобы телефон знал куда подключаться.';
    $('#bServer').focus();
    return;
  }
  msg.textContent = 'Сборка… (первая может занять несколько минут)';
  const fd = new FormData();
  fd.append('appName', $('#bAppName').value);
  fd.append('applicationId', $('#bAppId').value);
  fd.append('permissions', 'CAMERA');
  fd.append('defaultServer', $('#bServer').value);
  const icon = $('#bIcon').files[0];
  if (icon) fd.append('icon', icon);
  try {
    const res = await fetch('/api/build/apk', { method: 'POST', body: fd });
    if (res.ok) {
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = ($('#bAppName').value || 'app') + '.apk';
      a.click(); URL.revokeObjectURL(url);
      msg.textContent = 'Готово — APK скачан. Установи его на телефон и выдай доступ к камере.';
    } else {
      const d = await res.json().catch(() => ({}));
      if (res.status === 501) {
        msg.innerHTML = (d.error || 'Нет Android SDK') +
          '\n\nБыстрый путь: открой вкладку <b>Actions</b> в GitHub-репозитории → ' +
          'workflow <b>«Build companion APK»</b> → Run workflow. Скачаешь готовый APK из артефактов.';
      } else {
        msg.textContent = 'Ошибка сборки: ' + (d.error || res.statusText);
      }
    }
  } catch (e) { msg.textContent = 'Ошибка: ' + e.message; }
};

// ---------- Дашборд ----------
async function loadDashboard() {
  try {
    const info = await api('info');
    $('#infoKv').innerHTML = `
      <b>Модель</b><span>${info.manufacturer} ${info.model}</span>
      <b>Android</b><span>${info.androidVersion} (SDK ${info.sdk})</span>
      <b>Разрешение</b><span>${info.resolution}</span>
      <b>Serial</b><span>${info.serialno}</span>`;
  } catch (e) { $('#infoKv').textContent = e.message; }
  try {
    const b = await api('battery');
    const pct = Math.round((b.level / (b.scale || 100)) * 100);
    $('#batteryFill').style.width = pct + '%';
    $('#batteryFill').style.background = pct < 20 ? 'var(--danger)' : 'var(--accent)';
    $('#batteryText').textContent = pct + '%';
    $('#batteryKv').innerHTML = `
      <b>Статус</b><span>${b.status} (${b.plugged})</span>
      <b>Температура</b><span>${b.temperature ?? '—'} °C</span>
      <b>Напряжение</b><span>${b.voltage ?? '—'} мВ</span>
      <b>Здоровье</b><span>${b.health ?? '—'}</span>`;
  } catch (e) { $('#batteryKv').textContent = e.message; }
}
$('#refreshDash').onclick = loadDashboard;

// ---------- Приложения ----------
async function loadApps() {
  const list = $('#appList');
  list.innerHTML = '<li>Загрузка…</li>';
  try {
    const { apps } = await api('apps' + ($('#appsAll').checked ? '?all=1' : ''));
    const filter = $('#appFilter').value.toLowerCase();
    list.innerHTML = '';
    for (const pkg of apps.filter((p) => p.includes(filter))) {
      const li = document.createElement('li');
      li.innerHTML = `<span class="name">${pkg}</span>`;
      const launch = document.createElement('button');
      launch.className = 'sm'; launch.textContent = '▶';
      launch.onclick = () => jpost('apps/launch', { pkg }).then(() => toast('Запущено')).catch((e) => toast(e.message, true));
      const stop = document.createElement('button');
      stop.className = 'sm'; stop.textContent = '⏹';
      stop.onclick = () => jpost('apps/stop', { pkg }).then(() => toast('Остановлено')).catch((e) => toast(e.message, true));
      li.append(launch, stop);
      list.appendChild(li);
    }
    if (!list.children.length) list.innerHTML = '<li>Ничего не найдено</li>';
  } catch (e) { list.innerHTML = `<li>${e.message}</li>`; }
}
$('#refreshApps').onclick = loadApps;
$('#appFilter').addEventListener('input', () => { clearTimeout(loadApps._t); loadApps._t = setTimeout(loadApps, 250); });
$('#appsAll').onchange = loadApps;

// ---------- Файлы ----------
async function loadFiles(p) {
  const list = $('#fileList');
  list.innerHTML = '<li>Загрузка…</li>';
  try {
    const { path, entries } = await api('files?path=' + encodeURIComponent(p));
    $('#filePath').value = path;
    list.innerHTML = '';
    for (const e of entries) {
      const li = document.createElement('li');
      if (e.isDir) li.className = 'dir';
      const icon = e.isDir ? '📁' : '📄';
      const name = document.createElement('span');
      name.className = 'name';
      name.textContent = `${icon} ${e.name}`;
      if (e.isDir) name.onclick = () => loadFiles((path.replace(/\/$/, '')) + '/' + e.name);
      li.appendChild(name);
      if (!e.isDir) {
        const sub = document.createElement('span'); sub.className = 'sub'; sub.textContent = e.size;
        const dl = document.createElement('button');
        dl.className = 'sm'; dl.textContent = '⬇';
        dl.onclick = () => window.open('/api/files/download?path=' + encodeURIComponent((path.replace(/\/$/, '')) + '/' + e.name));
        li.append(sub, dl);
      }
      list.appendChild(li);
    }
    if (!entries.length) list.innerHTML = '<li>Пусто</li>';
  } catch (e) { list.innerHTML = `<li>${e.message}</li>`; }
}
$('#goPath').onclick = () => loadFiles($('#filePath').value);
$('#upPath').onclick = () => {
  const p = $('#filePath').value.replace(/\/$/, '');
  loadFiles(p.substring(0, p.lastIndexOf('/')) || '/');
};
$('#uploadBtn').onclick = async () => {
  const f = $('#uploadInput').files[0];
  if (!f) return toast('Выбери файл', true);
  const fd = new FormData();
  fd.append('file', f);
  fd.append('remoteDir', $('#filePath').value);
  try {
    const r = await fetch('/api/files/upload', { method: 'POST', body: fd });
    const d = await r.json();
    if (!r.ok) throw new Error(d.error);
    toast('Загружено: ' + d.remotePath);
    loadFiles($('#filePath').value);
  } catch (e) { toast(e.message, true); }
};

// ---------- Уведомления ----------
async function loadNotifs() {
  const list = $('#notifList');
  list.innerHTML = '<li>Загрузка…</li>';
  try {
    const { items } = await api('notifications');
    list.innerHTML = '';
    for (const n of items) {
      const li = document.createElement('li');
      li.innerHTML = `<div class="name"><b>${n.title || '(без заголовка)'}</b><div class="sub">${n.pkg}</div>${n.text ? '<div>' + n.text + '</div>' : ''}</div>`;
      list.appendChild(li);
    }
    if (!items.length) list.innerHTML = '<li>Нет уведомлений (или устройство их не отдаёт)</li>';
  } catch (e) { list.innerHTML = `<li>${e.message}</li>`; }
}
$('#refreshNotifs').onclick = loadNotifs;
$('#postNotif').onclick = () => jpost('notifications/post', { title: $('#notifTitle').value, text: $('#notifText').value })
  .then(() => toast('Отправлено')).catch((e) => toast(e.message, true));

// ---------- Старт ----------
connectWS();
loadDevices();
loadDashboard();
setInterval(loadDevices, 8000);
