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

// ---------- Камера в левой панели ----------
let camFrameCount = 0, lastCamFpsTime = Date.now();

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

// Отправка текста из левой панели
$('#sendText').onclick = () => {
  const v = $('#textInput').value;
  if (v) { ctrlSend({ type: 'text', text: v }); $('#textInput').value = ''; }
};
$('#textInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#sendText').click(); });


// ---------- Вкладки пикера ----------
document.querySelectorAll('.picker-tab').forEach((tab) => {
  tab.onclick = () => {
    document.querySelectorAll('.picker-tab').forEach((t) => t.classList.remove('active'));
    document.querySelectorAll('.picker-panel').forEach((p) => p.classList.remove('active'));
    tab.classList.add('active');
    $('#ptab-' + tab.dataset.ptab).classList.add('active');
    if (tab.dataset.ptab === 'build') loadBuildTab();
  };
});

// ---------- Вкладки панели устройства ----------
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
  };
});

// ---------- Экран выбора устройства ----------
let pickerTimer = null;

function showPicker() {
  $('#picker').style.display = 'flex';
  $('.layout').style.display = 'none';
  $('#backBtn').style.display = 'none';
  refreshPicker();
  pickerTimer = setInterval(refreshPicker, 2000);
}

function hidePicker(selectedIp) {
  clearInterval(pickerTimer);
  pickerTimer = null;
  $('#picker').style.display = 'none';
  $('.layout').style.display = 'flex';
  $('#backBtn').style.display = '';
}

async function refreshPicker() {
  try {
    const data = await api('phones');
    renderPickerPhones(data.phones || [], data.activeIp);
  } catch { /* ignore network errors while waiting */ }
}

function countryFlag(code) {
  if (!code || code.length !== 2) return '🌐';
  return String.fromCodePoint(
    code.toUpperCase().charCodeAt(0) - 65 + 0x1F1E6,
    code.toUpperCase().charCodeAt(1) - 65 + 0x1F1E6
  );
}

function renderPickerPhones(phones, activeIp) {
  const list = $('#phoneGrid');
  const status = $('#pickerStatus');
  list.innerHTML = '';
  if (!phones.length) {
    status.style.display = '';
    return;
  }
  status.style.display = 'none';
  for (const p of phones) {
    const strip = document.createElement('div');
    strip.className = 'phone-strip';
    const flag = countryFlag(p.countryCode);
    const city = p.city || '';
    strip.innerHTML = `
      <span class="ps-flag">${flag}</span>
      <span class="ps-info">
        <span class="ps-model">${p.model || 'Android'}</span>
        <span class="ps-sub">
          <span class="ps-ip">${p.label}</span>
          ${city ? `<span class="ps-city">${city}</span>` : ''}
        </span>
      </span>
      <span class="ps-arrow">›</span>`;
    strip.onclick = () => selectPhone(p.ip);
    list.appendChild(strip);
  }
}

async function selectPhone(ip) {
  try {
    await jpost('phones/select', { ip });
    hidePicker();
    startCameraView();
  } catch (e) { toast(e.message, true); }
}

$('#backBtn').onclick = showPicker;
$('#pickerRefresh').onclick = refreshPicker;

// ---------- Экран телефона (MediaProjection) ----------
let wsScreenViewer = null;
let wsControlChannel = null;
let screenRendering = false;
let screenPendingData = null;

function renderScreenFrame() {
  if (!screenPendingData) { screenRendering = false; return; }
  screenRendering = true;
  const data = screenPendingData;
  screenPendingData = null;
  const url = URL.createObjectURL(new Blob([data], { type: 'image/jpeg' }));
  const img = $('#phoneScreen');
  const prev = img.dataset.url;
  img.onload = () => { if (prev) URL.revokeObjectURL(prev); renderScreenFrame(); };
  img.onerror = () => { if (prev) URL.revokeObjectURL(prev); renderScreenFrame(); };
  img.src = url;
  img.dataset.url = url;
}

function startScreenViewer() {
  if (wsScreenViewer && wsScreenViewer.readyState < 2) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  wsScreenViewer = new WebSocket(`${proto}://${location.host}/screen?role=viewer`);
  wsScreenViewer.binaryType = 'arraybuffer';
  wsScreenViewer.onopen = () => {
    $('#phoneScreenStatus').textContent = 'ожидание телефона…';
    $('#phoneScreenStatus').classList.remove('on');
  };
  wsScreenViewer.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      try {
        const m = JSON.parse(ev.data);
        if (m.type === 'screen-phone') {
          if (m.connected) {
            $('#phoneScreenStatus').textContent = 'ожидание скриншота…';
            $('#phoneScreenStatus').classList.remove('on');
          } else {
            $('#phoneScreenStatus').textContent = 'телефон не подключён';
            $('#phoneScreenStatus').classList.remove('on');
          }
        }
      } catch {}
      return;
    }
    screenPendingData = ev.data;
    if (!screenRendering) renderScreenFrame();
    const hint = $('#phoneScreenHint');
    if (hint) hint.style.display = 'none';
    $('#phoneScreenStatus').textContent = 'подключён';
    $('#phoneScreenStatus').classList.add('on');
  };
  wsScreenViewer.onclose = () => {
    $('#phoneScreenStatus').textContent = 'нет соединения';
    $('#phoneScreenStatus').classList.remove('on');
    setTimeout(startScreenViewer, 2000);
  };
  wsScreenViewer.onerror = () => {};
}

function startControlChannel() {
  if (wsControlChannel && wsControlChannel.readyState < 2) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  wsControlChannel = new WebSocket(`${proto}://${location.host}/control?role=viewer`);
  wsControlChannel.onclose = () => setTimeout(startControlChannel, 2000);
  wsControlChannel.onerror = () => {};
}

function ctrlSend(obj) {
  if (wsControlChannel && wsControlChannel.readyState === WebSocket.OPEN)
    wsControlChannel.send(JSON.stringify(obj));
}

// Тап / свайп на экране телефона (MediaProjection)
const phoneScreenEl = $('#phoneScreen');
let phoneDownPt = null, phoneDownTime = 0;
phoneScreenEl.addEventListener('mousedown', (e) => {
  if (!phoneScreenEl.naturalWidth) return;
  phoneDownPt = normFromEvent(e, phoneScreenEl);
  phoneDownTime = Date.now();
  e.preventDefault();
});
window.addEventListener('mouseup', (e) => {
  if (!phoneDownPt) return;
  const up = normFromEvent(e, phoneScreenEl);
  const dist = Math.hypot(up.x - phoneDownPt.x, up.y - phoneDownPt.y);
  const dt = Date.now() - phoneDownTime;
  if (dist < 0.02 && dt < 400) ctrlSend({ type: 'tap', x: phoneDownPt.x, y: phoneDownPt.y });
  else ctrlSend({ type: 'swipe', x1: phoneDownPt.x, y1: phoneDownPt.y, x2: up.x, y2: up.y, ms: Math.min(600, Math.max(100, dt)) });
  phoneDownPt = null;
});
phoneScreenEl.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { ctrlSend({ type: 'key', name: 'BACK' }); e.preventDefault(); }
  else if (e.key === 'Enter') { ctrlSend({ type: 'text', text: '\n' }); e.preventDefault(); }
  else if (e.key === 'Backspace') { ctrlSend({ type: 'key', name: 'DEL' }); e.preventDefault(); }
  else if (e.key.length === 1) { ctrlSend({ type: 'text', text: e.key }); e.preventDefault(); }
});
document.querySelectorAll('[data-ctrl-key]').forEach((btn) => {
  btn.onclick = () => ctrlSend({ type: 'key', name: btn.dataset.ctrlKey });
});
$('#screenQuality').addEventListener('input', (e) => {
  const q = parseInt(e.target.value);
  $('#screenQualityVal').textContent = q + '%';
  ctrlSend({ type: 'screen-quality', quality: q });
});

$('#phoneTextInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#phoneSendText').click(); });
$('#phoneSendText').onclick = () => {
  const v = $('#phoneTextInput').value;
  if (v) { ctrlSend({ type: 'text', text: v }); $('#phoneTextInput').value = ''; }
};

// ---------- Трансляция камеры телефона ----------
const camWS = { back: null, front: null };
const phoneConnected = { back: false, front: false };
let currentLeftCam = 'back';

function startCamViewer(cam) {
  const ws = camWS[cam];
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const sock = new WebSocket(`${proto}://${location.host}/camera?role=viewer&cam=${cam}`);
  sock.binaryType = 'arraybuffer';
  sock.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      const m = JSON.parse(ev.data);
      if (m.type === 'phone') { phoneConnected[cam] = m.connected; updatePhoneStatus(); }
      return;
    }
    if (cam !== currentLeftCam) return;
    const url = URL.createObjectURL(new Blob([ev.data], { type: 'image/jpeg' }));
    const img = $('#camLeft');
    if (img.dataset.url) URL.revokeObjectURL(img.dataset.url);
    img.src = url; img.dataset.url = url;
    const hint = $('#screenHint');
    if (hint) hint.style.display = 'none';
    camFrameCount++;
    const now = Date.now();
    if (now - lastCamFpsTime >= 1000) {
      $('#fps').textContent = `${camFrameCount} fps`;
      camFrameCount = 0; lastCamFpsTime = now;
    }
  };
  sock.onclose = () => { phoneConnected[cam] = false; updatePhoneStatus(); };
  camWS[cam] = sock;
}

$('#btnCamSwitch').onclick = () => {
  currentLeftCam = currentLeftCam === 'back' ? 'front' : 'back';
  $('#btnCamSwitch').textContent = currentLeftCam === 'back' ? '🤳 Фронт' : '📷 Зад';
  ctrlSend({ type: 'cam-switch', cam: currentLeftCam });
};

// ---------- Аудио с телефона ----------
let audioCtx = null;
let audioWs = null;
let audioMuted = true;
let nextAudioTime = 0;
const AUDIO_SAMPLE_RATE = 16000;

function startAudioViewer() {
  if (audioWs && (audioWs.readyState === WebSocket.OPEN || audioWs.readyState === WebSocket.CONNECTING)) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  audioWs = new WebSocket(`${proto}://${location.host}/audio?role=viewer`);
  audioWs.binaryType = 'arraybuffer';
  audioWs.onmessage = (ev) => {
    if (audioMuted || typeof ev.data === 'string') return;
    if (!audioCtx) return;
    const int16 = new Int16Array(ev.data);
    const float32 = new Float32Array(int16.length);
    for (let i = 0; i < int16.length; i++) float32[i] = int16[i] / 32768;
    const buf = audioCtx.createBuffer(1, float32.length, AUDIO_SAMPLE_RATE);
    buf.copyToChannel(float32, 0);
    const src = audioCtx.createBufferSource();
    src.buffer = buf;
    src.connect(audioCtx.destination);
    const now = audioCtx.currentTime;
    if (nextAudioTime < now + 0.02) nextAudioTime = now + 0.06;
    src.start(nextAudioTime);
    nextAudioTime += buf.duration;
  };
  audioWs.onclose = () => {};
}

$('#camMute').onclick = () => {
  if (!audioCtx) {
    audioCtx = new AudioContext({ sampleRate: AUDIO_SAMPLE_RATE });
    nextAudioTime = 0;
  }
  if (audioCtx.state === 'suspended') audioCtx.resume();
  audioMuted = !audioMuted;
  $('#camMute').textContent = audioMuted ? '🔇 Тихо' : '🔊 Звук';
};

let touchLocked = false;
$('#btnTouchLock').onclick = () => {
  touchLocked = !touchLocked;
  $('#btnTouchLock').textContent = touchLocked ? '🔒 Касания' : '🔓 Касания';
  ctrlSend({ type: 'touch-lock', locked: touchLocked });
};

function startCameraView() {
  startCamViewer('back');
  startCamViewer('front');
  startAudioViewer();
  startScreenViewer();
  startControlChannel();
}

function updatePhoneStatus() {
  const on = phoneConnected.back || phoneConnected.front;
  const hint = $('#screenHint');
  if (hint && on) hint.style.display = 'none';
}

// ---------- Билдинг APK ----------
async function loadBuildTab() {
  try {
    const info = await api('serverinfo');
    if (info.addresses && info.addresses.length && !$('#bServer').value) {
      $('#bServer').value = `${info.addresses[0].address}:${info.port}`;
    }
  } catch { /* ignore */ }
  const saved = localStorage.getItem('arp_gh_token');
  if (saved && !$('#bGhToken').value) $('#bGhToken').value = saved;
}

$('#bGhToken').addEventListener('change', () => {
  const v = $('#bGhToken').value.trim();
  if (v) localStorage.setItem('arp_gh_token', v);
  else localStorage.removeItem('arp_gh_token');
});

$('#buildBtn').onclick = async () => {
  const msg = $('#buildMsg');
  if (!$('#bServer').value.trim()) {
    msg.textContent = 'Укажи адрес панели — он зашивается в APK, чтобы телефон знал куда подключаться.';
    $('#bServer').focus();
    return;
  }
  msg.textContent = 'Сборка…';
  const perms = [];
  if ($('#permCamera').checked) perms.push('CAMERA');
  if ($('#permMic').checked) perms.push('RECORD_AUDIO');
  if ($('#permScreen').checked) perms.push('SCREEN');
  const fd = new FormData();
  fd.append('appName', $('#bAppName').value);
  fd.append('applicationId', $('#bAppId').value);
  fd.append('permissions', perms.join(','));
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
    } else if (res.status === 501) {
      const token = $('#bGhToken').value.trim();
      if (token) {
        await buildViaGitHub(token, msg, perms);
      } else {
        msg.textContent = 'Android SDK не найден. Введи GitHub Token выше — APK соберётся автоматически с нужным IP и скачается сюда.';
        $('#bGhToken').focus();
      }
    } else {
      const d = await res.json().catch(() => ({}));
      msg.textContent = 'Ошибка сборки: ' + (d.error || res.statusText);
    }
  } catch (e) { msg.textContent = 'Ошибка: ' + e.message; }
};

async function buildViaGitHub(token, msgEl, perms = []) {
  msgEl.textContent = 'Отправляем задание на GitHub Actions…';
  try {
    const trigRes = await fetch('/api/build/github', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        appName: $('#bAppName').value,
        applicationId: $('#bAppId').value,
        defaultServer: $('#bServer').value,
        permissions: perms.join(','),
        token,
      }),
    });
    const td = await trigRes.json();
    if (!trigRes.ok) {
      msgEl.textContent = 'Ошибка GitHub API: ' + (td.error || trigRes.statusText);
      return;
    }
    const { runId, runUrl } = td;
    if (!runId) {
      msgEl.innerHTML = `Сборка запущена, но ID запуска не получили. <a href="${runUrl}" target="_blank">Открыть Actions</a>`;
      return;
    }
    msgEl.innerHTML = `Сборка идёт (#${runId})… это займёт ~2-3 мин.<br><a href="${runUrl}" target="_blank">Открыть в GitHub Actions</a>`;

    const poll = setInterval(async () => {
      try {
        const sr = await fetch(`/api/build/github/status?runId=${runId}&token=${encodeURIComponent(token)}`);
        const sd = await sr.json();
        if (sd.status === 'completed') {
          clearInterval(poll);
          if (sd.conclusion === 'success') {
            msgEl.innerHTML = 'Сборка готова! Скачиваем APK…';
            const a = document.createElement('a');
            a.href = `/api/build/github/artifact?runId=${runId}&token=${encodeURIComponent(token)}`;
            a.download = 'camera-companion-apk.zip';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => {
              msgEl.innerHTML = 'APK скачан (zip-архив — внутри <b>app-debug.apk</b>). Установи на телефон и выдай доступ к камере.<br><a href="' + sd.url + '" target="_blank">Посмотреть сборку</a>';
            }, 1500);
          } else {
            msgEl.innerHTML = `Ошибка сборки (${sd.conclusion}). <a href="${sd.url}" target="_blank">Смотри лог в Actions</a>`;
          }
        }
      } catch { /* ignore transient poll errors */ }
    }, 12000);
  } catch (e) { msgEl.textContent = 'Ошибка: ' + e.message; }
}

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
loadDashboard();
showPicker();
