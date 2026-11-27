# build.ps1 — сборка Ambient Home 3.0.2 (Kotlin unit tests + Pure Kotlin 2.1.10 + R8 shrinking + aapt2 + true STORED DEX + zipalign + apksigner).
param(
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$tcCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:AMBIENT_TOOLCHAIN_ROOT)) {
    $tcCandidates += $env:AMBIENT_TOOLCHAIN_ROOT
}
$tcCandidates += (Join-Path $root 'tools\android-build')
$tcCandidates += (Join-Path $root '..\tools\android-build')

$tc = $null
foreach ($cand in $tcCandidates) {
    if (Test-Path -LiteralPath $cand) {
        $tc = (Resolve-Path -LiteralPath $cand).Path
        break
    }
}
if (-not $tc) {
    throw "Не найден toolchain android-build. Запустите .\fetch-toolchain.ps1 или задайте AMBIENT_TOOLCHAIN_ROOT."
}

$bt  = Join-Path $tc 'build-tools'
$jdk = Join-Path $tc 'jdk'
$kt  = Join-Path $tc 'kotlinc'
$jar = Join-Path $tc 'platform\android.jar'
$out = Join-Path $root 'build'

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$bt;$kt\bin;$env:PATH"

foreach ($p in @($bt, $jar, (Join-Path $jdk 'bin\javac.exe'), (Join-Path $kt 'lib\kotlin-compiler.jar'), (Join-Path $kt 'lib\kotlin-stdlib.jar'))) {
    if (-not (Test-Path -LiteralPath $p)) { throw "Не найден компонент toolchain: $p" }
}

if (-not [string]::IsNullOrWhiteSpace($env:AMBIENT_KEYSTORE)) {
    if (-not (Test-Path -LiteralPath $env:AMBIENT_KEYSTORE)) {
        throw "AMBIENT_KEYSTORE не найден: $($env:AMBIENT_KEYSTORE)"
    }
    if ([string]::IsNullOrWhiteSpace($env:AMBIENT_KEYSTORE_PASS) -or [string]::IsNullOrWhiteSpace($env:AMBIENT_KEY_ALIAS)) {
        throw 'AMBIENT_KEYSTORE задан, но не заданы AMBIENT_KEYSTORE_PASS и/или AMBIENT_KEY_ALIAS'
    }
}

[xml]$manifestXml = Get-Content -LiteralPath (Join-Path $root 'AndroidManifest.xml') -Encoding UTF8
$verName = $manifestXml.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
if ([string]::IsNullOrWhiteSpace($verName)) { $verName = '3.0.0' }

$debugKs = Join-Path $out 'debug.keystore'
$legacyKs = Join-Path $root 'debug.keystore'
if ((Test-Path -LiteralPath $legacyKs) -and (-not (Test-Path -LiteralPath $debugKs))) {
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    Move-Item -LiteralPath $legacyKs -Destination $debugKs
    Write-Host '  Migrated legacy debug.keystore -> build\debug.keystore (signing cert preserved)'
}

if (Test-Path -LiteralPath $out) {
    Get-ChildItem -LiteralPath $out -Force | Where-Object { $_.Name -ne 'debug.keystore' } | Remove-Item -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $out, "$out\gen", "$out\r-classes", "$out\kt-classes", "$out\test-classes", "$out\dex" | Out-Null

if ($SkipTests) {
    Write-Host '== 0. Kotlin unit tests (SKIPPED via -SkipTests) =='
} else {
    $testDir = Join-Path $root 'test'
    if (-not (Test-Path -LiteralPath $testDir)) { throw "Каталог unit-тестов не найден: $testDir" }
    Write-Host "== 0. Kotlin unit tests (5 suites, Ambient Home $verName) =="
    $modelSources = Get-ChildItem (Join-Path $root 'src\com\will\ambienthome\model') -Recurse -Filter *.kt | ForEach-Object { $_.FullName }
    $dataTestSources = @(
        'LauncherStore.kt',
        'SettingsBackupCodec.kt',
        'WeatherClient.kt',
        'OnlineWallpaperProvider.kt',
        'LanWebRemoteRouter.kt',
        'LanWebRemoteServer.kt',
        'RootBridgeClient.kt',
        'SleepTimerController.kt',
        'SystemStatusHelper.kt'
    ) | ForEach-Object { Join-Path $root "src\com\will\ambienthome\data\$_" }
    $testSources  = @(Get-ChildItem $testDir -Recurse -Filter *.kt | ForEach-Object { $_.FullName })
    if ($testSources.Count -eq 0) { throw "В $testDir не найдено файлов unit-тестов (*.kt)" }
    $testInputs   = @($modelSources) + @($dataTestSources) + @($testSources)
    $testCp = "$jar;$kt\lib\kotlin-stdlib.jar"
    & "$jdk\bin\java.exe" -Xmx1024m -cp "$kt\lib\kotlin-compiler.jar" `
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -no-stdlib -jvm-target 11 -classpath $testCp -d "$out\test-classes" $testInputs
    if ($LASTEXITCODE -ne 0) { throw 'kotlinc unit tests failed' }
    foreach ($suite in @(
        'com.will.ambienthome.model.HomeLogicTest',
        'com.will.ambienthome.model.HomeNavigationTest',
        'com.will.ambienthome.model.AmbientFeaturesTest',
        'com.will.ambienthome.data.LauncherStoreTest',
        'com.will.ambienthome.data.DataAndRemoteTest'
    )) {
        & "$jdk\bin\java.exe" -cp "$out\test-classes;$kt\lib\kotlin-stdlib.jar;$jar" $suite
        if ($LASTEXITCODE -ne 0) { throw "$suite failed" }
    }
}

Write-Host '== 1. aapt2 compile =='
& "$bt\aapt2.exe" compile --dir (Join-Path $root 'res') -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '== 2. aapt2 link =='
& "$bt\aapt2.exe" link -o "$out\app-unsigned.apk" -I $jar `
    --manifest (Join-Path $root 'AndroidManifest.xml') `
    -R "$out\res.zip" --java "$out\gen" `
    --min-sdk-version 26 --target-sdk-version 33 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '== 3. javac (R.java) =='
$rSources = Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
    -classpath $jar -d "$out\r-classes" $rSources
if ($LASTEXITCODE -ne 0) { throw 'javac R.java failed' }

Write-Host '== 4. kotlinc (Kotlin 2.1.10 -> JVM 11) =='
$ktSources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.kt | ForEach-Object { $_.FullName }
$ktCp = "$jar;$kt\lib\kotlin-stdlib.jar;$out\r-classes"
& "$jdk\bin\java.exe" -Xmx1024m -cp "$kt\lib\kotlin-compiler.jar" `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -jvm-target 11 -classpath $ktCp -d "$out\kt-classes" $ktSources
if ($LASTEXITCODE -ne 0) { throw 'kotlinc failed' }

Write-Host '== 5. jar + R8 shrinking (kotlin-stdlib -> lean DEX) =='
& "$jdk\bin\jar.exe" --create --file "$out\app-classes.jar" -C "$out\r-classes" . -C "$out\kt-classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

$r8Jar = Join-Path $bt 'lib\d8.jar'
$pgConf = Join-Path $root 'proguard-rules.pro'
& "$jdk\bin\java.exe" -Xmx1024m -cp $r8Jar com.android.tools.r8.R8 `
    --release --min-api 26 --lib $jar --pg-conf $pgConf `
    --output "$out\dex" "$out\app-classes.jar" "$kt\lib\kotlin-stdlib.jar"
if ($LASTEXITCODE -ne 0) { throw 'R8 failed' }

$dexSizeKb = [math]::Round((Get-Item "$out\dex\classes.dex").Length / 1KB, 1)
Write-Host "  classes.dex (R8-shrunk Kotlin): $dexSizeKb KB"

Write-Host '== 6. pack classes.dex as true ZIP STORED (method=0, raw==comp) =='
$packJava = "$out\PackDex.java"
$packCode = @'
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
'@
[System.IO.File]::WriteAllText($packJava, $packCode, (New-Object System.Text.UTF8Encoding($false)))
& "$jdk\bin\java.exe" $packJava "$out\app-unsigned.apk" "$out\dex\classes.dex" "$out\app-packed.apk"
if ($LASTEXITCODE -ne 0) { throw 'PackDex failed' }

Write-Host '== 7. zipalign (-f -p 4) =='
& "$bt\zipalign.exe" -f -p 4 "$out\app-packed.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '== 8. sign (apksigner v2+v3) =='
$final = Join-Path $root 'AmbientHome.apk'
if (Test-Path -LiteralPath $final) { Remove-Item -LiteralPath $final -Force }
if (Test-Path -LiteralPath "$final.idsig") { Remove-Item -LiteralPath "$final.idsig" -Force }

if (-not [string]::IsNullOrWhiteSpace($env:AMBIENT_KEYSTORE)) {
    $releaseKs = $env:AMBIENT_KEYSTORE
    if (-not (Test-Path -LiteralPath $releaseKs)) {
        throw "AMBIENT_KEYSTORE не найден: $releaseKs"
    }
    if ([string]::IsNullOrWhiteSpace($env:AMBIENT_KEYSTORE_PASS) -or [string]::IsNullOrWhiteSpace($env:AMBIENT_KEY_ALIAS)) {
        throw 'AMBIENT_KEYSTORE задан, но не заданы AMBIENT_KEYSTORE_PASS и/или AMBIENT_KEY_ALIAS'
    }
    $keyPassArg = if ([string]::IsNullOrWhiteSpace($env:AMBIENT_KEY_PASS)) { 'env:AMBIENT_KEYSTORE_PASS' } else { 'env:AMBIENT_KEY_PASS' }
    & "$bt\apksigner.bat" sign --v4-signing-enabled false --ks $releaseKs `
        --ks-pass env:AMBIENT_KEYSTORE_PASS --key-pass $keyPassArg `
        --ks-key-alias $env:AMBIENT_KEY_ALIAS --out $final "$out\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'apksigner release sign failed' }
    Write-Host "  Signed with RELEASE keystore (alias: $($env:AMBIENT_KEY_ALIAS))"
} else {
    if (-not (Test-Path -LiteralPath $debugKs)) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $ktOut = & "$jdk\bin\keytool.exe" -genkeypair -keystore $debugKs -alias androiddebugkey `
            -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=Android Debug,O=Android,C=US" 2>&1
        $ktRc = $LASTEXITCODE
        $ErrorActionPreference = $prev
        if ($ktRc -ne 0) { throw "keytool failed (rc=$ktRc):`n$($ktOut -join "`n")" }
    }
    & "$bt\apksigner.bat" sign --v4-signing-enabled false --ks $debugKs `
        --ks-pass pass:android --key-pass pass:android `
        --ks-key-alias androiddebugkey --out $final "$out\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'apksigner debug sign failed' }
    Write-Host '  Signed with DEBUG keystore (set AMBIENT_KEYSTORE_* for release signing)'
}

Write-Host '== 9. verify (apksigner + zipalign + ZIP STORED header + SHA-256) =='
$apksigOut = & "$bt\apksigner.bat" verify --verbose --print-certs $final 2>&1
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed:`n$($apksigOut -join "`n")" }
$apksigOut | Where-Object { $_ -match 'Verifies|Verified using|Signer #1 certificate DN|Signer #1 certificate SHA-256' } | ForEach-Object { Write-Host "  $_" }

$zaOut = & "$bt\zipalign.exe" -c -v -p 4 $final 2>&1
if ($LASTEXITCODE -ne 0) { throw "zipalign verify failed:`n$($zaOut -join "`n")" }
$dexZaLine = ($zaOut | Where-Object { $_ -match 'classes\.dex' }) -join ''
Write-Host "  zipalign: $dexZaLine"
if ($dexZaLine -match 'compressed' -or $dexZaLine -notmatch '\(OK\)') {
    throw "classes.dex не STORED/4B-выровнен в zipalign: $dexZaLine"
}

$verifyJava = "$out\VerifyDexZip.java"
$verifyCode = @'
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
'@
[System.IO.File]::WriteAllText($verifyJava, $verifyCode, (New-Object System.Text.UTF8Encoding($false)))
& "$jdk\bin\java.exe" $verifyJava $final
if ($LASTEXITCODE -ne 0) { throw 'VerifyDexZip failed' }

$apkSha256 = (Get-FileHash -LiteralPath $final -Algorithm SHA256).Hash.ToLowerInvariant()
$shaFile = Join-Path $out "AmbientHome-$verName.apk.sha256"
[System.IO.File]::WriteAllText($shaFile, "$apkSha256  AmbientHome-$verName.apk`n", (New-Object System.Text.UTF8Encoding($false)))
Write-Host "  SHA-256: $apkSha256 -> $shaFile"

$size = [math]::Round((Get-Item $final).Length / 1MB, 2)
Write-Host "OK: $final ($size MB, DEX $dexSizeKb KB)"
