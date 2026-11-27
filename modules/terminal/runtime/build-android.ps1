<#
.SYNOPSIS
    Cross-builds liboperit_terminal.so for aarch64-linux-android26 and drops it into
    modules/terminal/src/main/jniLibs/arm64-v8a/.

.DESCRIPTION
    Gradle never runs cargo (see RUST-SCOPE.md). This script is the only way to
    regenerate the committed .so; the artifact is verified by Gradle
    (verifyTerminalRuntimeBinary) and by the checks printed at the end.

    Toolchain notes:
      * The rustup *shim* on this machine is broken ("missing manifest in toolchain").
        The script therefore resolves the real toolchain bin directory and puts it
        first on PATH, so `cargo` finds the real `rustc.exe`.
      * The NDK `*-clang.cmd` wrappers corrupt `-Wl,--version-script=...` arguments
        (they are batch files). Use clang.exe plus an explicit `--target` link arg.

.PARAMETER Ndk
    NDK root, e.g. C:\Users\xhl18\android-sdk\ndk\25.1.8937393.
    Defaults to $env:ANDROID_NDK_HOME, then the newest NDK under the known SDK roots.

.PARAMETER Online
    Allow cargo network access. Default is --offline (all crates are cached).

.PARAMETER NoCopy
    Build only; do not touch jniLibs.
#>
[CmdletBinding()]
param(
    [string]$Ndk,
    [switch]$Online,
    [switch]$NoCopy
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$moduleDir = Split-Path -Parent $here
$jniLibsDir = Join-Path $moduleDir 'src\main\jniLibs\arm64-v8a'
$targetTriple = 'aarch64-linux-android'
$apiLevel = '26'

function Fail([string]$message) {
    Write-Host "build-android: $message" -ForegroundColor Red
    exit 1
}

# --- NDK -------------------------------------------------------------------
function Resolve-Ndk {
    param([string]$Explicit)
    if ($Explicit) {
        if (Test-Path (Join-Path $Explicit 'toolchains')) { return (Resolve-Path $Explicit).Path }
        Fail "NDK has no toolchains directory: $Explicit"
    }
    if ($env:ANDROID_NDK_HOME -and (Test-Path (Join-Path $env:ANDROID_NDK_HOME 'toolchains'))) {
        return (Resolve-Path $env:ANDROID_NDK_HOME).Path
    }
    $roots = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk'),
        (Join-Path $env:USERPROFILE 'android-sdk\ndk'),
        'C:\dev\android-sdk\ndk',
        'C:\Android\ndk'
    )
    $candidates = foreach ($root in $roots) {
        if (Test-Path $root) {
            Get-ChildItem -Path $root -Directory |
                Where-Object { Test-Path (Join-Path $_.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe') }
        }
    }
    $candidates = @($candidates)
    if ($candidates.Count -eq 0) { Fail 'no NDK found; pass -Ndk <path>' }
    return ($candidates | Sort-Object Name -Descending | Select-Object -First 1).FullName
}

# --- Rust toolchain --------------------------------------------------------
function Resolve-RustBin {
    $rustupHome = if ($env:RUSTUP_HOME) { $env:RUSTUP_HOME } else { Join-Path $env:USERPROFILE '.rustup' }
    $toolchains = Join-Path $rustupHome 'toolchains'
    if (-not (Test-Path $toolchains)) { Fail "no rust toolchains under $toolchains" }
    $withStd = Get-ChildItem -Path $toolchains -Directory |
        Where-Object {
            (Test-Path (Join-Path $_.FullName 'bin\cargo.exe')) -and
            (Test-Path (Join-Path $_.FullName "lib\rustlib\$targetTriple\lib"))
        } |
        Sort-Object Name -Descending
    $withStd = @($withStd)
    if ($withStd.Count -eq 0) {
        Fail "no rust toolchain with the $targetTriple std under $toolchains (run: rustup target add $targetTriple)"
    }
    return (Join-Path $withStd[0].FullName 'bin')
}

$ndkRoot = Resolve-Ndk -Explicit $Ndk
$llvmBin = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$clang = Join-Path $llvmBin 'clang.exe'
$readelf = Join-Path $llvmBin 'llvm-readelf.exe'
$rustBin = Resolve-RustBin
$cargo = Join-Path $rustBin 'cargo.exe'

if (-not (Test-Path $clang)) { Fail "clang.exe missing under $llvmBin" }

Write-Host "build-android: NDK     $ndkRoot"
Write-Host "build-android: rust    $rustBin"

# --- Environment -----------------------------------------------------------
$env:PATH = "$rustBin;$llvmBin;$env:PATH"
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang
$env:CC_aarch64_linux_android = $clang
$env:AR_aarch64_linux_android = (Join-Path $llvmBin 'llvm-ar.exe')
$env:CARGO_TERM_COLOR = 'always'
# The `-Clink-arg=--target=...` that clang.exe needs lives in .cargo/config.toml, scoped
# to this target, so host `cargo test` is unaffected by anything here.

# --- Build -----------------------------------------------------------------
$cargoArgs = @('build', '--release', '--target', $targetTriple, '--manifest-path', (Join-Path $here 'Cargo.toml'))
if (-not $Online) { $cargoArgs += '--offline' }

Push-Location $here
try {
    & $cargo @cargoArgs
    if ($LASTEXITCODE -ne 0) { Fail "cargo build failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

$artifact = Join-Path $here "target\$targetTriple\release\liboperit_terminal.so"
if (-not (Test-Path $artifact)) { Fail "artifact missing: $artifact" }

# --- Verify ----------------------------------------------------------------
$header = & $readelf -h $artifact
if ($LASTEXITCODE -ne 0) { Fail 'llvm-readelf failed' }
$headerText = $header -join "`n"
if ($headerText -notmatch 'Class:\s+ELF64') { Fail 'artifact is not ELF64' }
if ($headerText -notmatch 'Machine:\s+AArch64') { Fail 'artifact is not AArch64' }
if ($headerText -notmatch 'Type:\s+DYN') { Fail 'artifact is not a shared object (ET_DYN)' }

$size = (Get-Item $artifact).Length

if (-not $NoCopy) {
    if (-not (Test-Path $jniLibsDir)) { New-Item -ItemType Directory -Path $jniLibsDir -Force | Out-Null }
    $destination = Join-Path $jniLibsDir 'liboperit_terminal.so'
    Copy-Item -Path $artifact -Destination $destination -Force
    $size = (Get-Item $destination).Length
    Write-Host "build-android: copied -> $destination"
}

Write-Host ''
Write-Host "build-android: $(((Get-Item $artifact).FullName))"
Write-Host ("build-android: size    {0} bytes" -f $size)
Write-Host 'build-android: ELF header'
& $readelf -h $artifact | Select-String -Pattern 'Class:|Data:|Type:|Machine:' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
Write-Host 'build-android: NEEDED'
& $readelf -d $artifact | Select-String -Pattern 'NEEDED' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
Write-Host 'build-android: exported JNI symbols'
& $readelf --dyn-syms --wide $artifact | Select-String -Pattern 'Java_com_ai_assistance_operit_terminal_runtime' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
