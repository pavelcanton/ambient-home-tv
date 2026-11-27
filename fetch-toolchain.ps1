# fetch-toolchain.ps1 — downloads portable JDK 17, Kotlin 2.1.10, and Android build-tools/platform.
# Installs nothing system-wide: everything extracts into .\tools\android-build by default.
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Root = ""
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "tools\android-build"))
}

$dlDir = Join-Path $Root "dl"
foreach ($dirPath in @($Root, $dlDir)) {
    if (-not (Test-Path -LiteralPath $dirPath)) {
        if ($PSCmdlet.ShouldProcess($dirPath, "Create directory")) {
            New-Item -ItemType Directory -Force -Path $dirPath | Out-Null
        }
    }
}

$items = @(
    @{
        name   = "jdk17.zip"
        url    = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip"
        sha256 = "e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0"
    },
    @{
        name   = "kotlin-2.1.10.zip"
        url    = "https://github.com/JetBrains/kotlin/releases/download/v2.1.10/kotlin-compiler-2.1.10.zip"
        sha256 = "c6e9e2636889828e19c8811d5ab890862538c89dc2a3101956dfee3c2a8ba6b1"
    },
    @{
        name   = "build-tools.zip"
        url    = "https://dl.google.com/android/repository/build-tools_r34-windows.zip"
        sha256 = "9be665ad74ef22bf0e489b37db465074484df79b95f26d759945d5c25a47b326"
    },
    @{
        name   = "platform-34-ext7.zip"
        url    = "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
        sha256 = "16fdb74c55e59ae3ef52def135aec713508467bd56d7dabcd8c9be31fa8b20f3"
    }
)

function Get-ArchiveSha256([string]$Path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hashBytes = $sha.ComputeHash($stream)
        return ([System.BitConverter]::ToString($hashBytes) -replace '-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $sha.Dispose()
    }
}

foreach ($it in $items) {
    $dst = Join-Path $dlDir $it.name
    $part = "$dst.part"
    $expected = $it.sha256.ToLowerInvariant()

    if (Test-Path -LiteralPath $dst) {
        $cachedHash = Get-ArchiveSha256 $dst
        if ($cachedHash -eq $expected) {
            Write-Host "skip $($it.name) (sha256 ok)"
            continue
        }
        Write-Warning "cached $($it.name) sha256 mismatch (got $cachedHash, expected $expected) — re-downloading"
        if ($PSCmdlet.ShouldProcess($dst, "Remove mismatched cached archive")) {
            Remove-Item -LiteralPath $dst -Force
        }
    }

    if ($PSCmdlet.ShouldProcess($dst, "Download $($it.url) -> .part, verify SHA-256, and move atomically")) {
        if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force }
        Write-Host "download $($it.name) ..."
        $prevProgress = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        try {
            Invoke-WebRequest -Uri $it.url -OutFile $part -UseBasicParsing -TimeoutSec 1800
            $actual = Get-ArchiveSha256 $part
            if ($actual -ne $expected) {
                throw "SHA-256 mismatch for $($it.name): got $actual, expected $expected"
            }
            Move-Item -LiteralPath $part -Destination $dst -Force
        } catch {
            if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force -ErrorAction SilentlyContinue }
            throw
        } finally {
            $ProgressPreference = $prevProgress
        }
        Write-Host ("  {0} MB (sha256 ok)" -f [math]::Round((Get-Item -LiteralPath $dst).Length/1MB,1))
    }
}

function Install-Archive {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [string]$ZipPath,
        [string]$TargetDir
    )
    if (Test-Path -LiteralPath $TargetDir) { return }
    if (-not $PSCmdlet.ShouldProcess($TargetDir, "Extract $ZipPath")) { return }
    $tmpDir = "${TargetDir}_tmp"
    if (Test-Path -LiteralPath $tmpDir) { Remove-Item -LiteralPath $tmpDir -Recurse -Force }
    try {
        Expand-Archive -LiteralPath $ZipPath -DestinationPath $tmpDir -Force
        $inner = Get-ChildItem -LiteralPath $tmpDir -Directory | Select-Object -First 1
        if (-not $inner) { throw "Unexpected archive layout in $ZipPath (no top-level directory)" }
        Move-Item -LiteralPath $inner.FullName -Destination $TargetDir
    } finally {
        if (Test-Path -LiteralPath $tmpDir) { Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

Write-Host "extract jdk"
Install-Archive (Join-Path $dlDir "jdk17.zip") (Join-Path $Root "jdk")

Write-Host "extract kotlinc"
Install-Archive (Join-Path $dlDir "kotlin-2.1.10.zip") (Join-Path $Root "kotlinc")

Write-Host "extract build-tools"
Install-Archive (Join-Path $dlDir "build-tools.zip") (Join-Path $Root "build-tools")

Write-Host "extract platform"
Install-Archive (Join-Path $dlDir "platform-34-ext7.zip") (Join-Path $Root "platform")

Write-Host "== verify =="
if ($WhatIfPreference) {
    Write-Host "WhatIf: skipping binary verification in $Root"
    return
}
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& "$Root\jdk\bin\java.exe" -version 2>&1
$javaRc = $LASTEXITCODE
$ErrorActionPreference = $prevPref
if ($javaRc -ne 0) { throw "java -version failed (rc=$javaRc)" }
Test-Path "$Root\kotlinc\lib\kotlin-compiler.jar"
Test-Path "$Root\kotlinc\lib\kotlin-stdlib.jar"
Get-ChildItem "$Root\build-tools" | Select-Object -First 10 Name
Test-Path "$Root\platform\android.jar"
Get-ChildItem "$Root\platform" | Select-Object Name
Write-Host "TOOLCHAIN OK: $Root"
