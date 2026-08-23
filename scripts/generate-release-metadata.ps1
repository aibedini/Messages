param(
    [string]$ApkPath = "app/build/outputs/apk/release/app-release.apk",
    [string]$OutputPath = "app/build/outputs/apk/release/release-metadata.txt"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apk = (Resolve-Path (Join-Path $projectRoot $ApkPath)).Path
$output = Join-Path $projectRoot $OutputPath
$localProperties = Join-Path $projectRoot "local.properties"
$configuredSdk = if (Test-Path $localProperties) {
    (Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1) -replace '^sdk\.dir=', ''
}
$sdkRoot = if ($configuredSdk) { $configuredSdk -replace '\\:', ':' -replace '\\', '/' } else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$buildTools = Join-Path $sdkRoot "build-tools"
$aapt = Get-ChildItem $buildTools -Recurse -Filter aapt.exe | Sort-Object FullName -Descending | Select-Object -First 1
$apksigner = Get-ChildItem $buildTools -Recurse -Filter apksigner.bat | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $aapt -or -not $apksigner) { throw "Android build-tools (aapt/apksigner) not found" }

$badging = & $aapt.FullName dump badging $apk | Select-String "^package:"
$certificate = & $apksigner.FullName verify --print-certs $apk
$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
$certHash = ($certificate | Select-String "Signer #1 certificate SHA-256 digest:").Line.Split(":", 2)[1].Trim()

$metadata = @(
    "artifact=$(Split-Path $apk -Leaf)"
    $badging.Line
    "apk_sha256=$apkHash"
    "signing_certificate_sha256=$certHash"
    "play_protect_status=Internet-sideloaded SMS apps may be blocked pending Google appeal review."
)
Set-Content -LiteralPath $output -Value $metadata -Encoding UTF8
Get-Content -LiteralPath $output
