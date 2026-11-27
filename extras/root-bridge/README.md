# Optional Root Remote Bridge (`files/remote.fifo`)

> **Ambient Home does NOT require root.** Out of the box on unrooted stock Android TV / Google TV devices, the launcher, Ambient screensaver, Night Cinema Mode, Sleep Timer (blackout), Weather, Now Playing, WoL, JSON backup, and LAN Web Remote (telemetry, app launcher, wallpaper upload, night mode, sleep timer, settings) all work without root.

This folder provides an **optional** privileged companion daemon (`ambient_remote_bridge.sh` + `ambient_remote_bridge.rc`) for **rooted Android TV / AOSP TV boxes** (such as RK3528 / HK1 RBOX K8) to unlock system-level actions from Ambient Home's LAN Web Remote and Sleep Timer:

- System-wide **D-pad / Media / Volume key injection** (`KEY 1..300`) across any foreground app
- **UTF-8 Clipboard Paste** (`PASTE` -> `input keyevent 279`)
- **True Hardware HDMI Sleep / Wake** (`SLEEP` -> `keyevent 223`, `WAKE` -> `keyevent 224`)
- **Force-Stop Misbehaving Apps** (`FORCESTOP <package>`) with a hard-coded protected system package denylist
- **Auto Frame Rate / Display Mode Switching** (`AFR 0|24|25|30|50|60` or `AFR <w> <h> <fps>`)
- **Safe USB Volume Unmount** (`EJECT <volume_id>`)

## Security Design

- **Zero Idle CPU:** Blocks cleanly on FD 3 (`exec 3<>"$FIFO"`) — consumes `0.0%` CPU when idle and avoids writer open races.
- **Strict Ownership & SELinux Isolation:** Creates `/data/data/com.will.ambienthome/files/remote.fifo` with mode `0600`, owned strictly by Ambient Home's `uid:gid` (never `root:root`), matching the app's SELinux context, and refuses symlinks (`-L`) or non-FIFO paths.
- **Zero `eval`, Zero Shell Expansion:** Parses each line (`<= 128` bytes) with pure POSIX `case` allowlists and passes literal arguments directly to `input`, `am`, `cmd display`, and `sm`.
- **Protected Package Denylist:** Refuses `FORCESTOP` on core system packages (`android`, `system`, `com.android.systemui`, `com.will.ambienthome`, `com.android.bluetooth`, `com.android.networkstack`, `com.android.tv.settings`, etc.).
- **Zero `/data/local/tmp` Artifacts:** Writes bounded rotating logs (`<= 200` lines, `0600`) under `/data/adb/ambient-remote-bridge.log`.

## Installation (Rooted Android TV Devices Only)

Requirements: rooted Android TV box (`adb root` or `su` / Magisk) with Ambient Home already installed.

### Option A — Native Android `init` Service (Recommended for System-Rooted Boxes)

```bash
# 1. Push daemon script into /data/adb/ (0700 root:root)
adb -s <TV_IP>:5555 push extras/root-bridge/ambient_remote_bridge.sh /data/local/tmp/ambient_remote_bridge.sh
adb -s <TV_IP>:5555 shell "su -c 'mkdir -p /data/adb && chmod 700 /data/adb && cp /data/local/tmp/ambient_remote_bridge.sh /data/adb/ambient_remote_bridge.sh && chmod 700 /data/adb/ambient_remote_bridge.sh && rm -f /data/local/tmp/ambient_remote_bridge.sh'"

# 2. Install init service definition (remount /system rw only on boxes with writable /system;
#    on Magisk/KernelSU devices, use Option B instead)
adb -s <TV_IP>:5555 push extras/root-bridge/ambient_remote_bridge.rc /data/local/tmp/ambient_remote_bridge.rc
adb -s <TV_IP>:5555 shell "su -c 'mount -o rw,remount / && cp /data/local/tmp/ambient_remote_bridge.rc /system/etc/init/ambient_remote_bridge.rc && chmod 644 /system/etc/init/ambient_remote_bridge.rc && rm -f /data/local/tmp/ambient_remote_bridge.rc && mount -o ro,remount /'"

# 3. Reboot and verify
adb -s <TV_IP>:5555 reboot
```

### Option B — Magisk / KernelSU `service.d` Boot Script

If your system partition is read-only (SAR / Magisk / KernelSU), skip `.rc` and launch the daemon from `/data/adb/service.d/`:

```sh
#!/system/bin/sh
exec /system/bin/sh /data/adb/ambient_remote_bridge.sh >/dev/null 2>&1 &
```

Save as `/data/adb/service.d/99-ambient-remote-bridge.sh` with `chmod 700`.

## Verification

After rebooting, verify that the FIFO exists with `0600` ownership matching Ambient Home and that the bridge reports ready:

```bash
adb -s <TV_IP>:5555 shell "su -c 'ls -lZ /data/data/com.will.ambienthome/files/remote.fifo && tail -n 5 /data/adb/ambient-remote-bridge.log'"
```

Expected log line:

```text
READY fifo=/data/data/com.will.ambienthome/files/remote.fifo
```

In Ambient Home's LAN Web Remote (`http://<TV_IP>:8765`), `GET /api/status` will now report `"bridgeAvailable": true` and automatically unlock D-pad, Paste, HDMI Sleep/Wake, Force-Stop, AFR, and Safe USB Eject controls.
