# Ambient Home 3.0.0 (`versionCode=18`)

First public open-source release of **Ambient Home** — an ultra-lightweight, ad-free, zero-telemetry Android TV launcher & living-room ambient display written in pure Kotlin (`0` Gradle, `0` AndroidX, `~25 MB` RAM, `0%` idle GPU).

> **Battle-Tested 24/7 on HK1 RBOX K8 (Rockchip RK3528):** Continuously soak-tested on **HK1 RBOX K8** (`RK3528`, `4x Cortex-A53`, `Mali-450 MP`, `4 GB RAM`, `Android 13 / API 33`) at **~25.1 MB Total PSS**, **0% idle GPU**, **0 crashes**, and **0 Accessibility lag** — while working **100% unrooted** on any Android TV / Google TV device (`minSdk=26`, `targetSdk=33`).

> **Localization Note:** Current TV and Web Remote UI strings in v3.0.0 are **Russian-first (`ru`)**. Community PRs adding English (`res/values/strings.xml`) and other translations are very welcome.

---

## Highlights

- **Apple TV-Style Dock & All Apps Grid:** Pinned favorites (`★`), manual dock reordering, app hiding, instant D-pad response, and pre-baked translucent panels.
- **Auto-Night Cinema Mode:** Configurable schedule (default `22:00-07:00`) + Auto/On/Off toggle; bakes warm amber tint and extra dimming directly into static `RGB_565` wallpapers off the UI thread (`0%` extra GPU load) and lowers clock luminance.
- **4 Burn-in-Safe Ambient Clocks:** Thin, Cinema, Minimal, and Analog styles with 15-minute 4-corner burn-in hop and subtle pixel drift.
- **Quick Control Sheet:** One-click living-room menu (TopBar Quick icon or `MENU` key) for Ambient Now, Next Wallpaper, Night Cinema toggle, Sleep Timer, LAN Web Remote info (URL + PIN), Soft Wi-Fi Reconnect, and Safe USB Eject.
- **Living-Room Sleep Timer:** 15 / 30 / 60 / 90-minute countdown badge, 15-second cancelable pre-sleep warning, smooth volume fade-out + media pause, and transition to ultra-dim black Night Ambient (plus optional hardware HDMI sleep when paired with the root bridge).
- **Smart Living-Room Widgets & Utilities:**
  - **Open-Meteo Weather** (optional, API-key-free, RAM-only cache, refreshes at most every 45 min while `HOME` is open).
  - **Now Playing Pill** (optional zero-idle-cost media bar via `NotificationListener` ADB grant).
  - **Photo of the Day Wallpapers** (optional Bing `HPImageArchive` + Wikimedia Commons POTD rotation, max 3 validated JPEGs, offline fallback) plus 5 metadata-stripped bundled nature photos, custom `/sdcard/Pictures/Wallpapers`, and gradients.
  - **Zero-Scan Bluetooth Audio Badge** (passive `AudioDeviceCallback`, `0` BT scans).
  - **Wake-on-LAN PC Tile**, **Wi-Fi-Aware Cold-Boot Auto-Launch**, and **Human-Readable JSON Settings Backup/Restore**.
- **Embedded LAN Web Remote PWA & REST API (`:8765`):** Control your TV from any phone/PC browser on your home LAN (`http://<TV_IP>:8765`), protected by a random 6-digit `SecureRandom` PIN (`X-Auth-Token`, constant-time verification, 5-failure 15s `429` lockout). Supports MacroDroid / Home Assistant / `curl` automation and can be toggled completely Off in Settings.
- **Cross-Platform Standalone Build:** Pure Kotlin `2.1.10` + R8 builds via Windows `fetch-toolchain.ps1` + `build.ps1` or Linux/macOS `./build.sh` with optional release signing (`AMBIENT_KEYSTORE*`), verified by **491/491** pure-JVM unit tests.

---

## Unrooted Stock Android TV vs Optional Root Bridge

Ambient Home requires **zero root** on stock Android TV / Google TV boxes:
- **Works unrooted:** Dock/Grid launcher, Wallpapers, Night Cinema Mode, Ambient Clocks, Sleep Timer (volume fade + media pause + black Night Ambient), Weather, BT Audio badge, Now Playing pill (via ADB `NotificationListener` grant), WoL, Cold-Boot Auto-Launch, JSON Backup/Restore, and LAN Web Remote (Live Telemetry, App Launcher, Night Mode, Sleep Timer, Weather/WoL config, Wallpaper Upload).
- **Optional Root Bridge (`extras/root-bridge/`):** On rooted Android TV devices (including RK3528 / HK1 RBOX K8), installing the optional allowlisted FIFO bridge (`files/remote.fifo`) additionally enables system-wide D-pad keys, UTF-8 Clipboard Paste, Force-Stop, AFR display-mode switching, Safe USB Eject, and hardware HDMI sleep (`KEYCODE_SLEEP`). See `extras/root-bridge/README.md` and `docs/HK1_RBOX_K8_NOTES.md`.

---

## Installation

### Quick-Install Script (Recommended)

**Windows (PowerShell):**

```powershell
.\scripts\install-tv.ps1 -Device <TV_IP>:5555 -NowPlaying -SetHome
```

**Linux / macOS:**

```bash
chmod +x ./scripts/install-tv.sh
./scripts/install-tv.sh --device <TV_IP>:5555 --now-playing --set-home
```

### Manual ADB Install

```bash
adb connect <TV_IP>:5555
adb install -r AmbientHome-3.0.0.apk
adb shell cmd package compile -m speed -f com.will.ambienthome
adb shell cmd package set-home-activity com.will.ambienthome/.MainActivity
# Optional: enable Now Playing pill (survives reboots)
adb shell cmd notification allow_listener com.will.ambienthome/com.will.ambienthome.data.AmbientNotificationListener
```

---

## Build Verification & Checksums

| Artifact | Value |
| --- | --- |
| **Version** | `3.0.0` (`versionCode=18`, `minSdk=26`, `targetSdk=33`) |
| **Release Asset** | `AmbientHome-3.0.0.apk` (+ `AmbientHome-3.0.0.apk.sha256`) |
| **Release APK Size** | `4,084,695` bytes (`3.90 MB`) |
| **Release APK SHA-256** | `96610aebe504cb424bc435d534aec784860918758c35686cc94b5cdb8e63ecc5` |
| **R8 `classes.dex`** | `346,556` bytes (`STORED`, 4-byte aligned `mod4=0`, SHA-256 `43d69ab04d81792e550349ab82d6b96dd7d8f5b4831ad346fb7dc19281bbf3c6`) |
| **JVM Unit Tests** | `491 / 491` PASS (`0` FAIL across 5 suites) |
| **Signing Certificate** | `CN=Ambient Home, O=pavelcanton` (RSA 4096-bit, `apksigner` v2+v3, v4 disabled) |
| **Signing Cert SHA-256** | `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c` |

> **Verify Before Installing:** Check `sha256sum -c AmbientHome-3.0.0.apk.sha256` (Linux/macOS) or `(Get-FileHash AmbientHome-3.0.0.apk -Algorithm SHA256).Hash` (Windows) against `96610aebe504cb424bc435d534aec784860918758c35686cc94b5cdb8e63ecc5`, and verify the release signature (`CN=Ambient Home, O=pavelcanton`, cert SHA-256 `0011e394417345dcc87a9fdafee389db81cbf799b6aadbe9b9e372f4597ec74c`).
