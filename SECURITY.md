# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| 3.0.x | Yes |
| < 3.0.0 | No |

## Threat Model & Local Network Disclosure

Ambient Home is designed for **private home LANs** with zero telemetry, zero ads, and zero cloud accounts. Please understand the security boundaries of its optional features:

### 1. LAN Web Remote (`LanWebRemoteService`, port `8765`)
- **Transport:** Serves an embedded HTML5 PWA and JSON REST API over **unencrypted HTTP** on the local network (`0.0.0.0:8765`) by default when enabled. It is intended exclusively for trusted home LANs — **never expose port `8765` to the public Internet** (no router port forwarding, no DMZ).
- **Authentication:** Every `/api/*` endpoint (except the unauthenticated liveness check `GET /api/ping`) requires a 6-digit `SecureRandom`-generated PIN sent in the `X-Auth-Token` header and verified with constant-time byte comparison (`LanWebRemoteRouter.constantTimeEquals`).
- **Rate Limiting:** 5 consecutive authentication failures trigger a 15-second `429 rate_limited` lockout.
- **No PIN Leakage:** The HTML shell (`GET /`) and `GET /api/ping` never embed or reveal the PIN, and the PIN is never written to `logcat`.
- **Upload & Input Validation:** JSON bodies are capped at 64 KiB; wallpaper uploads are capped at 10 MiB, reject path traversal (`..`, `/`, `\`, NUL), allow only strict `[a-zA-Z0-9_-]{1,64}.(jpg|jpeg|png)` names, and validate raw JPEG/PNG magic bytes before writing.
- **Opt-Out:** You can disable the LAN Web Remote completely in **Settings -> LAN Web Remote** (stops `LanWebRemoteService` immediately with 0% background CPU and closes port `8765`) or rotate the 6-digit PIN at any time.

### 2. Optional Root Bridge (`files/remote.fifo`)
- Ambient Home works **unrooted** on stock Android TV devices. It never requests `su` or bundles a root daemon.
- If an advanced user configures an external privileged FIFO reader at `<app filesDir>/remote.fifo`, `RootBridgeClient` enforces:
  - Canonical parent directory check + `Os.lstat()` `S_ISFIFO` check (refuses symlinks or regular files).
  - Non-blocking `O_WRONLY | O_NONBLOCK` writes (returns `ENXIO` immediately if no reader is active).
  - Strict allowlists for keycodes (`1..300`), simple verbs (`PASTE`, `SLEEP`, `WAKE`), AFR refresh rates (`0, 24, 25, 30, 50, 60`), volume IDs, and package names.
  - Hard-coded denylist of critical system packages (`android`, `system`, `com.android.systemui`, `com.will.ambienthome`, etc.) that `FORCESTOP` always refuses.

### 3. Optional Outbound Network Connections
Ambient Home sends **zero analytics or telemetry**. Outbound HTTPS requests occur **only** when explicitly enabled by the user:
- **Weather widget** (disabled by default): queries `api.open-meteo.com` and `geocoding-api.open-meteo.com` at most once every 45 minutes while `HOME` is active.
- **Photo of the Day wallpapers** (disabled by default): queries `www.bing.com/HPImageArchive.aspx` (with fallback to `commons.wikimedia.org/w/api.php`) at most once every 12 hours while `HOME` is active, caching at most 3 validated JPEGs in `cacheDir/wp_daily/`.

### 4. APK Signing
- Local developer builds without signing environment variables fall back to an ephemeral debug keystore under `build/debug.keystore`.
- Official release builds should be signed with a private release keystore via `AMBIENT_KEYSTORE`, `AMBIENT_KEYSTORE_PASS`, `AMBIENT_KEY_ALIAS`, and `AMBIENT_KEY_PASS` (never committed to git).

## Reporting a Vulnerability

If you discover a security issue in Ambient Home:

1. **Do not** open a public GitHub issue with exploit details.
2. Use **GitHub Private Vulnerability Reporting** (`Security -> Advisories -> Report a vulnerability`) in this repository, or contact the repository maintainer privately through GitHub.
3. Include affected version, device/Android API level, reproduction steps, and potential impact.

We aim to acknowledge reports within 72 hours and provide a fix or mitigation plan as quickly as possible.
