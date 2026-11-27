# Changelog

All notable changes to **Ambient Home** are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.0.0] - 2026-11-27

First public open-source release (`versionCode=18`).

### Added
- **Auto-Night Cinema Mode (`model/NightSchedule.kt`):** Configurable schedule (default `22:00-07:00`) plus Auto/On/Off toggle; bakes warm amber cinema tint and deeper dimming directly into static `RGB_565` wallpapers off the UI thread (`0%` extra GPU load) and lowers clock luminance.
- **Quick Control Sheet (`TvDialogs.showQuickControls`):** One-click living-room drawer via the TopBar Quick button or `MENU` key — Ambient Now, Next Wallpaper, Night Cinema toggle, Sleep Timer, LAN Web Remote info (URL + PIN), Soft Wi-Fi Reconnect, and Safe USB Eject (when external USB storage is mounted).
- **4 Ambient Clock Styles & Burn-in Protection (`ui/AmbientClockRenderer.kt`):** Thin, Cinema, Minimal, and Analog clocks with 15-minute 4-corner burn-in hop and subtle pixel drift.
- **Living-Room Sleep Timer (`data/SleepTimerController.kt`, `SleepTimerReceiver`):** 15/30/60/90-minute timer with TopBar & Ambient countdown badge, 15-second cancelable pre-sleep warning, smooth volume fade-out + media pause, and transition to ultra-dim black Night Ambient (plus optional HDMI sleep when a root bridge FIFO is present).
- **Zero-Scan Bluetooth Audio Indicator (`data/AudioDeviceObserver.kt`):** Passive `AudioManager.AudioDeviceCallback` showing a TopBar headphones badge only while BT A2DP / BLE headset audio is connected, with 0 Bluetooth scans.
- **Open-Meteo Weather Widget (`data/WeatherClient.kt`):** Optional, API-key-free weather pill and 4-day / hourly forecast dialog with RAM-only caching (refreshes at most every 45 minutes while `HOME` is active).
- **Now Playing Pill (`data/AmbientNotificationListener`, `data/MediaSessionBridge.kt`):** Optional zero-idle-cost media bar showing active playback metadata on Home and Ambient screens once `NotificationListener` access is granted.
- **Photo of the Day Wallpapers (`data/OnlineWallpaperProvider.kt`):** Optional Bing HPImageArchive + Wikimedia Commons POTD daily wallpaper fetcher (at most once every 12 hours, max 3 cached validated JPEGs, automatic offline fallback).
- **LAN Web Remote PWA & REST API (`LanWebRemoteService`, `LanWebRemoteRouter`, `res/raw/web_remote.html`):** Embedded mobile/desktop web remote on port `:8765` with 6-digit `SecureRandom` PIN (`X-Auth-Token`, constant-time verification, 5-failure 15s `429` lockout), live device telemetry, app launcher, Night Mode / Sleep Timer / Weather / WoL controls, wallpaper upload (10 MiB cap + magic byte validation), and optional root-bridge D-pad / clipboard paste / AFR / force-stop / HDMI sleep support.
- **Wake-on-LAN PC Tile (`data/WolSender.kt`, `model/CustomShortcuts.kt`):** Optional virtual app tile to wake a configured LAN PC via UDP magic packet.
- **Wi-Fi-Aware Cold-Boot Auto-Launch (`model/BootAutoLaunch.kt`):** Optional cold-boot app launcher using `/proc/sys/kernel/random/boot_id` with network-wait and cancelable countdown banner.
- **JSON Settings Backup & Restore (`data/SettingsBackupCodec.kt`):** Human-readable JSON export/import of dock order, pinned/hidden apps, appearance, night schedule, weather city, WoL target, and Web Remote settings.
- **Cross-Platform Standalone Build & CI:** Portable Windows (`fetch-toolchain.ps1` + `build.ps1`) and POSIX Linux/macOS (`build.sh`) build scripts with environment-variable release signing (`AMBIENT_KEYSTORE*`), SHA-256 checksum output, and GitHub Actions CI.

### Changed
- **Deslop Modular Architecture:** Split `MainActivity`, `HomeRenderer`, and `WallpaperSurfaceView` into clean deep modules (`WallpaperController`, `TopBarRenderer`, `AmbientClockRenderer`, `MainFeatureCoordinator`, `MainSystemObservers`, `SettingsExtraHelper`) with strict `< 600` line file limits and zero circular dependencies.
- **Cross-Device Hardware Telemetry:** Generalized `SystemStatusHelper` GPU devfreq and thermal discovery to support Rockchip, Amlogic, Qualcomm, MediaTek, and generic Android TV devices with graceful fallbacks.
- **Unrooted Stock Android TV Polish:** Web Remote PWA and Sleep Timer / NotificationListener dialogs cleanly adapt when the optional root bridge FIFO is absent.

### Security & Hygiene
- Stripped EXIF metadata from bundled drawable wallpapers (`w4.jpg`, `w5.jpg`) while preserving National Park Service Public Domain attribution.
- Moved default debug keystore path to `build/debug.keystore` and enforced release signing via environment variables only.
- Verified `491` pure-JVM unit tests, R8 shrinking, `apksigner` v2+v3 signatures, `zipalign -p 4`, and uncompressed 4-byte-aligned `STORED` `classes.dex`.
