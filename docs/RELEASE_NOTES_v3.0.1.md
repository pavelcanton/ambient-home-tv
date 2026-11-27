# Ambient Home 3.0.1 (`versionCode=19`)

Релиз стабилизации, безопасности и доступности для **Ambient Home 3.x** — исправляет все критические и средние замечания, выявленные в ходе полного статического аудита кода и аудита LAN Web-пульта (`6` HIGH + `12` MEDIUM + полировка LOW), сохраняя 0 внешних зависимостей, `~25 МБ` RAM и `0%` GPU в простое.

> **Обновление поверх v3.0.0 без потери настроек:** релиз подписан тем же постоянным RSA-4096 сертификатом (`CN=Ambient Home, O=pavelcanton`, SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`) — можно устанавливать поверх `3.0.0` через `adb install -r` или автоматически через **Open Shelf / Obtainium**.

---

## Что исправлено и усилено в v3.0.1

### Надёжность таймера сна, Blackout и жизненного цикла
- **Идемпотентность таймера сна и корректный AudioFocus (`SleepTimerController.kt`):** добавлен потокобезопасный автомат поколений (`SleepTriggerState`), `cancelAlarm()` вызывается сразу при старте последовательности сна (поздний будильник `AlarmManager` больше не может повторно запустить сон); `AudioFocusRequest` корректно освобождается через `abandonAudioFocusRequest()`; отмена во время 4.5-секундного fade немедленно отменяет отложенные задачи и возвращает исходную громкость `STREAM_MUSIC`; `SleepTimerReceiver` безопасно удерживает `goAsync()` без лишних разрешений.
- **Чёрный ночной экран (Sleep Blackout) в 3 сценариях (`MainActivity.kt`, `HomeCanvasView.kt`, `WallpaperSurfaceView.kt`):**
  1. Отмена `ambientIdleTask` в `enterSleepBlackout()` и `onResume(blackoutOnResume=true)` — таймаут заставки больше не пробуждает чёрный экран обратно к ярким обоям.
  2. Разделение `blackout` и `showClock` (`AmbientOverlayData.showClock`) — `WallpaperSurfaceView` рисует сплошной `Color.BLACK` даже при выключенных часах.
  3. Немедленная синхронизация (`forceSync=true`, 0 мс задержки) при переходе в Blackout, даже если Ambient-заставка уже активна.
- **Потокобезопасность `LauncherStore` (`LauncherStore.kt`, `LanWebRemoteService.kt`):** все операции чтения-модификации-записи и первичная генерация PIN синхронизированы общим замком `STORE_LOCK`, исключая гонки между `MainActivity`, `SettingsActivity` и 4 потоками Web-пульта.
- **Защита цепочек диалогов (`MainActivity.kt`, `SettingsActivity.kt`, `MainFeatureCoordinator.kt`, `SettingsExtraHelper.kt`):** каждый `OnDismissListener` проверяет идентичность (`activeDialog === dlg`), чтобы асинхронное закрытие диалога #1 не затирало ссылку на уже открытый диалог #2; `bootTickTask` отменяется в `onPause()`, а асинхронные UI-коллбэки проверяют `isFinishing || isDestroyed`.
- **Аналоговые Ambient-часы на 720p / 1080p / 4K (`AmbientModels.kt`, `AmbientClockRenderer.kt`):** пересчитана геометрия 4 углов (`AmbientClockLayout`) — дата, погода и строка «Сейчас играет» выравниваются от безопасного края и гарантированно помещаются внутри safe-zone (`<= 1044u`) без обрезания.
- **Обои «Фото дня», Погода, Медиа и JSON (`OnlineWallpaperProvider.kt`, `WeatherClient.kt`, `MediaSessionBridge.kt`, `AudioDeviceObserver.kt`, `MiniJson.kt`, `HomeWidgetsRenderer.kt`):**
  - Цикл чтения заголовка JPEG в `streamWithMagicCheck` устойчив к фрагментации TLS-пакетов; очистка кэша сортирует по `lastModified()` (файлы Wikimedia больше не блокируют обновление Bing); добавлен синхронизированный 15-минутный оффлайн-backoff.
  - Все HTTP-чтения JSON ограничены `256 КБ` (`262_144` Б); кэш погоды переведён на единый атомарный `@Volatile`-снимок с монотонным `elapsedRealtime()`.
  - Защищены отложенные коллбэки `MediaSessionBridge` и `AudioDeviceObserver` после `stop()`; ужесточён `MiniJson` (`\uXXXX` строго 4 hex-цифры, границы `Int`, запрет `Infinity`/`NaN`); строка `♫` в `HomeWidgetsRenderer` запекается с `0` аллокаций на кадр и surrogate-safe обрезкой.

### Безопасность, производительность и доступность LAN Web-пульта
- **Без открытого PIN в бэкапах (`SettingsBackupCodec.kt`):** `webRemote.pin` больше не записывается в `ambient_home_backup.json` (включая копию в `/sdcard/Download/`), а при импорте сохраняется действующий PIN устройства.
- **Защита HTTP-сервера (`LanWebRemoteServer.kt`, `LanWebRemoteRouter.kt`):** добавлены общие дедлайны запроса (`10 с` заголовки / `30 с` тело -> `408 Request Timeout`), строгие заголовки `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy`, корректное закрытие при TCP EOF (без ложного `431`), исключена двойная проверка авторизации и добавлена поддержка `Expect: 100-continue`.
- **Кэш приложений и загрузка обоев (`AppRepository.kt`, `LanWebRemoteService.kt`, `WallpaperRepository.kt`):** `/api/status` использует лёгкий 30-секундный кэш `{package, label, virtual}` без сканирования PM и запекания иконок; добавлены `@Volatile` для `cachedHtml`, `AtomicBoolean` для буфера обмена, очистка `.tmp_*` в `finally`, а порог пользовательских обоев выровнен до `64 Б` с проверкой сигнатуры JPEG/PNG/WebP.
- **PWA Web-пульт — ноль необработанных ошибок и WCAG 2.2 AA (`res/raw/web_remote.html`):**
  - Отклоняемый `429`/оффлайн PIN больше не сохраняется в `localStorage`; все 12 обработчиков и таймеров гарантируют `0` `UnhandledPromiseRejection`; централизованная русская локализация ошибок `errMsg()` (никогда не выводит `«Ошибка: undefined»`).
  - При выходе и `401` очищаются `#pinInput` и `#loginErr`.
  - Доступность и мобильный UX: разрешён pinch-to-zoom, `touch-action: manipulation`, `:focus-visible`, `prefers-reduced-motion`, `<main>`, `aria-label` на всех 11 иконочных кнопках и 6 полях, `role="status"` / `role="alert"`, цели нажатия `>= 44×44 px`, фильтрация AFR по белому списку `[0,24,25,30,50,60]`, отправка по `Enter`, защита от 0-байтных файлов и состояние загрузки на кнопке.

---

## Проверка сборки и контрольные суммы

| Артефакт | Значение |
| --- | --- |
| **Версия** | `3.0.1` (`versionCode=19`, `minSdk=26`, `targetSdk=33`) |
| **Файлы релиза** | `AmbientHome-3.0.1.apk` (+ `AmbientHome-3.0.1.apk.sha256`) |
| **Размер релизного APK** | `4,105,175` байт (`3.91 MB`) |
| **SHA-256 релизного APK** | `88e3feecad346d12f060b849a4dd9c4caec92dacc55bbd43e231217ac9dc3b13` |
| **R8 `classes.dex`** | `364,092` байт (`STORED`, 4-байтное выравнивание `mod4=0`, SHA-256 `54ad782da89d02fe8a32dcdf3f65ab0c62d21a9feb76e3c039738f9a3e4c0836`) |
| **JVM Unit-тесты** | `591 / 591` PASS (`0` FAIL в 5 наборах) |
| **PWA Node VM-тесты** | `60 / 60` PASS (`node --check` OK, `0` unhandled rejections, `0` `innerHTML`) |
| **Сертификат подписи** | `CN=Ambient Home, O=pavelcanton` (RSA 4096-bit, `apksigner` v2+v3, v4 отключён) |
| **SHA-256 сертификата** | `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c` |

> **Проверка перед установкой:** сверьте `sha256sum -c AmbientHome-3.0.1.apk.sha256` (Linux/macOS) или `(Get-FileHash AmbientHome-3.0.1.apk -Algorithm SHA256).Hash` (Windows) с `88e3feecad346d12f060b849a4dd9c4caec92dacc55bbd43e231217ac9dc3b13`, а также подпись сертификата (`CN=Ambient Home, O=pavelcanton`, SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`).

---

## English Summary

**Ambient Home 3.0.1 (`versionCode=19`)** is a comprehensive hardening, security, and accessibility patch release addressing all `6` HIGH and `12` MEDIUM findings (plus clean LOW polish) from static code and PWA audits while keeping `0` external dependencies, `~25 MB` RAM, and `0%` idle GPU load.

- **Lifecycle & Core Stability:** Strictly idempotent Sleep Timer (`SleepTriggerState` + `cancelAlarm()` + proper `AudioFocusRequest` release + immediate volume restore on mid-fade cancel + `goAsync()`); fixed all 3 Sleep Blackout edge cases (idle-wake guard, `showClock=false` blackout support, instant 0ms sync when already in Ambient); process-wide `STORE_LOCK` eliminating `LauncherStore` multi-thread RMW races; identity-guarded chained dialog `OnDismissListener`s; 720p/1080p/4K safe-zone geometry for `ANALOG` and all 4 Ambient clocks; TLS partial-read JPEG handling, mtime-based cache eviction, 15m offline backoff, 256 KiB HTTP JSON caps, monotonic weather staleness, stopped-observer callback guards, and hardened `MiniJson`.
- **Security & PWA WCAG 2.2 AA:** Omitted plaintext Web Remote PIN from `ambient_home_backup.json` (preserving device PIN on import); added Slowloris request deadlines (`10s` header / `30s` body -> `408`), `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, strict `Content-Security-Policy`, clean TCP EOF, single-pass auth, `Expect: 100-continue`, and cached lightweight `/api/status` app listing; hardened `web_remote.html` with `0` unhandled rejections, `429`/offline PIN persistence guard, Russian error mapping (`errMsg`), PIN clearing on logout/`401`, pinch-to-zoom, `:focus-visible`, `aria-label` on all icon buttons & inputs, `>= 44x44px` touch targets, and whitelisted AFR rates.
- **Verified Official Release Build:** `AmbientHome-3.0.1.apk` (`4,105,175` bytes / `3.91 MB`), SHA-256 `88e3feecad346d12f060b849a4dd9c4caec92dacc55bbd43e231217ac9dc3b13`, R8 `classes.dex` `364,092` bytes (`STORED mod4=0`, SHA-256 `54ad782da89d02fe8a32dcdf3f65ab0c62d21a9feb76e3c039738f9a3e4c0836`), `591/591` JVM unit tests PASS, `60/60` Node VM PWA checks PASS, signed with permanent RSA-4096 certificate `CN=Ambient Home, O=pavelcanton` (`apksigner` v2+v3, cert SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`).
