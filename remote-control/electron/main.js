// Electron main-процесс: поднимает встроенный сервер панели и открывает его
// в собственном окне приложения (без внешнего браузера).

import { app, BrowserWindow, Menu, shell, dialog } from 'electron';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const appRoot = path.join(__dirname, '..');

// Единственный экземпляр приложения.
if (!app.requestSingleInstanceLock()) {
  app.quit();
}

// Ищем adb: сначала переменная окружения, затем папка platform-tools рядом
// с приложением (можно просто положить её рядом с .exe), иначе — из PATH.
function resolveAdb() {
  const exe = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const candidates = [
    process.env.ADB_PATH,
    path.join(process.resourcesPath || appRoot, 'platform-tools', exe),
    path.join(path.dirname(app.getPath('exe')), 'platform-tools', exe),
    path.join(appRoot, 'platform-tools', exe),
  ].filter(Boolean);
  for (const c of candidates) {
    try { if (fs.existsSync(c)) return c; } catch { /* ignore */ }
  }
  return exe; // положимся на PATH
}

let mainWindow = null;

async function createWindow(url) {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 840,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0f1216',
    title: 'Android Remote Panel',
    icon: path.join(appRoot, 'build', process.platform === 'win32' ? 'icon.ico' : 'icon.png'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  // Внешние ссылки (например, на scrcpy) открываем в системном браузере.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { label: 'Файл', submenu: [{ role: 'quit', label: 'Выход' }] },
    { label: 'Вид', submenu: [
      { role: 'reload', label: 'Обновить' },
      { role: 'toggleDevTools', label: 'Инструменты разработчика' },
      { type: 'separator' },
      { role: 'resetZoom', label: 'Сбросить масштаб' },
      { role: 'zoomIn', label: 'Крупнее' },
      { role: 'zoomOut', label: 'Мельче' },
      { type: 'separator' },
      { role: 'togglefullscreen', label: 'Полный экран' },
    ] },
  ]));

  await mainWindow.loadURL(url);
  mainWindow.on('closed', () => { mainWindow = null; });
}

app.whenReady().then(async () => {
  process.env.ADB_PATH = resolveAdb();

  let info;
  try {
    // Импортируем сервер уже после установки ADB_PATH.
    const { startServer } = await import('../server.js');
    // 0.0.0.0 — чтобы телефон в той же сети мог прислать поток камеры.
    info = await startServer({ host: '0.0.0.0', port: 0 }); // 0 = свободный порт
  } catch (e) {
    dialog.showErrorBox('Не удалось запустить сервер', String(e && e.message || e));
    app.quit();
    return;
  }

  await createWindow(`http://127.0.0.1:${info.port}`);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow(`http://127.0.0.1:${info.port}`);
  });
});

app.on('second-instance', () => {
  if (mainWindow) { if (mainWindow.isMinimized()) mainWindow.restore(); mainWindow.focus(); }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
