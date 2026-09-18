param(
    [string]$OutputPath = "android/feature/ai-perception/src/main/assets/efficientdet_lite0_int8.tflite"
)

$ErrorActionPreference = "Stop"
$modelUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
$expectedSha256 = "0720BF247BD76E6594EA28FA9C6F7C5242BE774818997DBBEFFC4DA460C723BB"
$expectedLength = 4602795
$expectedMd5 = "CEBF64AF6C35E5ABD734494685064842"
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputPath))
$outputDirectory = Split-Path -Parent $resolvedOutput

if (Test-Path -LiteralPath $resolvedOutput) {
    $existingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedOutput).Hash
    if ($existingHash -eq $expectedSha256) {
        Write-Output "Baseline model already present and verified: $resolvedOutput"
        exit 0
    }
    throw "Existing model has an unexpected SHA-256: $resolvedOutput"
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$temporary = Join-Path $outputDirectory ("download-" + [guid]::NewGuid().ToString("N") + ".tmp")
try {
    Invoke-WebRequest -UseBasicParsing $modelUrl -OutFile $temporary -TimeoutSec 120
    $downloaded = Get-Item -LiteralPath $temporary
    if ($downloaded.Length -ne $expectedLength) {
        throw "Unexpected model size: $($downloaded.Length)"
    }
    $downloadedHash = (Get-FileHash -Algorithm MD5 -LiteralPath $temporary).Hash
    if ($downloadedHash -ne $expectedMd5) {
        throw "Unexpected model MD5: $downloadedHash"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash -ne $expectedSha256) { throw "model_sha256_mismatch" }
    Move-Item -LiteralPath $temporary -Destination $resolvedOutput
    Write-Output "Downloaded and verified baseline model: $resolvedOutput"
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Force
    }
}
