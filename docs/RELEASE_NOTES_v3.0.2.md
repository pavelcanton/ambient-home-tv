# Ambient Home 3.0.2 (`versionCode=20`)

Релиз оптимизации производительности, сжатия APK и полировки безопасности для **Ambient Home 3.x** — устраняет все оставшиеся аллокации в `onDraw()`, уменьшает размер APK на **23,75% (`-974,8 КБ`)** за счёт точных `1920×1080` WebP-обоев, изолирует сетевой I/O от локального UI, убирает лишние пробуждения таймера сна и 25-IPC пересканы приложений при возврате на HOME, а также закрывает все LOW-замечания аудита.

> **Обновление поверх v3.0.0 / v3.0.1 без потери настроек:** релиз подписан тем же постоянным RSA-4096 сертификатом (`CN=Ambient Home, O=pavelcanton`, SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`) — можно устанавливать поверх предыдущей версии через `adb install -r` или автоматически через **Open Shelf / Obtainium**.

---

## Что оптимизировано и усилено в v3.0.2

### Производительность, сжатие APK и ноль аллокаций на кадр
- **Честные 0 аллокаций в горячем пути `onDraw()` (`ui/HomeSurfaceCache.kt`, `model/AmbientModels.kt`, `ui/HomeWidgetsRenderer.kt`, `ui/TopBarRenderer.kt`):**
  - В `ensureWeatherPill()` и `ensureSleepPill()` строковые ключи кэша заменены на примитивные/ссылочные поля (аналогично `ensureWifiPill()`).
  - `NowPlayingState.displayTitle`/`displaySubtitle`, `WeatherSnapshot.pillTempText`/`ambientSummary` и `SleepTimerState.badgeText` переведены в вычисляемые при создании `val`-поля (0 строковых аллокаций на кадре 60 fps).
  - Удалён избыточный вызов `syncVisibleActions()` внутри `TopBarRenderer.draw()`; при переходе в сетку `GRID` освобождается `dockBgBmp` (зеркально вытеснению `gridBgBmp` в `DOCK`, по-прежнему `0` HWUI `.recycle()`).
- **Точные `1920×1080` WebP-обои (`res/drawable-nodpi/w1..w5.webp`):** все 5 встроенных пейзажей обрезаны по центру до `1920×1080` и перекодированы в WebP (`q=85`, `method=6`, 0 EXIF, PSNR `36,5–39,5 дБ`, визуально без бандинга и артефактов) — размер обоев снижен с `3 713 277` Б до `2 732 706` Б (`-980 571` Б / `-26,4%`), размер релизного APK упал с `4 105 175` Б (`3,91 МиБ`) до **`3 130 332` Б (`2,99 МиБ`)**, а пиковый буфер декодирования `w1` уменьшился на `25%`.
- **Разделение `localIo` / `netIo` и оффлайн-backoff погоды (`MainActivity.kt`, `data/WallpaperController.kt`, `MainFeatureCoordinator.kt`, `data/WeatherClient.kt`):** быстрый локальный I/O (`PackageManager`, запекание обоев, статус Wi-Fi) изолирован от внешнего HTTPS (`Open-Meteo`, `Bing`/`Wikimedia`) — отсутствие Интернета больше никогда не блокирует локальный интерфейс. В `WeatherClient` добавлена синхронизированная защита `fetchLock` от параллельных дублей и 15-минутный монотонный оффлайн-backoff.
- **Умные пробуждения и перерисовки таймера сна (`data/SleepTimerController.kt`, `ui/HomeCanvasView.kt`):** `tickTask` полностью останавливается при уходе в фон (`0` фоновых пробуждений — срабатывание обеспечивает `AlarmManager`) и возобновляется в `attachListeners()`; на главном экране тики выровнены по границе минуты при `remainingSec > 60` и переключаются на 1с лишь в последние 60 секунд; `HomeCanvasView.setWidgetsState()` пропускает `syncTopMeta()` + `invalidate()`, если `active`/`badgeText`/`warningActive` и баннер не изменились.
- **Быстрый 1-IPC путь `PackageManager.getChangedPackages()` и `prepareToDraw()` (`MainActivity.kt`, `data/AppRepository.kt`):** рутинный возврат на `HOME` проверяет изменения пакетов за 1 Binder IPC вместо полного 25–30 IPC сканирования (полное обновление по-прежнему выполняется при первом запуске, установке/удалении пакетов, возврате из Настроек или изменении WoL). На запечённые плитки добавлен `masked.prepareToDraw()`.
- **Микрооптимизации (`data/LanWebRemoteServer.kt`, `data/WallpaperRepository.kt`, `data/MediaSessionBridge.kt`, `data/SystemStatusHelper.kt`):** пропуск лишних `setsockopt(SO_RCVTIMEO)` при непустом буфере `input.available() > 0` (~600 сисколлов -> 1–2 на запрос); `allowCoreThreadTimeOut(true)` (30с) для воркеров Web-пульта; метаданные `stat()` вместо открытия каждого файла в `customFilesSignature()`; кэш `lastPkg -> lastLabel` в `MediaSessionBridge.resolveAppLabel()`; ранний выход из чтения `/proc/meminfo` после нахождения `MemTotal` и `MemAvailable`.

### Безопасность, доступность PWA и чистота скриптов
- **Ограниченная очередь HTTP-воркеров (`503`) и монотонный lockout PIN (`data/LanWebRemoteServer.kt`, `data/LanWebRemoteRouter.kt`):** вместо неограниченного пула используется `ThreadPoolExecutor(4, 4, 30s, ArrayBlockingQueue(32))` с корректным ответом `503 Service Unavailable` (`service_unavailable`) при переполнении; 15-секундная блокировка PIN переведена на монотонный `SystemClock.elapsedRealtime()`.
- **Белый список HTTPS-хостов «Фото дня» (`data/OnlineWallpaperProvider.kt`):** строгая проверка `https://` (`bing.com`, `*.bing.com`, `upload.wikimedia.org`, без `userInfo`, порт `443`) при парсинге JSON и до/после редиректа в `downloadJpeg()`.
- **Локализация ошибок PWA и контраст WCAG 1.4.11 (`res/raw/web_remote.html`):** в `ERR_MAP` добавлены русские сообщения для `forbidden_package`, `bridge_not_running` и `service_unavailable`; контраст границ `--bd` повышен до `rgba(255,255,255,.34)` (`3,04:1–3,11:1 >= 3,0:1` WCAG 1.4.11 на всех тёмных поверхностях).
- **Чистота shell / PowerShell скриптов (`build.sh`, `scripts/install-tv.sh`, `fetch-toolchain.ps1`):** устранены предупреждения `shellcheck SC1007` (`CDPATH='' cd`) и `PSScriptAnalyzer PSShouldProcess` (`[CmdletBinding(SupportsShouldProcess = $true)]` для `Install-Archive`).

---

## Проверка сборки и контрольные суммы

| Артефакт | Значение |
| --- | --- |
| **Версия** | `3.0.2` (`versionCode=20`, `minSdk=26`, `targetSdk=33`) |
| **Файлы релиза** | `AmbientHome-3.0.2.apk` (+ `AmbientHome-3.0.2.apk.sha256`) |
| **Размер релизного APK** | `3,130,332` байт (`2.99 MB`, `-974,843` Б / `-23.75%` к `v3.0.1`) |
| **SHA-256 релизного APK** | `55e69a609bcdf101f28fd33def13110bd3ec7abfaf74a314e2c48ce754a9708b` |
| **R8 `classes.dex`** | `368,408` байт (`359.8 KB`, `STORED`, 4-байтное выравнивание `mod4=0`, SHA-256 `b551340daf2c65343fa0e5ee52e2ed6eb67a55ee0fac4291f10c36a5e14b6ae1`) |
| **JVM Unit-тесты** | `645 / 645` PASS (`0` FAIL в 5 наборах, `+54` новых теста) |
| **PWA Node VM-тесты** | `60 / 60` PASS (`node --check` OK, `0` unhandled rejections, `0` `innerHTML`, `--bd` `3.04–3.11:1`) |
| **Сертификат подписи** | `CN=Ambient Home, O=pavelcanton` (RSA 4096-bit, `apksigner` v2+v3, v4 отключён) |
| **SHA-256 сертификата** | `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c` |

> **Проверка перед установкой:** сверьте `sha256sum -c AmbientHome-3.0.2.apk.sha256` (Linux/macOS) или `(Get-FileHash AmbientHome-3.0.2.apk -Algorithm SHA256).Hash` (Windows) с `55e69a609bcdf101f28fd33def13110bd3ec7abfaf74a314e2c48ce754a9708b`, а также подпись сертификата (`CN=Ambient Home, O=pavelcanton`, SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`).

---

## English Summary

**Ambient Home 3.0.2 (`versionCode=20`)** is a performance, APK compression, and security-polish release implementing every remaining optimization and LOW audit polish item while keeping `0` external dependencies, `~25 MB` RAM, and `0%` idle GPU load.

- **Zero-Alloc `onDraw()` & 23.75% Smaller APK:** Eliminated per-frame Weather/Sleep pill key concatenations, precomputed `NowPlayingState.displayTitle`/`displaySubtitle`, `WeatherSnapshot.pillTempText`/`ambientSummary`, and `SleepTimerState.badgeText` as stored `val`s, removed redundant `syncVisibleActions()` from `TopBarRenderer.draw()`, and evicted `dockBgBmp` on `GRID` entry (`0` HWUI `.recycle()`). Center-cropped all 5 built-in wallpapers to exact `1920×1080` WebP (`q=85`, `0` EXIF, visually verified), cutting APK size from `4,105,175 B` (`3.91 MiB`) to **`3,130,332 B` (`2.99 MiB`, `-974,843 B` / `-23.75%`)**.
- **I/O Isolation, 1-IPC Resume & Smart Sleep Ticks:** Split `MainActivity` background I/O into `localIo` and `netIo`, added synchronized in-flight dedup + 15m monotonic offline backoff to `WeatherClient`, stopped background Sleep Timer ticks when detached (`0` background wakeups) with minute-aligned foreground ticks (`> 60s`) and diff-guarded redraws, added 1-IPC `PackageManager.getChangedPackages()` fast path on `onResume()` + `masked.prepareToDraw()` on baked tiles, and applied micro-optimizations in `LanWebRemoteServer` (`input.available() == 0` guard + 30s core thread timeout), `WallpaperRepository` (metadata-only signature), `MediaSessionBridge` (cached app label), and `SystemStatusHelper` (`/proc/meminfo` early break).
- **Security, WCAG 1.4.11 & Lint Polish:** Bounded HTTP worker queue (`4` workers, queue `32`, clean `503 service_unavailable`), monotonic `elapsedRealtime()` PIN lockout, strict HTTPS host allowlist (`*.bing.com`, `upload.wikimedia.org`) for daily wallpapers, localized `forbidden_package`/`bridge_not_running`/`service_unavailable` PWA toasts, `--bd` border contrast `rgba(255,255,255,.34)` (`3.04–3.11:1 >= 3.0:1`), and clean `shellcheck` (`CDPATH='' cd`) & `PSScriptAnalyzer` (`SupportsShouldProcess`) scripts.
- **Verified Official Release Build:** `AmbientHome-3.0.2.apk` (`3,130,332` bytes / `2.99 MB`), SHA-256 `55e69a609bcdf101f28fd33def13110bd3ec7abfaf74a314e2c48ce754a9708b`, R8 `classes.dex` `368,408` bytes (`STORED mod4=0`, SHA-256 `b551340daf2c65343fa0e5ee52e2ed6eb67a55ee0fac4291f10c36a5e14b6ae1`), `645/645` JVM unit tests PASS, `60/60` Node VM PWA checks PASS, signed with permanent RSA-4096 certificate `CN=Ambient Home, O=pavelcanton` (`apksigner` v2+v3, cert SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`).
