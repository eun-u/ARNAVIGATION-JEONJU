param(
    [ValidateRange(1024,65535)][int]$Port = 8018,
    [ValidateRange(1024,65535)][int]$BackendPort = 8000
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $repo 'artifacts\jeonju\field-server'
$codeFile = Join-Path $runtime 'access-token.local.txt'
$expected = Get-Content (Join-Path $repo 'android\app\src\main\assets\jeonju_scope.json') -Raw | ConvertFrom-Json
$actual = Invoke-RestMethod "http://127.0.0.1:$BackendPort/demo/jeonju" -TimeoutSec 10
foreach ($key in @('region_id','dataset_revision','scope_revision','graph_sha256')) {
    if ($actual.$key -ne $expected.$key) { throw "Jeonju contract mismatch: $key" }
}
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
if (-not (Test-Path -LiteralPath $codeFile)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $newCode = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    [IO.File]::WriteAllText($codeFile, $newCode, (New-Object Text.UTF8Encoding $false))
}
$env:NAVI_FIELD_TOKEN = [IO.File]::ReadAllText($codeFile).Trim()
$env:NAVI_FIELD_UPSTREAM = "http://127.0.0.1:$BackendPort"
try {
    & (Join-Path $repo '.venv\Scripts\python.exe') -m uvicorn app.field_gateway:create_gateway --factory --app-dir (Join-Path $repo 'backend') --host 127.0.0.1 --port $Port
} finally {
    Remove-Item Env:NAVI_FIELD_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:NAVI_FIELD_UPSTREAM -ErrorAction SilentlyContinue
}
