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

// Разрешения, которые панель умеет включать. Пока — только камера.
// INTERNET нужен для самой трансляции и не показывается пользователю
// (это «обычное» install-time разрешение), поэтому он всегда включён.
export const AVAILABLE_PERMISSIONS = [
  { id: 'CAMERA', manifest: 'android.permission.CAMERA', label: 'Камера', runtime: true, default: true },
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
    `ARP_PERMISSIONS=${perms.join(',')}`,
    `ARP_DEFAULT_SERVER=${cfg.defaultServer || ''}`,
    'org.gradle.jvmargs=-Xmx2048m',
    'android.useAndroidX=true',
  ].join('\n') + '\n';

  await fsp.writeFile(path.join(PROJECT_DIR, 'build.properties.generated'), props);
  return { appName, appId, perms };
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

  if (!hasAndroidSdk()) {
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

  await new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd: PROJECT_DIR, shell: process.platform === 'win32' });
    child.stdout.on('data', (d) => onLog(d.toString()));
    child.stderr.on('data', (d) => onLog(d.toString()));
    child.on('error', reject);
    child.on('close', (code) => code === 0 ? resolve() : reject(new Error('gradle exited ' + code)));
  });

  const apk = path.join(PROJECT_DIR, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
  if (!fs.existsSync(apk)) throw new Error('Сборка прошла, но APK не найден: ' + apk);

  // Копируем в понятное имя.
  const outName = `${meta.appName.replace(/[^\w.-]+/g, '_')}.apk`;
  const outPath = path.join(os.tmpdir(), outName);
  await fsp.copyFile(apk, outPath);
  return { apkPath: outPath, fileName: outName, meta };
}
