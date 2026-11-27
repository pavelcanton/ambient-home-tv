# Contributing to Ambient Home

Thank you for your interest in contributing to **Ambient Home**! This project is engineered for ultra-low memory, zero idle GPU draw, and rock-solid stability on low-end and mid-range Android TV hardware (`minSdk=26`, `targetSdk=33`).

## Quick Start

### Prerequisites
- **Windows:** PowerShell 5.1+ (run `.\fetch-toolchain.ps1` once, then `.\build.ps1`)
- **Linux / macOS:** JDK 17 (`JAVA_HOME`), Kotlin `2.1.10`, Android `build-tools r34`, Android `platform-34` (run `./build.sh`)

No Gradle, no Android Studio, and no AndroidX dependencies are required.

## Architectural Rules (Mandatory)

All pull requests are checked against our strict **Deslop & Zero-Bloat** engineering gates:

1. **Zero External Dependencies:**
   - Pure Kotlin `2.1.10` + Android SDK (`android.jar` API 34) only.
   - Do **not** add Gradle, AndroidX, Jetpack, Compose, OkHttp, Gson, Coroutines, or third-party JARs.
2. **Module & Function Budgets:**
   - Every `.kt` file must stay **strictly `< 600` lines** (aim for `< 500`).
   - Every function must stay **`<= 50` lines**.
   - Every class/object should stay **`<= 7` non-override public methods** (deep, narrow modules).
3. **Strict Layer DAG (Zero Cycles):**
   - Allowed dependency directions: `root -> {ui, data, model}`, `ui -> model`, `data -> model`.
   - **Never** import `com.will.ambienthome.data.*` inside `ui/`.
4. **HWUI & Low-End GPU Safety:**
   - **Never** call `Bitmap.recycle()` on HWUI-drawn bitmaps (`HomeCanvasView`, `HomeRenderer`, `TopBarRenderer`, `HomeSurfaceCache`, `AppRepository` tile cache). Only off-screen temporary IO bitmaps (`WallpaperRepository`) and CPU-locked `SurfaceView` buffers (`WallpaperSurfaceView`) may recycle bitmaps.
   - Pre-bake static shadows, panels, and night tints off the UI thread; keep idle GPU usage at `0%` (no continuous animations when idle).
5. **R8 & Android 13 Compatibility:**
   - Avoid `java.util.EnumMap` (keep R8 enum shrinking clean) and ensure all manifest-referenced Activities, Services, and BroadcastReceivers are kept in `proguard-rules.pro`.
   - Use Android 13+ compliant `registerReceiver(..., RECEIVER_NOT_EXPORTED / RECEIVER_EXPORTED)` when registering dynamic broadcast receivers.
6. **File Encoding Hygiene:**
   - `.kt`, `.xml`, `.html`, `.sh`, `.md`, `.yml`, `LICENSE`, `.gitignore`, `.pro`: **UTF-8 without BOM + LF (`\n`)**.
   - `.ps1`: **UTF-8 with BOM + LF (`\n`)** and zero PowerShell AST parse errors.

## Localization Contributions (English & Other Languages)

Ambient Home 3.0.0 is currently **Russian-first (`ru`)**, with UI strings defined directly in Kotlin files and `res/raw/web_remote.html`. Community pull requests that cleanly extract UI strings into `res/values/strings.xml` (English default) + `res/values-ru/strings.xml` (Russian) without breaking our `< 600` line file budget or adding dependencies are **very welcome**!

Please open an issue or draft PR first if you plan a large i18n refactor so we can coordinate string keys cleanly.

## Testing & Verification Checklist

Before opening a pull request:
- Add or update pure-JVM unit tests under `test/` for any new model, router, storage, or codec logic.
- Run `.\build.ps1` (Windows) or `./build.sh` (Linux/macOS) and confirm:
  - All JVM unit test suites pass (`0` failures).
  - R8 shrinking, `classes.dex` `STORED mod4=0`, `zipalign`, and `apksigner` verification all pass.
  - CI checks (`ci.yml`) pass without warnings or encoding violations.
- Never commit personal IP addresses, Wi-Fi SSIDs, MAC addresses, PINs, keystores, or build artifacts.
