#!/usr/bin/env sh
# build.sh — standalone POSIX/Linux/macOS/CI builder for Ambient Home (0 Gradle).
# Runs all 5 JVM unit test suites, aapt2, javac, Kotlin 2.1.10, R8 shrinking,
# true ZIP STORED classes.dex packing, zipalign (-p 4), apksigner (v2+v3),
# VerifyDexZip (STORED mod4=0), and emits build/AmbientHome-<version>.apk.sha256.
set -eu

SKIP_TESTS=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests) SKIP_TESTS=1 ;;
        -h|--help)
            cat <<'USAGE'
Usage: ./build.sh [--skip-tests]

Toolchain lookup (first match wins):
  AMBIENT_TOOLCHAIN_ROOT -> ./tools/android-build -> ../tools/android-build
Individual overrides:
  JAVA_HOME, AMBIENT_KOTLIN_HOME, AMBIENT_BUILD_TOOLS, AMBIENT_ANDROID_JAR
  (also falls back to ANDROID_HOME / ANDROID_SDK_ROOT on Linux/macOS/CI)

Release signing (optional; falls back to build/debug.keystore if unset):
  AMBIENT_KEYSTORE, AMBIENT_KEYSTORE_PASS, AMBIENT_KEY_ALIAS, [AMBIENT_KEY_PASS]
USAGE
            exit 0
            ;;
        *)
            echo "Unknown option: $arg (use --help)" >&2
            exit 2
            ;;
    esac
done

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OUT="$ROOT/build"

TC=""
for cand in "${AMBIENT_TOOLCHAIN_ROOT:-}" "$ROOT/tools/android-build" "$ROOT/../tools/android-build"; do
    if [ -n "$cand" ] && [ -d "$cand" ]; then
        TC="$(CDPATH= cd -- "$cand" && pwd)"
        break
    fi
done

JDK_HOME=""
if [ -n "$TC" ] && [ -x "$TC/jdk/bin/javac" ]; then
    JDK_HOME="$TC/jdk"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JDK_HOME="$JAVA_HOME"
elif command -v javac >/dev/null 2>&1; then
    JAVAC_PATH="$(command -v javac)"
    if command -v readlink >/dev/null 2>&1; then
        JAVAC_REAL="$(readlink -f "$JAVAC_PATH" 2>/dev/null || printf '%s' "$JAVAC_PATH")"
    else
        JAVAC_REAL="$JAVAC_PATH"
    fi
    JDK_HOME="$(dirname -- "$(dirname -- "$JAVAC_REAL")")"
fi

if [ -z "$JDK_HOME" ] || [ ! -x "$JDK_HOME/bin/java" ] || [ ! -x "$JDK_HOME/bin/javac" ]; then
    echo "JDK 17 not found. Install JDK 17 (set JAVA_HOME) or populate tools/android-build/jdk." >&2
    exit 1
fi
JAVA_BIN="$JDK_HOME/bin/java"
JAVAC_BIN="$JDK_HOME/bin/javac"
JAR_BIN="$JDK_HOME/bin/jar"
KEYTOOL_BIN="$JDK_HOME/bin/keytool"

KT="${AMBIENT_KOTLIN_HOME:-}"
if [ -z "$KT" ] && [ -n "$TC" ] && [ -d "$TC/kotlinc" ]; then
    KT="$TC/kotlinc"
elif [ -z "$KT" ] && command -v kotlinc >/dev/null 2>&1; then
    KT_PATH="$(command -v kotlinc)"
    if command -v readlink >/dev/null 2>&1; then
        KT_REAL="$(readlink -f "$KT_PATH" 2>/dev/null || printf '%s' "$KT_PATH")"
    else
        KT_REAL="$KT_PATH"
    fi
    KT="$(dirname -- "$(dirname -- "$KT_REAL")")"
fi
if [ -z "$KT" ] || [ ! -f "$KT/lib/kotlin-compiler.jar" ] || [ ! -f "$KT/lib/kotlin-stdlib.jar" ]; then
    echo "Kotlin compiler (kotlinc/lib/kotlin-compiler.jar) not found. Set AMBIENT_KOTLIN_HOME or AMBIENT_TOOLCHAIN_ROOT." >&2
    exit 1
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="${AMBIENT_BUILD_TOOLS:-}"
if [ -z "$BT" ] && [ -n "$TC" ] && [ -d "$TC/build-tools" ]; then
    BT="$TC/build-tools"
elif [ -z "$BT" ] && [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/build-tools" ]; then
    BT="$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
fi
if [ -z "$BT" ] || [ ! -x "$BT/aapt2" ] || [ ! -x "$BT/zipalign" ] || [ ! -f "$BT/lib/d8.jar" ]; then
    echo "Android build-tools (aapt2, zipalign, lib/d8.jar) not found. Set AMBIENT_BUILD_TOOLS or AMBIENT_TOOLCHAIN_ROOT." >&2
    exit 1
fi

ANDROID_JAR="${AMBIENT_ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ] && [ -n "$TC" ] && [ -f "$TC/platform/android.jar" ]; then
    ANDROID_JAR="$TC/platform/android.jar"
elif [ -z "$ANDROID_JAR" ] && [ -n "$SDK_ROOT" ] && [ -f "$SDK_ROOT/platforms/android-34/android.jar" ]; then
    ANDROID_JAR="$SDK_ROOT/platforms/android-34/android.jar"
elif [ -z "$ANDROID_JAR" ] && [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/platforms" ]; then
    ANDROID_JAR="$(find "$SDK_ROOT/platforms" -maxdepth 2 -name android.jar | sort | tail -n 1)"
fi
if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "Android platform android.jar not found. Set AMBIENT_ANDROID_JAR or AMBIENT_TOOLCHAIN_ROOT." >&2
    exit 1
fi

if [ -n "${AMBIENT_KEYSTORE:-}" ]; then
    if [ ! -f "$AMBIENT_KEYSTORE" ]; then
        echo "AMBIENT_KEYSTORE not found: $AMBIENT_KEYSTORE" >&2
        exit 1
    fi
    if [ -z "${AMBIENT_KEYSTORE_PASS:-}" ] || [ -z "${AMBIENT_KEY_ALIAS:-}" ]; then
        echo "AMBIENT_KEYSTORE is set, but AMBIENT_KEYSTORE_PASS and/or AMBIENT_KEY_ALIAS is empty" >&2
        exit 1
    fi
fi

run_apksigner() {
    if [ -f "$BT/lib/apksigner.jar" ]; then
        "$JAVA_BIN" -jar "$BT/lib/apksigner.jar" "$@"
    else
        "$BT/apksigner" "$@"
    fi
}

VER_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -n 1)"
[ -n "$VER_NAME" ] || VER_NAME="3.0.0"

DEBUG_KS="$OUT/debug.keystore"
if [ -d "$OUT" ]; then
    find "$OUT" -mindepth 1 -maxdepth 1 ! -name 'debug.keystore' -exec rm -rf {} +
fi
mkdir -p "$OUT/gen" "$OUT/r-classes" "$OUT/kt-classes" "$OUT/test-classes" "$OUT/dex"

cd "$ROOT"

if [ "$SKIP_TESTS" -eq 1 ]; then
    echo "== 0. Kotlin unit tests (SKIPPED via --skip-tests) =="
else
    if [ ! -d "$ROOT/test" ]; then
        echo "Unit test directory missing: $ROOT/test" >&2
        exit 1
    fi
    echo "== 0. Kotlin unit tests (5 suites, Ambient Home $VER_NAME) =="
    TEST_LIST="$OUT/test-sources.lst"
    {
        find src/com/will/ambienthome/model -type f -name '*.kt' | sort
        printf '%s\n' \
            src/com/will/ambienthome/data/LauncherStore.kt \
            src/com/will/ambienthome/data/SettingsBackupCodec.kt \
            src/com/will/ambienthome/data/WeatherClient.kt \
            src/com/will/ambienthome/data/OnlineWallpaperProvider.kt \
            src/com/will/ambienthome/data/LanWebRemoteRouter.kt \
            src/com/will/ambienthome/data/RootBridgeClient.kt \
            src/com/will/ambienthome/data/SystemStatusHelper.kt
        find test -type f -name '*.kt' | sort
    } > "$TEST_LIST"
    "$JAVA_BIN" -Xmx1024m -cp "$KT/lib/kotlin-compiler.jar" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -jvm-target 11 -classpath "$ANDROID_JAR:$KT/lib/kotlin-stdlib.jar" \
        -d "$OUT/test-classes" "@$TEST_LIST"
    for suite in \
        com.will.ambienthome.model.HomeLogicTest \
        com.will.ambienthome.model.HomeNavigationTest \
        com.will.ambienthome.model.AmbientFeaturesTest \
        com.will.ambienthome.data.LauncherStoreTest \
        com.will.ambienthome.data.DataAndRemoteTest
    do
        "$JAVA_BIN" -cp "$OUT/test-classes:$KT/lib/kotlin-stdlib.jar:$ANDROID_JAR" "$suite"
    done
fi

echo "== 1. aapt2 compile =="
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "== 2. aapt2 link =="
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    -R "$OUT/res.zip" --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 33 --auto-add-overlay

echo "== 3. javac (R.java) =="
R_LIST="$OUT/r-sources.lst"
find "$OUT/gen" -type f -name '*.java' | sort > "$R_LIST"
"$JAVAC_BIN" -encoding UTF-8 -source 11 -target 11 -nowarn \
    -classpath "$ANDROID_JAR" -d "$OUT/r-classes" "@$R_LIST"

echo "== 4. kotlinc (Kotlin 2.1.10 -> JVM 11) =="
KT_LIST="$OUT/kt-sources.lst"
find src -type f -name '*.kt' | sort > "$KT_LIST"
"$JAVA_BIN" -Xmx1024m -cp "$KT/lib/kotlin-compiler.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 11 -classpath "$ANDROID_JAR:$KT/lib/kotlin-stdlib.jar:$OUT/r-classes" \
    -d "$OUT/kt-classes" "@$KT_LIST"

echo "== 5. jar + R8 shrinking (kotlin-stdlib -> lean DEX) =="
"$JAR_BIN" --create --file "$OUT/app-classes.jar" -C "$OUT/r-classes" . -C "$OUT/kt-classes" .
"$JAVA_BIN" -Xmx1024m -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
    --release --min-api 26 --lib "$ANDROID_JAR" --pg-conf "$ROOT/proguard-rules.pro" \
    --output "$OUT/dex" "$OUT/app-classes.jar" "$KT/lib/kotlin-stdlib.jar"
DEX_BYTES="$(wc -c < "$OUT/dex/classes.dex" | tr -d ' ')"
echo "  classes.dex (R8-shrunk Kotlin): $DEX_BYTES bytes"

echo "== 6. pack classes.dex as true ZIP STORED (method=0, raw==comp) =="
PACK_JAVA="$OUT/PackDex.java"
cat <<'EOF' > "$PACK_JAVA"
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class PackDex {
    public static void main(String[] args) throws Exception {
        File inApk = new File(args[0]);
        File dexFile = new File(args[1]);
        File outApk = new File(args[2]);
        byte[] dexBytes = Files.readAllBytes(dexFile.toPath());
        CRC32 dexCrc = new CRC32();
        dexCrc.update(dexBytes);

        try (ZipFile zf = new ZipFile(inApk);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outApk)))) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if ("classes.dex".equals(e.getName())) continue;
                byte[] data;
                try (InputStream is = zf.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                if (e.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    ne.setCrc(crc.getValue());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }

            ZipEntry dexEntry = new ZipEntry("classes.dex");
            dexEntry.setMethod(ZipEntry.STORED);
            dexEntry.setSize(dexBytes.length);
            dexEntry.setCompressedSize(dexBytes.length);
            dexEntry.setCrc(dexCrc.getValue());
            zos.putNextEntry(dexEntry);
            zos.write(dexBytes);
            zos.closeEntry();
        }
    }
}
EOF
"$JAVA_BIN" "$PACK_JAVA" "$OUT/app-unsigned.apk" "$OUT/dex/classes.dex" "$OUT/app-packed.apk"

echo "== 7. zipalign (-f -p 4) =="
"$BT/zipalign" -f -p 4 "$OUT/app-packed.apk" "$OUT/aligned.apk"

echo "== 8. sign (apksigner v2+v3) =="
FINAL="$ROOT/AmbientHome.apk"
rm -f "$FINAL" "$FINAL.idsig"

if [ -n "${AMBIENT_KEYSTORE:-}" ]; then
    if [ ! -f "$AMBIENT_KEYSTORE" ]; then
        echo "AMBIENT_KEYSTORE not found: $AMBIENT_KEYSTORE" >&2
        exit 1
    fi
    if [ -z "${AMBIENT_KEYSTORE_PASS:-}" ] || [ -z "${AMBIENT_KEY_ALIAS:-}" ]; then
        echo "AMBIENT_KEYSTORE is set, but AMBIENT_KEYSTORE_PASS and/or AMBIENT_KEY_ALIAS is empty" >&2
        exit 1
    fi
    if [ -n "${AMBIENT_KEY_PASS:-}" ]; then
        KEY_PASS_ARG="env:AMBIENT_KEY_PASS"
    else
        KEY_PASS_ARG="env:AMBIENT_KEYSTORE_PASS"
    fi
    run_apksigner sign --v4-signing-enabled false --ks "$AMBIENT_KEYSTORE" \
        --ks-pass env:AMBIENT_KEYSTORE_PASS --key-pass "$KEY_PASS_ARG" \
        --ks-key-alias "$AMBIENT_KEY_ALIAS" --out "$FINAL" "$OUT/aligned.apk"
    echo "  Signed with RELEASE keystore (alias: $AMBIENT_KEY_ALIAS)"
else
    if [ ! -f "$DEBUG_KS" ]; then
        "$KEYTOOL_BIN" -genkeypair -keystore "$DEBUG_KS" -alias androiddebugkey \
            -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    fi
    run_apksigner sign --v4-signing-enabled false --ks "$DEBUG_KS" \
        --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --out "$FINAL" "$OUT/aligned.apk"
    echo "  Signed with DEBUG keystore (set AMBIENT_KEYSTORE_* for release signing)"
fi

echo "== 9. verify (apksigner + zipalign + ZIP STORED header + SHA-256) =="
run_apksigner verify --verbose --print-certs "$FINAL" > "$OUT/apksigner.log" 2>&1
grep -E 'Verifies|Verified using|Signer #1 certificate DN|Signer #1 certificate SHA-256' "$OUT/apksigner.log" || true

"$BT/zipalign" -c -v -p 4 "$FINAL" > "$OUT/zipalign.log" 2>&1
DEX_ZA_LINE="$(grep 'classes\.dex' "$OUT/zipalign.log" || true)"
echo "  zipalign: $DEX_ZA_LINE"
case "$DEX_ZA_LINE" in
    *"(OK)"*) ;;
    *) echo "classes.dex not aligned in zipalign: $DEX_ZA_LINE" >&2; exit 1 ;;
esac
case "$DEX_ZA_LINE" in
    *compressed*) echo "classes.dex is compressed in zipalign: $DEX_ZA_LINE" >&2; exit 1 ;;
esac

VERIFY_JAVA="$OUT/VerifyDexZip.java"
cat <<'EOF' > "$VERIFY_JAVA"
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

public class VerifyDexZip {
    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL)
            | ((b[off + 1] & 0xFFL) << 8)
            | ((b[off + 2] & 0xFFL) << 16)
            | ((b[off + 3] & 0xFFL) << 24);
    }

    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        try (ZipFile zf = new ZipFile(f)) {
            ZipEntry e = zf.getEntry("classes.dex");
            if (e == null) throw new RuntimeException("classes.dex missing");
            int method = e.getMethod();
            long raw = e.getSize();
            long comp = e.getCompressedSize();
            if (method != ZipEntry.STORED || raw != comp) {
                throw new RuntimeException("classes.dex not STORED: method=" + method + " raw=" + raw + " comp=" + comp);
            }
            byte[] all = Files.readAllBytes(f.toPath());
            long dataOff = -1;
            int pos = 0;
            while (pos + 30 <= all.length) {
                long sig = u32(all, pos);
                if (sig != 0x04034b50L) break;
                int flags = u16(all, pos + 6);
                int lfhMethod = u16(all, pos + 8);
                long lfhComp = u32(all, pos + 18);
                int nLen = u16(all, pos + 26);
                int eLen = u16(all, pos + 28);
                if (pos + 30L + nLen + eLen > all.length) {
                    throw new RuntimeException("Truncated ZIP local header at offset " + pos);
                }
                String entryName = new String(all, pos + 30, nLen, StandardCharsets.UTF_8);
                long entryDataOff = pos + 30L + nLen + eLen;
                long actualComp = lfhComp;
                if ((flags & 0x08) != 0) {
                    ZipEntry cdEntry = zf.getEntry(entryName);
                    if (cdEntry == null || cdEntry.getCompressedSize() < 0) {
                        throw new RuntimeException("Missing central directory size for bit-3 entry: " + entryName);
                    }
                    actualComp = cdEntry.getCompressedSize();
                }
                if ("classes.dex".equals(entryName)) {
                    long lfhUncomp = u32(all, pos + 22);
                    if (lfhMethod != ZipEntry.STORED || (flags & 0x08) != 0 || lfhComp != raw || lfhUncomp != raw) {
                        throw new RuntimeException("classes.dex local header mismatch: method=" + lfhMethod + " flags=" + flags + " comp=" + lfhComp + " uncomp=" + lfhUncomp + " expected=" + raw);
                    }
                    dataOff = entryDataOff;
                    break;
                }
                long nextPos = entryDataOff + actualComp;
                if ((flags & 0x08) != 0) {
                    if (nextPos + 12 > all.length) {
                        throw new RuntimeException("Truncated data descriptor after " + entryName);
                    }
                    nextPos += (nextPos + 4 <= all.length && u32(all, (int) nextPos) == 0x08074b50L) ? 16L : 12L;
                }
                if (nextPos <= pos || nextPos > all.length) {
                    throw new RuntimeException("Invalid ZIP progression at " + pos + " -> " + nextPos);
                }
                pos = (int) nextPos;
            }
            System.out.println("  ZIP header: classes.dex method=" + method + " (STORED) raw=" + raw + " comp=" + comp + " dataOffset=" + dataOff + " mod4=" + (dataOff % 4));
            if (dataOff < 0 || (dataOff % 4) != 0) {
                throw new RuntimeException("classes.dex not 4-byte aligned: dataOff=" + dataOff);
            }
        }
    }
}
EOF
"$JAVA_BIN" "$VERIFY_JAVA" "$FINAL"

if command -v sha256sum >/dev/null 2>&1; then
    APK_SHA256="$(sha256sum "$FINAL" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
    APK_SHA256="$(shasum -a 256 "$FINAL" | awk '{print $1}')"
else
    SHA_JAVA="$OUT/Sha256File.java"
    cat <<'EOF' > "$SHA_JAVA"
import java.nio.file.*;
import java.security.*;

public class Sha256File {
    public static void main(String[] args) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(Files.readAllBytes(Paths.get(args[0])));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        System.out.println(sb.toString());
    }
}
EOF
    APK_SHA256="$("$JAVA_BIN" "$SHA_JAVA" "$FINAL")"
fi
SHA_FILE="$OUT/AmbientHome-$VER_NAME.apk.sha256"
printf '%s  AmbientHome-%s.apk\n' "$APK_SHA256" "$VER_NAME" > "$SHA_FILE"
echo "  SHA-256: $APK_SHA256 -> $SHA_FILE"

APK_BYTES="$(wc -c < "$FINAL" | tr -d ' ')"
echo "OK: $FINAL ($APK_BYTES bytes, DEX $DEX_BYTES bytes)"
