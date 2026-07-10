Сюда можно положить adb (Android platform-tools), и он будет собран
вместе с приложением — тогда пользователю не нужно ставить adb в систему.

Windows: скопируй в эту папку файлы adb.exe, AdbWinApi.dll, AdbWinUsbApi.dll
из архива SDK Platform-Tools:
https://developer.android.com/tools/releases/platform-tools

macOS / Linux: скопируй сюда бинарник adb.

Приложение ищет adb в таком порядке:
  1. переменная окружения ADB_PATH
  2. platform-tools/adb(.exe) рядом с приложением (эта папка)
  3. adb из системного PATH

Если оставить папку пустой — приложение возьмёт adb из PATH (нужно установить
platform-tools в систему отдельно).
