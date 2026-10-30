#!/usr/bin/env pwsh
[CmdletBinding()]
param(
    [string]$KeyStore = $env:ARLINUX_RELEASE_KEYSTORE,
    [string]$SigningEnv = $env:ARLINUX_SIGNING_ENV,
    [switch]$Publish,
    [string]$Wrangler = 'wrangler'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Run([string]$Program, [Parameter(ValueFromRemainingArguments)][string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed ($LASTEXITCODE)" }
}
if ($Publish) {
    $Apk = Join-Path $PSScriptRoot 'build/arlinux-installer.apk'
    $Receipt = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build/installer.json') -Raw | ConvertFrom-Json
    if ((Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Receipt.sha256 -or
        (Get-Item -LiteralPath $Apk).Length -ne $Receipt.sizeBytes) { throw 'Installer does not match its build receipt.' }
    Run 'gh' 'release' 'upload' 'latest' $Apk '--repo' 'taowen/arlinux-installer' '--clobber'
    Run $Wrangler 'r2' 'object' 'put' 'arlinux-downloads/android/arlinux-installer.apk' '--remote' '--file' $Apk `
        '--content-type' 'application/vnd.android.package-archive' '--cache-control' 'public, max-age=60'
    return
}
if (-not $KeyStore -or -not (Test-Path -LiteralPath $KeyStore -PathType Leaf)) { throw 'Pass an external release -KeyStore.' }
if ($SigningEnv) {
    foreach ($line in Get-Content -LiteralPath $SigningEnv) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"', "'"), 'Process')
        }
    }
}
if (-not $env:ARLINUX_RELEASE_STORE_PASSWORD) { $env:ARLINUX_RELEASE_STORE_PASSWORD = $env:ARCTRL_RELEASE_STORE_PASSWORD }
if (-not $env:ARLINUX_RELEASE_KEY_PASSWORD) { $env:ARLINUX_RELEASE_KEY_PASSWORD = $env:ARCTRL_RELEASE_KEY_PASSWORD }
if (-not $env:ARLINUX_RELEASE_KEY_ALIAS) { $env:ARLINUX_RELEASE_KEY_ALIAS = $env:ARCTRL_RELEASE_KEY_ALIAS }
if (-not $env:ARLINUX_RELEASE_STORE_PASSWORD) { throw 'Missing ARLINUX_RELEASE_STORE_PASSWORD.' }
if (-not $env:ARLINUX_RELEASE_KEY_PASSWORD) { $env:ARLINUX_RELEASE_KEY_PASSWORD = $env:ARLINUX_RELEASE_STORE_PASSWORD }
if (-not $env:ARLINUX_RELEASE_KEY_ALIAS) { $env:ARLINUX_RELEASE_KEY_ALIAS = 'connect-screen' }
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to Android SDK 36.' }
$Tools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$ApkSigner = Join-Path $Tools $(if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' })
$ZipAlign = Join-Path $Tools $(if ($IsWindows) { 'zipalign.exe' } else { 'zipalign' })
Push-Location $PSScriptRoot
try {
    Run $(if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }) ':app:assembleRelease'
    New-Item -ItemType Directory -Force build | Out-Null
    Run $ZipAlign '-P' '16' '-f' '4' 'app/build/outputs/apk/release/app-release-unsigned.apk' 'build/aligned.apk'
    Run $ApkSigner 'sign' '--ks' $KeyStore '--ks-key-alias' $env:ARLINUX_RELEASE_KEY_ALIAS `
        '--ks-pass' 'env:ARLINUX_RELEASE_STORE_PASSWORD' '--key-pass' 'env:ARLINUX_RELEASE_KEY_PASSWORD' `
        '--out' 'build/arlinux-installer.apk' 'build/aligned.apk'
    Run $ApkSigner 'verify' '--verbose' 'build/arlinux-installer.apk'
    $Apk = Join-Path $PSScriptRoot 'build/arlinux-installer.apk'
    $Sha = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    @{ sha256 = $Sha; sizeBytes = (Get-Item -LiteralPath $Apk).Length; versionName = '0.0.1' } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'build/installer.json')
    Write-Host "APK: $Apk ($((Get-Item -LiteralPath $Apk).Length) bytes) SHA-256: $Sha"
} finally { Pop-Location }
