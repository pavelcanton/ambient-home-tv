**🇷🇺 Русский** · [🇬🇧 English](README.en.md)

# Ambient Home

[![CI](https://github.com/pavelcanton/ambient-home-tv/actions/workflows/ci.yml/badge.svg)](https://github.com/pavelcanton/ambient-home-tv/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/pavelcanton/ambient-home-tv)](https://github.com/pavelcanton/ambient-home-tv/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**Сверхлёгкий лаунчер и Ambient-заставка для Android TV без рекламы, телеметрии, Gradle и AndroidX — чистый Kotlin Canvas, ~25 МБ RAM (PSS) и 0% нагрузки на GPU в простое.**

[Скриншоты](#скриншоты) · [Возможности](#ключевые-возможности-v301) · [Архитектура](#архитектура-и-производительность) · [Без root vs Root-мост](#работа-без-root-vs-опциональный-root-мост) · [Установка](#установка-и-обновления) · [LAN Web-пульт и API](#lan-web-пульт-pwa-и-rest-api) · [Сборка из исходников](#сборка-из-исходников) · [Приватность](#приватность-и-атрибуция-обоев) · [English README](README.en.md)

> **Язык интерфейса (v3.0.1):** Экранный интерфейс ТВ-лаунчера и встроенного Web-пульта выполнен на **русском языке (`ru`)**. Кодовая база не имеет внешних зависимостей и легко локализуется — PR сообщества с добавлением английского (`res/values/strings.xml`) и других языков приветствуются! См. [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Скриншоты

| Док в стиле Apple TV | Ночной кино-режим (тёплый янтарный тинт без шейдеров) |
| :---: | :---: |
| ![Док Ambient Home](docs/screenshots/dock.png) | ![Ночной кино-режим](docs/screenshots/night_cinema.png) |

| Аналоговые Ambient-часы (защита от выгорания по 4 углам) | Индикатор таймера сна |
| :---: | :---: |
| ![Аналоговая Ambient-заставка](docs/screenshots/ambient_analog.png) | ![Активный таймер сна](docs/screenshots/sleep_timer.png) |

| Настройки и живая диагностика железа |
| :---: |
| ![Настройки Ambient Home](docs/screenshots/settings.png) |

---

## Ключевые возможности (v3.0.1)

- **Лаконичный док и сетка приложений:** полупрозрачный док в стиле Apple TV с избранным (`★`), ручной сортировкой, скрытием лишних приложений и полноэкранной сеткой всех приложений (`minSdk=26`, `targetSdk=33`).
- **4 стиля Ambient-часов с защитой от выгорания:** Тонкий, Кино, Минимал и Аналоговые — с перескоком по 4 углам экрана каждые 15 минут и мягким сдвигом пикселей.
- **Авто-ночной кино-режим:** настраиваемое расписание (по умолчанию `22:00–07:00`) + режимы Авто / Вкл / Выкл. Тёплый янтарный оттенок и дополнительное затемнение запекаются напрямую в статичный `RGB_565` bitmap вне UI-потока (`0%` дополнительной нагрузки на GPU), одновременно снижается яркость часов.
- **Панель быстрых действий:** открывается с кнопки на верхней панели или клавишей `MENU` — мгновенный запуск Ambient, смена обоев, переключение ночного режима, таймер сна, карточка LAN Web-пульта (URL + PIN), мягкий переподъём Wi-Fi и безопасное извлечение USB-накопителя.
- **Таймер сна для гостиной:** обратный отсчёт 15 / 30 / 60 / 90 минут с индикатором-полумесяцем на главном экране и в Ambient, отменяемое предупреждение за 15 секунд до сна, плавное затухание громкости + пауза медиа и переход в сверхтёмный чёрный ночной Ambient (либо настоящий аппаратный сон HDMI при наличии опционального root-моста).
- **Индикатор Bluetooth-аудио без сканирования:** пассивный `AudioManager.AudioDeviceCallback` показывает значок наушников только при активном BT A2DP / BLE-аудио — ноль фоновых Bluetooth-сканирований.
- **Опциональные виджеты и обои:**
  - **Погода (Open-Meteo):** без API-ключей, компактная пилюля и диалог прогноза на 4 дня / по часам, кэш только в RAM (обновляется не чаще раза в 45 минут, пока открыт `HOME`).
  - **Пилюля «Сейчас играет»:** название трека и исполнителя на главном экране и в Ambient через стандартное разрешение `NotificationListener` (`0%` нагрузки в простое).
  - **Обои «Фото дня»:** опциональная ротация Bing `HPImageArchive` + Wikimedia Commons POTD (до 3 проверенных JPEG в кэше с автоматическим оффлайн-фолбэком), а также 5 встроенных пейзажей без EXIF, пользовательские фото из `/sdcard/Pictures/Wallpapers` и процедурные градиенты.
- **Встроенный LAN Web-пульт (PWA) и REST API:** управление ТВ с любого телефона или ПК в домашней Wi-Fi сети (`http://192.168.1.x:8765`), защита случайным 6-значным `SecureRandom` PIN (`X-Auth-Token`) с защитой от перебора. Легко интегрируется с **MacroDroid**, **Home Assistant** и `curl`.
- **Плитка Wake-on-LAN:** виртуальная плитка в доке/сетке для пробуждения домашнего ПК по LAN (UDP magic packet).
- **Умный автозапуск после холодного старта:** опциональный запуск любимого ТВ-приложения один раз после холодной перезагрузки (отслеживается по `/proc/sys/kernel/random/boot_id`) после готовности сети, с отменяемым баннером обратного отсчёта.
- **JSON-бэкап и восстановление настроек:** читаемый файл `ambient_home_backup.json` сохраняет порядок дока, избранные/скрытые приложения, внешний вид, расписание ночного режима, город погоды, WoL и параметры Web-пульта (без сохранения PIN в открытом виде).

---

## Архитектура и производительность

Ambient Home спроектирован так, чтобы работать мгновенно даже на недорогих ARM/Mali ТВ-приставках и оставаться безупречно стабильным на флагманских устройствах.

> **Проверено 24/7 на HK1 RBOX K8 (Rockchip RK3528):** непрерывные длительные тесты на **HK1 RBOX K8** (`Rockchip RK3528`, `4× Cortex-A53`, `Mali-450 MP`, `4 ГБ RAM`, `Android 13 / API 33`) показали **~25,1 МБ Total PSS**, **0% нагрузки на GPU в простое**, **0 падений** и **0 лагов Accessibility** — при **100% совместимости с любыми нерутированными Android TV / Google TV приставками (API 26+)**. См. [docs/HK1_RBOX_K8_NOTES.md](docs/HK1_RBOX_K8_NOTES.md) с полевыми заметками по оптимизации RK3528 / Android 13.

| Показатель | Значение |
| --- | --- |
| **Внешние зависимости** | **0** — только чистый Kotlin `2.1.10` + Android SDK (`android.jar` API 34): **0 AndroidX, 0 Jetpack, 0 Compose, 0 Gradle** |
| **Потребление памяти** | **~25 МБ Total PSS** (`1` Activity, `7` Views, `0` WebViews) |
| **Нагрузка на GPU в простое** | **0%** — обои, затемнение, ночной янтарный тинт и стеклянные панели запекаются заранее вне UI-потока; нет фоновых анимаций в простое |
| **Структура кода** | **38 модулей Deslop** — каждый `.kt` файл `< 600` строк (макс. `550`), функции `<= 50` строк, строгий ациклический DAG (`root -> {ui, data, model}`, `0` импортов `data.*` в `ui/`), `0` вызовов HWUI `.recycle()` |
| **Автотесты** | **591 pure-JVM unit-тест** в 5 наборах + **60 Node VM PWA-проверок** (прогоняются за секунды без эмулятора) |
| **Релизный APK** | **~3,91 МБ R8-сжатый APK** с подписью `apksigner` v2+v3, выравниванием `zipalign -p 4` и несжатым 4-байтно выровненным `STORED` `classes.dex` (`mod4=0`) для zero-copy ART `mmap` |

---

## Работа без root vs опциональный Root-мост

Ambient Home работает **полностью без root** на обычных Android TV / Google TV / AOSP TV устройствах. Приложение никогда не вызывает `su` и не содержит встроенного root-демона.

| Функция | Обычный Android TV (без root) | С опциональным Root-мостом (`files/remote.fifo`) |
| --- | :---: | :---: |
| Док / сетка приложений, избранное, порядок, скрытие | Да | Да |
| Встроенные / свои / ежедневные обои и ночной кино-режим | Да | Да |
| 4 стиля Ambient-часов + защита от выгорания по 4 углам | Да | Да |
| Погода, индикатор BT-аудио, автозапуск после ребута, WoL | Да | Да |
| JSON-бэкап и восстановление настроек | Да | Да |
| Пилюля «Сейчас играет» | Да (через `adb` разрешение NotificationListener) | Да |
| Таймер сна (затухание громкости + пауза + чёрный ночной Ambient) | Да | Да + опциональный аппаратный сон HDMI (`KEYCODE_SLEEP`) |
| LAN Web-пульт: телеметрия, запуск приложений, ночной режим, таймер сна, загрузка обоев, настройки, WoL | Да | Да |
| LAN Web-пульт: системные клавиши D-pad, вставка текста, Force-Stop, переключение частоты AFR, безопасное извлечение USB | Аккуратное уведомление (отключено) | Да (неблокирующие команды по строгому белому списку FIFO — см. [extras/root-bridge](extras/root-bridge/README.md)) |

---

## Установка и обновления

### Вариант 1: Скрипт быстрой установки (рекомендуется)

Скрипт подключится по ADB, установит `AmbientHome.apk`, выполнит AOT-компиляцию `speed`, при желании включит пилюлю «Сейчас играет» (`NotificationListener`) и назначит Ambient Home главным экраном:

**Windows (PowerShell):**

```powershell
.\scripts\install-tv.ps1 -Device 192.168.1.x:5555 -NowPlaying -SetHome
```

**Linux / macOS (POSIX sh):**

```bash
chmod +x ./scripts/install-tv.sh
./scripts/install-tv.sh --device 192.168.1.x:5555 --now-playing --set-home
```

### Вариант 2: Ручная установка через ADB

Скачайте `AmbientHome-3.0.1.apk` из раздела [GitHub Releases](https://github.com/pavelcanton/ambient-home-tv/releases) (см. [примечания к релизу v3.0.1](docs/RELEASE_NOTES_v3.0.1.md)) или [соберите из исходников](#сборка-из-исходников), проверьте контрольную сумму SHA-256 и установите:

```bash
adb connect 192.168.1.x:5555
adb install -r AmbientHome-3.0.1.apk
adb shell cmd package compile -m speed -f com.will.ambienthome
```

Нажмите **HOME** на пульте ТВ и выберите **Ambient Home -> Всегда**, либо назначьте его лаунчером по умолчанию через ADB:

```bash
adb shell cmd package set-home-activity com.will.ambienthome/.MainActivity
```

### Вариант 3: Автообновления через Open Shelf / Obtainium

Официальные релизы подписаны постоянным RSA-4096 релизным сертификатом (`CN=Ambient Home, O=pavelcanton`, SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`). Добавьте репозиторий `https://github.com/pavelcanton/ambient-home-tv` в **Open Shelf** на ТВ-приставке (или в **Obtainium**), чтобы получать и устанавливать новые версии напрямую из GitHub Releases поверх текущей установки без потери настроек.

### Опционально: включение пилюли «Сейчас играет»

Чтобы отображать активный трек/фильм без root-прав, один раз выдайте разрешение `NotificationListener` через ADB (сохраняется после перезагрузок):

```bash
adb shell cmd notification allow_listener com.will.ambienthome/com.will.ambienthome.data.AmbientNotificationListener
```

---

## LAN Web-пульт (PWA) и REST API

Когда Web-пульт включён (по умолчанию **Вкл**, в любой момент отключается в Настройках с `0%` фоновой нагрузки), `LanWebRemoteService` слушает порт **`http://192.168.1.x:8765`** в вашей локальной сети.

- Откройте `http://192.168.1.x:8765` в браузере телефона или компьютера в той же Wi-Fi сети и введите 6-значный PIN из **Быстрых действий -> Web-пульт** или **Настроек -> LAN Web-пульт**.
- **Безопасность:** все эндпоинты `/api/*` (кроме `/api/ping`) требуют заголовок `X-Auth-Token: <6-значный PIN>` (сравнение за постоянное время, блокировка `429` на 15 с после 5 неверных попыток). Порт `8765` использует локальный HTTP для доверенной домашней сети — **никогда не пробрасывайте его в Интернет**. Полная модель угроз описана в [SECURITY.md](SECURITY.md).

### Справочник REST API (MacroDroid / Home Assistant / `curl`)

| Метод | Эндпоинт | Авторизация | Описание |
| --- | --- | :---: | --- |
| `GET` | `/` | Нет | Встроенный однофайловый Web-пульт PWA (никогда не содержит PIN) |
| `GET` | `/api/ping` | Нет | Проверка доступности (`{"ok":true,"app":"AmbientHome","version":"3.0.1"}`) |
| `GET` | `/api/status` | PIN | Телеметрия, ночной режим, таймер сна, погода, приложения, USB-тома, `bridgeAvailable` |
| `POST` | `/api/launch` | PIN | Запуск приложения: `{"package":"org.example.app"}` |
| `POST` | `/api/action` | PIN | Действие: `night_toggle`, `night_on`, `night_off`, `night_auto`, `sleep_timer` (`{"action":"sleep_timer","minutes":30}`), `next_wallpaper`, `ambient_now`, `media_play_pause`, `media_next`, `wol`, `wifi_reconnect` |
| `POST` | `/api/config` | PIN | Обновить `weatherCity`, `wolName`/`wolMac`, `nightStart`/`nightEnd`, `clockStyle` |
| `POST` | `/api/wallpaper` | PIN | Загрузка обоев JPEG/PNG (`<= 10 МиБ`, сырые байты в теле, заголовок `X-Filename: photo.jpg`) |
| `POST` | `/api/key` | PIN | *(Опциональный root-мост)* Отправка Android keycode `1..300`: `{"code":23}` |
| `POST` | `/api/text` | PIN | *(Опциональный root-мост)* Запись в буфер обмена + вставка UTF-8 текста: `{"text":"привет"}` |
| `POST` | `/api/forcestop` | PIN | *(Опциональный root-мост)* Принудительная остановка пользовательского приложения: `{"package":"org.example.app"}` |
| `POST` | `/api/action` (мост) | PIN | *(Опциональный root-мост)* `hdmi_sleep`, `hdmi_wake`, `afr` (`{"action":"afr","fps":24}`), `usb_eject` (`{"action":"usb_eject","volume":"public:8,1"}`) |

#### Пример (`curl` / HTTP-запрос MacroDroid)

```bash
# Переключить ночной кино-режим с ярлыка на телефоне или из умного дома
curl -X POST "http://192.168.1.x:8765/api/action" \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token: <6-значный PIN>" \
  -d '{"action":"night_toggle"}'

# Запустить таймер сна на 30 минут
curl -X POST "http://192.168.1.x:8765/api/action" \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token: <6-значный PIN>" \
  -d '{"action":"sleep_timer","minutes":30}'
```

---

## Сборка из исходников

Ни Gradle, ни Android Studio не требуются — проект собирается из командной строки за секунды.

### Windows (PowerShell 5.1+)

```powershell
# 1. Однократная загрузка портативного тулчейна (Temurin JDK 17, Kotlin 2.1.10, build-tools r34, platform-34)
#    Проверяет закреплённые SHA-256 и распаковывает локально в .\tools\android-build\
.\fetch-toolchain.ps1

# 2. Запуск всех 5 наборов JVM unit-тестов + R8-сборка + zipalign + проверка apksigner
.\build.ps1
# Опционально: пропустить JVM-тесты при быстрой итерации UI
.\build.ps1 -SkipTests
```

### Linux / macOS (Bash)

Подготовьте JDK 17 (`JAVA_HOME`), Kotlin `2.1.10`, Android `build-tools r34` и `android-34/android.jar` (в каталоге `./tools/android-build/`, через `AMBIENT_TOOLCHAIN_ROOT`, либо по отдельности через `AMBIENT_KOTLIN_HOME`, `AMBIENT_BUILD_TOOLS`, `AMBIENT_ANDROID_JAR` / `$ANDROID_HOME`):

```bash
chmod +x ./build.sh
./build.sh
# Опционально: пропустить JVM unit-тесты
./build.sh --skip-tests
```

Оба скрипта (`build.ps1` и `build.sh`) формируют:
- `AmbientHome.apk` (проверен `apksigner` v2+v3, `zipalign -c -p 4` и `VerifyDexZip` `STORED mod4=0`)
- `build/AmbientHome-3.0.1.apk.sha256`

### Релизная подпись (опциональные переменные окружения)

Без переменных подписи локальная сборка автоматически использует временный debug keystore в `build/debug.keystore`. Для публичных и личных релизных сборок передайте собственный приватный keystore через переменные окружения (никогда не коммитьте keystore в git):

| Переменная | Обязательна | Описание |
| --- | :---: | --- |
| `AMBIENT_KEYSTORE` | Да | Путь к файлу `.keystore` / `.jks` вне репозитория |
| `AMBIENT_KEYSTORE_PASS` | Да | Пароль хранилища ключей |
| `AMBIENT_KEY_ALIAS` | Да | Псевдоним (alias) ключа подписи |
| `AMBIENT_KEY_PASS` | Нет | Пароль ключа (если не задан, используется `AMBIENT_KEYSTORE_PASS`) |

---

## Приватность и атрибуция обоев

Ambient Home собирает **ноль аналитики, ноль отчётов о сбоях, ноль рекламы и ноль идентификаторов устройства**.

- Исходящие HTTPS-запросы выполняются **исключительно** при явном включении пользователем опциональных функций:
  1. **Виджет погоды (выключен по умолчанию):** обращается к [Open-Meteo](https://open-meteo.com/) для выбранного города не чаще одного раза в 45 минут, пока открыт `HOME`.
  2. **Обои «Фото дня» (выключены по умолчанию):** загружает до 3 ежедневных снимков с Bing `HPImageArchive` (фолбэк: Wikimedia Commons POTD) не чаще одного раза в 12 часов, пока открыт `HOME`.
- **Встроенные обои (`res/drawable-nodpi/w1..w5.jpg`):** все EXIF-метаданные полностью очищены из ресурсов APK.
  - `w4.jpg` (Grand Teton Sunrise): C.J. Adams / U.S. National Park Service (Public Domain).
  - `w5.jpg` (Denali from Wonder Lake): Mary Lewandowski / U.S. National Park Service (Public Domain).
  - `w1.jpg`, `w2.jpg`, `w3.jpg`: фотографии природы из Wikimedia Commons (CC0 / Public Domain).

---

## Документация и примечания к релизам

- [English README](README.en.md)
- [Примечания к релизу v3.0.1](docs/RELEASE_NOTES_v3.0.1.md)
- [Примечания к релизу v3.0.0](docs/RELEASE_NOTES_v3.0.0.md)
- [Полевые заметки HK1 RBOX K8 (RK3528)](docs/HK1_RBOX_K8_NOTES.md)
- [Опциональный Root-мост для Web-пульта](extras/root-bridge/README.md)
- [История изменений (Changelog)](CHANGELOG.md)
- [Политика безопасности (Security Policy)](SECURITY.md)
- [Руководство для контрибьюторов](CONTRIBUTING.md)

---

## Лицензия

Проект распространяется под лицензией [MIT License](LICENSE).
