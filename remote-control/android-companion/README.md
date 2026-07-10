# Camera Companion (Android)

Минимальное приложение-компаньон: запрашивает **только доступ к камере** и стримит
её в панель Android Remote Panel (JPEG-кадры по WebSocket на `/camera?role=phone`).

## Как собирается

- **Из панели** (вкладка «Билдинг APK») — панель пишет параметры в
  `build.properties.generated` и запускает Gradle. Нужен установленный Android SDK.
- **Через GitHub Actions** — workflow `.github/workflows/build-apk.yml`
  (собирает на раннере с готовым Android SDK, APK кладётся в артефакты).
- **Локально**:
  ```bash
  cd android-companion
  ./gradlew assembleDebug      # или: gradle assembleDebug
  # APK: app/build/outputs/apk/debug/app-debug.apk
  ```

## Параметры сборки (`build.properties.generated`, необязательный файл)

| Ключ | Назначение |
|------|------------|
| `ARP_APP_NAME` | Название приложения (label) |
| `ARP_APPLICATION_ID` | `applicationId` (package) |
| `ARP_PERMISSIONS` | Список разрешений (пока поддерживается `CAMERA`) |
| `ARP_DEFAULT_SERVER` | Адрес панели по умолчанию (IP:порт), зашивается в APK |

Если файла нет — берутся значения по умолчанию, проект всё равно собирается.

## Стек

- CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) — доступ к камере
- OkHttp — WebSocket-клиент, передача JPEG-кадров
- minSdk 24, targetSdk 34, Kotlin 1.9, AGP 8.1
