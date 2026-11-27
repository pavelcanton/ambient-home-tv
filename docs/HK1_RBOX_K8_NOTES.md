# Battle-Tested Field Notes: HK1 RBOX K8 (Rockchip RK3528, Android 13)

Ambient Home 3.0.0 was developed and soak-tested 24/7 on an **HK1 RBOX K8** Android TV box:

| Hardware / OS | Measured Baseline with Ambient Home 3.0.0 |
| --- | --- |
| **SoC / CPU** | Rockchip RK3528 (`4x ARM Cortex-A53`, `interactive` governor) |
| **GPU** | ARM Mali-450 MP (`simple_ondemand`, `0%` idle GPU load on Home & Ambient) |
| **Memory** | 4 GB RAM (`~2.9 GB` `MemAvailable`, Ambient Home `~25.1 MB` Total PSS, `1` Activity, `7` Views, `0` WebViews) |
| **OS** | Android 13 (API 33) |
| **Stability** | `0` Java/Native launcher crashes, `0` Accessibility IPC lag, cold-reboot verified |

Ambient Home works out of the box on **any unrooted Android TV / Google TV device (API 26+)**. The field notes below share optional OS-level optimizations that produced the largest real-world stability, flash-longevity, and thermal wins on budget RK3528 / Android 13 boxes.

> **Disclaimer:** Review every command before running it on your device. Replace `<TV_IP>` and `<LAN_SUBNET>` (for example `192.168.1.0/24`) with your own local network values.

---

## 1. Cut Background eMMC Flash Wear by ~91% (GMS `chromesync` Storm)

On stock Android 13 TV builds with Play Services (`com.google.android.gms`), broken Chrome Sync adapters can loop continuously in the background, generating hundreds of megabytes of daily `system_server` / GMS flash writes and logcat spam even with Chrome unused.

Disabling only the three `chromesync` components cuts `system_server` boot/idle eMMC write volume dramatically (~91% reduction measured over 16-hour soak) while keeping Google sign-in and Play Services intact:

```bash
# Requires root shell on Android 13 boxes where pm disable needs privileged uid
pm disable 'com.google.android.gms/.chromesync.sync.SyncReceiverService'
pm disable 'com.google.android.gms/.chromesync.sync.SyncReceiverService$Receiver'
pm disable 'com.google.android.gms/.chromesync.sync.SyncService'
```

## 2. Stop Perpetual GMS Bluetooth LE Scanning (Keep BT Audio Working)

Some Android TV GMS builds keep an active BLE GATT scanner running 24/7 even when `ble_scan_always_enabled=0`. Blocking UID-level `BLUETOOTH_SCAN` for GMS reduces idle BT CPU and radio contention to `0` active GATT scanners while keeping Bluetooth audio pairing and headphones fully functional:

```bash
settings put global ble_scan_always_enabled 0
settings put global wifi_scan_always_enabled 0
appops set --uid com.google.android.gms BLUETOOTH_SCAN ignore
```

## 3. Protect AOT `.odex` Compilations from Android 13 App Hibernation

Android 13's `app_hibernation` service strips compiled AOT `.odex` artifacts (reverting apps to slower `run-from-apk`) and revokes unused permissions after 30 days. After compiling key TV apps with `cmd package compile -m speed -f <pkg>`, exempt them from hibernation:

```bash
for pkg in com.will.ambienthome; do
    cmd app_hibernation set-state --global "$pkg" false
    cmd app_hibernation set-state --user 0 "$pkg" false
    appops set "$pkg" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
done
```

## 4. Cap `logd` Ring Buffers (`256K` Floor)

On always-on living-room TV boxes, multi-megabyte log buffers waste RAM and churn memory pages. Android 13 enforces a clean `256K` floor:

```bash
setprop persist.logd.size 256K
setprop persist.logd.size.main 256K
setprop persist.logd.size.system 256K
setprop persist.logd.size.crash 256K
```

## 5. On-Box LAN Firewall for ADB (`:5555`) & LAN Web Remote (`:8765`)

If you keep ADB over Wi-Fi (`5555`) or Ambient Home's LAN Web Remote (`8765`) active on a rooted TV box, restrict both ports strictly to your local subnet using a dedicated `iptables` / `ip6tables` chain.

Key safety rules verified on RK3528:
- **Anti-lockout order:** Always allow `eth0` and `lo` first, insert subnet `RETURN` rules before appending `DROP`, and verify allow rules with `-C` before linking into `INPUT`.
- **No martian `127.0.0.1` on `wlan0`:** Permit loopback strictly on `-i lo` (never `-i wlan0 -s 127.0.0.1/32`).

```sh
LAN_SUBNET="<LAN_SUBNET>"   # e.g. 192.168.1.0/24

iptables -w -N tv_lan_in 2>/dev/null || true
iptables -w -C tv_lan_in -i eth0 -j RETURN 2>/dev/null || iptables -w -I tv_lan_in 1 -i eth0 -j RETURN
iptables -w -C tv_lan_in -i lo -j RETURN 2>/dev/null || iptables -w -I tv_lan_in 1 -i lo -j RETURN
iptables -w -C tv_lan_in -i wlan0 -s "$LAN_SUBNET" -p tcp --dport 5555 -j RETURN 2>/dev/null || \
    iptables -w -I tv_lan_in 1 -i wlan0 -s "$LAN_SUBNET" -p tcp --dport 5555 -j RETURN
iptables -w -C tv_lan_in -i wlan0 -s "$LAN_SUBNET" -p tcp --dport 8765 -j RETURN 2>/dev/null || \
    iptables -w -I tv_lan_in 1 -i wlan0 -s "$LAN_SUBNET" -p tcp --dport 8765 -j RETURN

# Append DROP rules only after all ALLOW rules are confirmed
if iptables -w -C tv_lan_in -i eth0 -j RETURN 2>/dev/null && \
   iptables -w -C tv_lan_in -i lo -j RETURN 2>/dev/null && \
   iptables -w -C tv_lan_in -i wlan0 -s "$LAN_SUBNET" -p tcp --dport 5555 -j RETURN 2>/dev/null && \
   iptables -w -C tv_lan_in -i wlan0 -s "$LAN_SUBNET" -p tcp --dport 8765 -j RETURN 2>/dev/null; then
    iptables -w -C tv_lan_in -i wlan0 -p tcp --dport 5555 -j DROP 2>/dev/null || \
        iptables -w -A tv_lan_in -i wlan0 -p tcp --dport 5555 -j DROP
    iptables -w -C tv_lan_in -i wlan0 -p tcp --dport 8765 -j DROP 2>/dev/null || \
        iptables -w -A tv_lan_in -i wlan0 -p tcp --dport 8765 -j DROP
    iptables -w -C INPUT -j tv_lan_in 2>/dev/null || iptables -w -I INPUT 1 -j tv_lan_in
fi

# Block IPv6 inbound access to 5555/8765 on wlan0 (keep lo and eth0 open)
ip6tables -w -N tv_lan_in 2>/dev/null || true
ip6tables -w -C tv_lan_in -i eth0 -j RETURN 2>/dev/null || ip6tables -w -I tv_lan_in 1 -i eth0 -j RETURN
ip6tables -w -C tv_lan_in -i lo -j RETURN 2>/dev/null || ip6tables -w -I tv_lan_in 1 -i lo -j RETURN
ip6tables -w -C tv_lan_in -p tcp --dport 5555 -j DROP 2>/dev/null || ip6tables -w -A tv_lan_in -p tcp --dport 5555 -j DROP
ip6tables -w -C tv_lan_in -p tcp --dport 8765 -j DROP 2>/dev/null || ip6tables -w -A tv_lan_in -p tcp --dport 8765 -j DROP
ip6tables -w -C INPUT -j tv_lan_in 2>/dev/null || ip6tables -w -I INPUT 1 -j tv_lan_in
```

## 6. Flash-Safe FOSS Media Stack Hygiene (4 GB RAM Boxes)

To protect low-end TV box eMMC storage and keep navigation stutter-free:

- **Zero Accessibility Services:** Keep `enabled_accessibility_services` empty — Accessibility IPC hooks add measurable input latency on Mali-450 / Cortex-A53 systems and are completely unnecessary for Ambient Home.
- **RAM-Only Streaming Cache (e.g. TorrServe):** On 4 GB RAM boxes, configure torrent/stream servers for RAM-only caching (for example `CacheSize: 201326592` [192 MB], `UseDisk: false`, `RemoveCacheOnDrop: true`, `EnableDLNA: false`) and disable unneeded boot receivers so media streaming writes `0 B` to eMMC flash.
- **Optional Root Remote Bridge:** For system-wide D-pad, UTF-8 paste, force-stop, AFR, and true hardware HDMI sleep (`KEYCODE_SLEEP`), see [extras/root-bridge/README.md](../extras/root-bridge/README.md).
