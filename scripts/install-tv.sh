#!/usr/bin/env sh
# install-tv.sh — quick ADB installer for Ambient Home on Android TV / Google TV.
# Connects to a parameterized TV device IP[:port], installs AmbientHome.apk, runs AOT speed compile,
# and optionally grants NotificationListener ("Now Playing") and sets Ambient Home as default HOME.
set -eu

DEVICE=""
APK_PATH=""
NOW_PLAYING=0
SET_HOME=0
SKIP_AOT=0

usage() {
    cat <<'USAGE'
Usage: ./scripts/install-tv.sh --device <ip[:5555]> [options]

Options:
  --device <ip[:port]>  Target TV address (required, e.g. 192.168.1.50:5555)
  --apk <path>          Path to AmbientHome.apk (default: ./AmbientHome.apk)
  --now-playing         Grant NotificationListener for the Now Playing pill
  --set-home            Set Ambient Home as the default HOME launcher
  --skip-aot            Skip `cmd package compile -m speed`
  -h, --help            Show this help message
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --device)
            [ "$#" -ge 2 ] || { echo "Missing value for --device" >&2; exit 2; }
            DEVICE="$2"
            shift 2
            ;;
        --device=*)
            DEVICE="${1#--device=}"
            shift
            ;;
        --apk)
            [ "$#" -ge 2 ] || { echo "Missing value for --apk" >&2; exit 2; }
            APK_PATH="$2"
            shift 2
            ;;
        --apk=*)
            APK_PATH="${1#--apk=}"
            shift
            ;;
        --now-playing)
            NOW_PLAYING=1
            shift
            ;;
        --set-home)
            SET_HOME=1
            shift
            ;;
        --skip-aot)
            SKIP_AOT=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1 (use --help)" >&2
            exit 2
            ;;
    esac
done

if [ -z "$DEVICE" ]; then
    echo "Error: --device <ip[:5555]> is required." >&2
    usage >&2
    exit 2
fi

case "$DEVICE" in
    *:*)
        HOST="${DEVICE%:*}"
        PORT="${DEVICE##*:}"
        ;;
    *)
        HOST="$DEVICE"
        PORT="5555"
        ;;
esac

case "$PORT" in
    ''|*[!0-9]*)
        echo "Invalid port in --device: $PORT" >&2
        exit 2
        ;;
esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    echo "Invalid port in --device: $PORT (must be 1..65535)" >&2
    exit 2
fi

case "$HOST" in
    ''|-*|.*|*[!a-zA-Z0-9.-]*)
        echo "Invalid host in --device: $HOST" >&2
        exit 2
        ;;
esac

# Validate IPv4 octets when host is all digits and dots
case "$HOST" in
    *[!0-9.]*) ;;
    *)
        OIFS="$IFS"
        IFS='.'
        set -- $HOST
        IFS="$OIFS"
        if [ "$#" -ne 4 ]; then
            echo "Invalid IPv4 address in --device: $HOST" >&2
            exit 2
        fi
        for oct in "$1" "$2" "$3" "$4"; do
            case "$oct" in
                ''|*[!0-9]*) echo "Invalid IPv4 octet: $oct" >&2; exit 2 ;;
            esac
            if [ "$oct" -lt 0 ] || [ "$oct" -gt 255 ]; then
                echo "Invalid IPv4 octet: $oct" >&2
                exit 2
            fi
        done
        ;;
esac

TARGET="${HOST}:${PORT}"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
LAUNCHER_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
if [ -z "$APK_PATH" ]; then
    APK_PATH="$LAUNCHER_ROOT/AmbientHome.apk"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "APK not found: $APK_PATH (build with ./build.sh or download from GitHub Releases)" >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found in PATH. Install Android Platform-Tools first." >&2
    exit 1
fi

echo "== 1. Connecting to $TARGET =="
adb connect "$TARGET"

echo "== 2. Installing $(basename -- "$APK_PATH") =="
adb -s "$TARGET" install -r "$APK_PATH"

if [ "$SKIP_AOT" -eq 0 ]; then
    echo "== 3. Running AOT speed compilation (com.will.ambienthome) =="
    adb -s "$TARGET" shell cmd package compile -m speed -f com.will.ambienthome
else
    echo "== 3. Skipping AOT compilation (--skip-aot) =="
fi

if [ "$NOW_PLAYING" -eq 1 ]; then
    echo "== 4. Granting NotificationListener (Now Playing pill) =="
    adb -s "$TARGET" shell cmd notification allow_listener \
        com.will.ambienthome/com.will.ambienthome.data.AmbientNotificationListener
fi

if [ "$SET_HOME" -eq 1 ]; then
    echo "== 5. Setting Ambient Home as default HOME launcher =="
    adb -s "$TARGET" shell cmd package set-home-activity com.will.ambienthome/.MainActivity
fi

echo ""
echo "INSTALL COMPLETE on $TARGET"
echo "  LAN Web Remote URL: http://${HOST}:8765"
echo "  (Find the 6-digit PIN in Ambient Home -> Quick Controls or Settings -> LAN Web Remote)"
