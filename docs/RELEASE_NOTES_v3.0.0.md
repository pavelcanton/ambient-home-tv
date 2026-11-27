# Ambient Home 3.0.0 (`versionCode=18`)

Первый публичный open-source релиз **Ambient Home** — сверхлёгкого лаунчера и Ambient-заставки для Android TV без рекламы и телеметрии, написанного на чистом Kotlin (`0` Gradle, `0` AndroidX, `~25 МБ` RAM, `0%` GPU в простое).

> **Проверено 24/7 на HK1 RBOX K8 (Rockchip RK3528):** непрерывные тесты на **HK1 RBOX K8** (`RK3528`, `4× Cortex-A53`, `Mali-450 MP`, `4 ГБ RAM`, `Android 13 / API 33`) подтвердили **~25,1 МБ Total PSS**, **0% нагрузки на GPU в простое**, **0 падений** и **0 лагов Accessibility** — при **100% работе без root** на любом Android TV / Google TV (`minSdk=26`, `targetSdk=33`).

> **Язык интерфейса:** в v3.0.0 экранный интерфейс ТВ и Web-пульта выполнен на **русском языке (`ru`)**. PR сообщества с английским (`res/values/strings.xml`) и другими переводами приветствуются.

---

## Что нового и ключевые возможности

- **Док в стиле Apple TV и сетка всех приложений:** закреплённое избранное (`★`), ручной порядок иконок в доке, скрытие приложений, мгновенный отклик на D-pad и заранее запечённые полупрозрачные панели.
- **Авто-ночной кино-режим:** настраиваемое расписание (по умолчанию `22:00–07:00`) + режимы Авто / Вкл / Выкл; тёплый янтарный оттенок и дополнительное затемнение запекаются в статичные `RGB_565` обои вне UI-потока (`0%` нагрузки на GPU), одновременно снижается яркость часов.
- **4 стиля Ambient-часов с защитой от выгорания:** Тонкий, Кино, Минимал и Аналоговые с перескоком по 4 углам раз в 15 минут и мягким сдвигом пикселей.
- **Панель быстрых действий:** меню в один клик (кнопка на верхней панели или клавиша `MENU`) — Ambient сейчас, следующие обои, ночной кино-режим, таймер сна, карточка LAN Web-пульта (URL + PIN), мягкий переподъём Wi-Fi и безопасное извлечение USB.
- **Таймер сна для гостиной:** отсчёт 15 / 30 / 60 / 90 минут, отменяемое предупреждение за 15 секунд до сна, плавное снижение громкости + пауза медиа и переход в сверхтёмный чёрный ночной Ambient (плюс опциональный аппаратный сон HDMI через root-мост).
- **Умные виджеты и утилиты:**
  - **Погода Open-Meteo** (опционально, без API-ключей, кэш только в RAM, обновление не чаще раза в 45 мин при открытом `HOME`).
  - **Пилюля «Сейчас играет»** (опциональная строка медиа с нулевой нагрузкой в простое через разрешение `NotificationListener` по ADB).
  - **Обои «Фото дня»** (опциональная ротация Bing `HPImageArchive` + Wikimedia Commons POTD, максимум 3 проверенных JPEG, оффлайн-фолбэк) + 5 встроенных пейзажей без EXIF, свои обои из `/sdcard/Pictures/Wallpapers` и градиенты.
  - **Индикатор Bluetooth-аудио без сканирования** (пассивный `AudioDeviceCallback`, `0` BT-сканирований).
  - **Плитка Wake-on-LAN**, **автозапуск после холодного старта с ожиданием Wi-Fi** и **читаемый JSON-бэкап/восстановление настроек**.
- **Встроенный LAN Web-пульт PWA и REST API (`:8765`):** управление ТВ с любого телефона/ПК в локальной сети (`http://<TV_IP>:8765`), защита случайным 6-значным `SecureRandom` PIN (`X-Auth-Token`, сравнение за постоянное время, блокировка `429` на 15 с после 5 ошибок). Поддерживает автоматизацию MacroDroid / Home Assistant / `curl` и полностью отключается в Настройках.
- **Кроссплатформенная сборка без Gradle:** чистый Kotlin `2.1.10` + R8 через Windows `fetch-toolchain.ps1` + `build.ps1` или Linux/macOS `./build.sh` с опциональной релизной подписью (`AMBIENT_KEYSTORE*`), подтверждённые **491/491** pure-JVM unit-тестами.

---

## Обычный Android TV (без root) vs опциональный Root-мост

Ambient Home **не требует root** на обычных Android TV / Google TV приставках:
- **Работает без root:** док и сетка приложений, обои, ночной кино-режим, Ambient-часы, таймер сна (затухание громкости + пауза медиа + чёрный ночной Ambient), погода, индикатор BT-аудио, пилюля «Сейчас играет» (через ADB `NotificationListener`), WoL, автозапуск после холодного старта, JSON-бэкап и LAN Web-пульт (телеметрия, запуск приложений, ночной режим, таймер сна, настройки погоды/WoL, загрузка обоев).
- **Опциональный Root-мост (`extras/root-bridge/`):** на рутированных Android TV приставках (включая RK3528 / HK1 RBOX K8) установка опционального FIFO-моста (`files/remote.fifo`) дополнительно открывает системные клавиши D-pad, вставку UTF-8 из буфера обмена, Force-Stop, переключение частоты AFR, безопасное извлечение USB и аппаратный сон HDMI (`KEYCODE_SLEEP`). Подробнее в `extras/root-bridge/README.md` и `docs/HK1_RBOX_K8_NOTES.md`.

---

## Установка и автообновление

### Скрипт быстрой установки (рекомендуется)

**Windows (PowerShell):**

```powershell
.\scripts\install-tv.ps1 -Device <TV_IP>:5555 -NowPlaying -SetHome
```

**Linux / macOS:**

```bash
chmod +x ./scripts/install-tv.sh
./scripts/install-tv.sh --device <TV_IP>:5555 --now-playing --set-home
```

### Ручная установка через ADB

```bash
adb connect <TV_IP>:5555
adb install -r AmbientHome-3.0.0.apk
adb shell cmd package compile -m speed -f com.will.ambienthome
adb shell cmd package set-home-activity com.will.ambienthome/.MainActivity
# Опционально: включить пилюлю «Сейчас играет» (сохраняется после перезагрузки)
adb shell cmd notification allow_listener com.will.ambienthome/com.will.ambienthome.data.AmbientNotificationListener
```

### Автообновление через Open Shelf / Obtainium

Релиз подписан постоянным релизным сертификатом (`CN=Ambient Home, O=pavelcanton`), поэтому **Open Shelf** и **Obtainium** могут автоматически обновлять приложение напрямую из `https://github.com/pavelcanton/ambient-home-tv/releases` без переустановки и потери настроек.

---

## Проверка сборки и контрольные суммы

| Артефакт | Значение |
| --- | --- |
| **Версия** | `3.0.0` (`versionCode=18`, `minSdk=26`, `targetSdk=33`) |
| **Файлы релиза** | `AmbientHome-3.0.0.apk` (+ `AmbientHome-3.0.0.apk.sha256`) |
| **Размер релизного APK** | `4,084,695` байт (`3.90 MB`) |
| **SHA-256 релизного APK** | `96610aebe504cb424bc435d534aec784860918758c35686cc94b5cdb8e63ecc5` |
| **R8 `classes.dex`** | `346,556` байт (`STORED`, 4-байтное выравнивание `mod4=0`, SHA-256 `43d69ab04d81792e550349ab82d6b96dd7d8f5b4831ad346fb7dc19281bbf3c6`) |
| **JVM Unit-тесты** | `491 / 491` PASS (`0` FAIL в 5 наборах) |
| **Сертификат подписи** | `CN=Ambient Home, O=pavelcanton` (RSA 4096-bit, `apksigner` v2+v3, v4 отключён) |
| **SHA-256 сертификата** | `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c` |

> **Проверка перед установкой:** сверьте `sha256sum -c AmbientHome-3.0.0.apk.sha256` (Linux/macOS) или `(Get-FileHash AmbientHome-3.0.0.apk -Algorithm SHA256).Hash` (Windows) с `96610aebe504cb424bc435d534aec784860918758c35686cc94b5cdb8e63ecc5`, а также подпись сертификата (`CN=Ambient Home, O=pavelcanton`, SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`).

---

## English Summary

**Ambient Home 3.0.0 (`versionCode=18`)** is the first public open-source release of our ultra-lightweight, ad-free, zero-telemetry Android TV launcher & living-room ambient display written in pure Kotlin (`0` Gradle, `0` AndroidX, `~25 MB` RAM, `0%` idle GPU).

- **Works 100% unrooted** on Android TV / Google TV (`minSdk=26`, `targetSdk=33`), soak-tested 24/7 on HK1 RBOX K8 (RK3528, Android 13), with an optional root FIFO bridge (`extras/root-bridge/`) for system-wide D-pad, paste, force-stop, AFR, USB eject, and hardware HDMI sleep.
- **Features:** Apple TV-style dock & grid, 4 burn-in-safe Ambient clocks (4-corner hop), Auto-Night Cinema warm amber mode (`0%` extra GPU), Quick Control Sheet, Living-Room Sleep Timer, zero-scan BT audio badge, Open-Meteo Weather, Now Playing pill, Photo of the Day wallpapers, Wake-on-LAN, cold-boot auto-launch, JSON settings backup/restore, and PIN-protected LAN Web Remote PWA & REST API (`:8765`). UI is currently **Russian-first (`ru`)**; English documentation is in [README.en.md](https://github.com/pavelcanton/ambient-home-tv/blob/main/README.en.md).
- **Verified Official Release Build:** `AmbientHome-3.0.0.apk` (`4,084,695` bytes / `3.90 MB`), SHA-256 `96610aebe504cb424bc435d534aec784860918758c35686cc94b5cdb8e63ecc5`, R8 `classes.dex` `346,556` bytes (`STORED mod4=0`), `491/491` JVM unit tests PASS, signed with permanent RSA-4096 certificate `CN=Ambient Home, O=pavelcanton` (`apksigner` v2+v3, cert SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`, Open Shelf / Obtainium auto-update ready).
