param(
    [ValidateSet('Replay','Live','Prepare','SelfTest')][string]$Mode = 'Replay',
    [string]$BundleZip = '',
    [string]$ClipId = 'C01',
    [string]$DeviceSerial = '',
    [ValidateSet('Debug','Benchmark')][string]$Variant = 'Debug',
    [int]$DurationSeconds = 600,
    [int]$Port = 8000,
    [switch]$SkipBuild,
    [switch]$NoScreenRecord
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $root
if ($ClipId -notmatch '^[A-Za-z0-9_-]{1,60}$') { throw 'Invalid ClipId' }
if ($DurationSeconds -lt 5 -or $DurationSeconds -gt 900) { throw 'DurationSeconds must be 5..900' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Invalid Port' }
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + $Mode.ToLowerInvariant()
$runDir = Join-Path $root "artifacts/jeonju/runs/$runId"
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$summary = [ordered]@{ status='preparing'; run_id=$runId; mode=$Mode; variant=$Variant; clip_id=$ClipId; device_serial=$DeviceSerial; field_acceptance_verified=$false; error=$null; steps=@(); started_at=(Get-Date).ToUniversalTime().ToString('o') }
$server = $null
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$appRemote = '/sdcard/Android/data/kr.co.navi.mobility/files/jeonju'
$screenBase = "/sdcard/Download/navi-$runId"
$screenStarted = $false
$appLaunched = $false
$nativeIndex = 0

function Quote-WindowsArgument([string]$Value) {
    return '"' + (($Value -replace '(\\*)"','$1$1\"') -replace '(\\+)$','$1$1') + '"'
}
function Invoke-Native([string]$File,[string[]]$Arguments,[int]$TimeoutSeconds=120,[string]$WorkingDirectory=$root) {
    $script:nativeIndex++
    $p = New-Object Diagnostics.Process
    $p.StartInfo.FileName=$File
    $p.StartInfo.Arguments=($Arguments | ForEach-Object { Quote-WindowsArgument $_ }) -join ' '
    $p.StartInfo.WorkingDirectory=$WorkingDirectory
    $p.StartInfo.UseShellExecute=$false
    $p.StartInfo.CreateNoWindow=$true
    $p.StartInfo.WindowStyle='Hidden'
    $p.StartInfo.RedirectStandardOutput=$true
    $p.StartInfo.RedirectStandardError=$true
    if(-not $p.Start()){throw "process_start_failed: $File"}
    $stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
    $timer=[Diagnostics.Stopwatch]::StartNew()
    while(-not $p.WaitForExit(1000)) {
        if($timer.Elapsed.TotalSeconds -gt $TimeoutSeconds){
            # Terminate only the tree rooted in this invocation (e.g. cmd -> Gradle).
            $killer=New-Object Diagnostics.Process
            $killer.StartInfo.FileName=Join-Path $env:SystemRoot 'System32/taskkill.exe'
            $killer.StartInfo.Arguments="/PID $($p.Id) /T /F"
            $killer.StartInfo.UseShellExecute=$false;$killer.StartInfo.CreateNoWindow=$true
            try {if($killer.Start()){$null=$killer.WaitForExit(10000)}} finally {$killer.Dispose()}
            if(-not $p.HasExited){$p.Kill()}
            throw "process_timeout: $File"
        }
    }
    $out=$stdout.Result; $err=$stderr.Result
    [IO.File]::WriteAllText((Join-Path $runDir ("native-{0:D2}.log" -f $script:nativeIndex)), $out+"`n"+$err,$utf8)
    if($p.ExitCode -ne 0){throw "process_failed ($($p.ExitCode)): $File`n$($out | Select-Object -Last 1)`n$err"}
    return $out.Trim()
}
function Step([string]$Name) { $summary.steps += $Name; Write-Output $Name }
function Save-Summary { [IO.File]::WriteAllText((Join-Path $runDir 'run_summary.json'),($summary | ConvertTo-Json -Depth 20),$utf8) }
function Device([string[]]$Arguments,[int]$Timeout=120) { Invoke-Native $adb (@('-s',$DeviceSerial)+$Arguments) $Timeout }
function Capture-DeviceMetrics([string]$Label) {
    $metricStamp=[ordered]@{label=$Label;started_at=(Get-Date).ToUniversalTime().ToString('o');device_serial=$DeviceSerial;app_pid=$null}
    foreach($kind in @('meminfo','thermalservice','battery')) {
        try {
            $arguments=@('shell','dumpsys',$kind)
            if($kind -eq 'meminfo'){$arguments+=@('kr.co.navi.mobility')}
            $result=Device $arguments 10
            [IO.File]::WriteAllText((Join-Path $runDir "$Label-$kind.txt"),$result,$utf8)
        } catch { $summary.steps += "device_metric_unavailable:$Label/$kind" }
    }
    try {
        $appPid=(Device @('shell','pidof','kr.co.navi.mobility') 5).Trim()
        if($appPid -notmatch '^\d+$'){throw 'single_app_process_not_found'}
        $metricStamp.app_pid=[int]$appPid
        # Two samples give top a measured interval; its first sample can be since boot.
        $cpu=Device @('shell','top','-b','-n','2','-d','1','-p',$appPid,'-o','PID,ELAPSED,%CPU,%MEM,RES,CMDLINE') 8
        [IO.File]::WriteAllText((Join-Path $runDir "$Label-cpu.txt"),$cpu,$utf8)
        $metricStamp.cpu_basis='second top sample, one-second interval; percent can exceed 100 on multiple cores'
    } catch { $metricStamp.cpu_error=$_.Exception.Message;$summary.steps += "device_metric_unavailable:$Label/cpu" }
    $metricStamp.finished_at=(Get-Date).ToUniversalTime().ToString('o')
    [IO.File]::WriteAllText((Join-Path $runDir "$Label-metrics.json"),($metricStamp | ConvertTo-Json),$utf8)
}

try {
    if(-not $BundleZip) {
        $choices=@(Get-ChildItem -LiteralPath (Join-Path $root 'data') -File -Filter 'NaVi_Jeonju_P0_FINAL_*.zip')
        if($choices.Count -ne 1){throw 'bundle_missing_or_ambiguous: pass -BundleZip'}
        $BundleZip=$choices[0].FullName
    }
    $BundleZip=[IO.Path]::GetFullPath($BundleZip)
    if(-not (Test-Path -LiteralPath $BundleZip)){throw "bundle_missing: $BundleZip"}
    $python=Join-Path $root '.venv/Scripts/python.exe'
    if(-not (Test-Path -LiteralPath $python)) {
        Step 'Creating Python environment'
        Invoke-Native 'python.exe' @('-m','venv',(Join-Path $root '.venv')) 120 | Out-Null
    }
    Step 'Checking Python runtime dependencies'
    $dependencyCheck='import sys; assert sys.version_info >= (3,11), "Python 3.11+ required"; import fastapi,networkx,pydantic,uvicorn,httpx,pytest,geopandas,osmnx,rasterio,shapely,xlrd; import importlib.metadata as m; assert int(m.version("pydantic").split(".")[0])==2, "Pydantic 2 required"'
    try {Invoke-Native $python @('-c',$dependencyCheck) 60 | Out-Null}
    catch {
        Step 'Installing missing or incompatible Python dependencies'
        Invoke-Native $python @('-m','pip','install','-e','.[dev,geo]') 600 | Out-Null
        Invoke-Native $python @('-c',$dependencyCheck) 60 | Out-Null
    }
    Step 'Checking bundle integrity and importing P0 source bytes'
    Invoke-Native $python @('scripts/import_jeonju_p0.py','--archive',$BundleZip) | Out-Null
    Invoke-Native $python @('scripts/validate_jeonju_p0.py','--output',(Join-Path $runDir 'validation.json')) | Out-Null
    $manifest=Get-Content -LiteralPath (Join-Path $root 'data/processed/jeonju/p0_manifest.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    $summary.dataset_revision=$manifest.dataset_revision
    $summary.scope_revision=$manifest.scope_revision
    $summary.graph_sha256=$manifest.graph_sha256
    $summary.bundle_sha256=$manifest.bundle_sha256
    Step 'Checking pinned model'
    Invoke-Native 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $root 'scripts/fetch_ai_baseline_model.ps1')) 180 | Out-Null
    $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
    $env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    $sdk=($env:ANDROID_HOME -replace '\\','/').Replace(':','\:')
    $propertiesPath=Join-Path $root 'android/local.properties'
    $properties=@()
    if(Test-Path -LiteralPath $propertiesPath){$properties=@(Get-Content -LiteralPath $propertiesPath -Encoding UTF8 | Where-Object{$_ -notmatch '^\s*(sdk\.dir|NAVI_BACKEND_URL)\s*='})}
    $properties+=@("sdk.dir=$sdk","NAVI_BACKEND_URL=http://127.0.0.1:$Port")
    [IO.File]::WriteAllText($propertiesPath,($properties -join "`n")+"`n",$utf8)
    if(-not $SkipBuild) {
        Step "Building $Variant APK"
        Invoke-Native 'cmd.exe' @('/d','/c','gradlew.bat',":app:assemble$Variant",'--console=plain') 600 (Join-Path $root 'android') | Out-Null
    }
    $builtApk=Join-Path $root ("android/app/build/outputs/apk/{0}/app-{0}.apk" -f $Variant.ToLowerInvariant())
    if(-not (Test-Path -LiteralPath $builtApk)){throw "apk_missing: $builtApk"}
    # Validate and install a run-owned copy so a later build cannot replace the tested input.
    $apk=Join-Path $runDir ("app-{0}.apk" -f $Variant.ToLowerInvariant())
    Copy-Item -LiteralPath $builtApk -Destination $apk
    $summary.apk_source=$builtApk
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apkArchive=[IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $scopeEntry=$apkArchive.GetEntry('assets/jeonju_scope.json')
        if($null -eq $scopeEntry){throw 'apk_scope_missing: rebuild APK'}
        $scopeReader=New-Object IO.StreamReader($scopeEntry.Open())
        try {$packagedScope=$scopeReader.ReadToEnd() | ConvertFrom-Json} finally {$scopeReader.Dispose()}
        foreach($key in @('region_id','dataset_revision','scope_revision','graph_sha256')){if($packagedScope.$key -ne $manifest.$key){throw "apk_${key}_mismatch: rebuild APK"}}
        $modelEntry=$apkArchive.GetEntry('assets/efficientdet_lite0_int8.tflite')
        if($null -eq $modelEntry){throw 'apk_model_missing: rebuild APK'}
        $modelStream=$modelEntry.Open();$modelHasher=[Security.Cryptography.SHA256]::Create()
        try {$packagedModelHash=([BitConverter]::ToString($modelHasher.ComputeHash($modelStream))).Replace('-','').ToLowerInvariant()}
        finally {$modelStream.Dispose();$modelHasher.Dispose()}
        if($packagedModelHash -ne '0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb'){throw 'apk_model_hash_mismatch'}
    } finally {$apkArchive.Dispose()}
    $summary.apk=$apk;$summary.apk_sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Step 'Checking dedicated Jeonju server'
    $health=$null
    try {$health=Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 3} catch {}
    if($null -eq $health) {
        $env:NAVI_GRAPH_PATH=Join-Path $root $manifest.graph_path
        $env:NAVI_DB_PATH=Join-Path $root "data/runtime/jeonju_p0_$($manifest.dataset_revision)_$runId.db"
        $summary.database=$env:NAVI_DB_PATH
        $server=Start-Process -FilePath $python -ArgumentList @('-m','uvicorn','app.main:app','--app-dir','backend','--host','127.0.0.1','--port',"$Port") -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDir 'server.stdout.log') -RedirectStandardError (Join-Path $runDir 'server.stderr.log') -PassThru
        $until=(Get-Date).AddSeconds(30)
        do {Start-Sleep -Milliseconds 500; try {$health=Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 2} catch {}; if($server.HasExited){throw 'server_start_failed: see server.stderr.log'}} while($null -eq $health -and (Get-Date) -lt $until)
        if($null -eq $health){throw 'server_start_timeout'}
    }
    foreach($key in @('region_id','dataset_revision','scope_revision','graph_sha256')){if($health.$key -ne $manifest.$key){throw "server_${key}_mismatch"}}
    if($health.database.graph_revision -ne 0 -or $null -eq $health.database.edge_overlay_count -or $health.database.edge_overlay_count -ne 0){throw 'server_graph_overlay_present: use a clean dedicated P0 server/database'}
    $summary.server_health=$health
    if($Mode -eq 'Prepare') {$summary.status='preflight_passed';Step 'Data, route, model and APK preflight passed';return}
    if($Mode -eq 'Replay') {
        $clip=Join-Path $root "data/replay/jeonju_p0/$ClipId"
        Step "Validating measured replay input: $clip"
        Invoke-Native $python @('scripts/validate_jeonju_clip.py',$clip) | Out-Null
    }
    if(-not (Test-Path -LiteralPath $adb)){throw 'adb_missing'}
    $devices=Invoke-Native $adb @('devices') 15
    $available=@($devices -split "`n" | Where-Object{$_ -match '^\S+\s+device\s*$'} | ForEach-Object{($_ -split '\s+')[0]})
    if(-not $DeviceSerial) {if($available.Count -ne 1){throw 'device_required: specify -DeviceSerial'}; $DeviceSerial=$available[0]}
    if($DeviceSerial -notin $available){throw "device_not_ready: $DeviceSerial"}
    if($Mode -eq 'Live' -and $DeviceSerial.StartsWith('emulator-')){throw 'physical_device_required_for_live_capture'}
    $summary.device_serial=$DeviceSerial
    Step "Installing $Variant on $DeviceSerial"
    Device @('install','-r',$apk) | Out-Null
    Device @('reverse',"tcp:$Port","tcp:$Port") | Out-Null
    if($Mode -eq 'Replay') {
        Device @('shell','mkdir','-p',"$appRemote/replay") | Out-Null
        Device @('push',$clip,"$appRemote/replay/") | Out-Null
    }
    if(-not $NoScreenRecord) {
        $seconds=[Math]::Min(180,$DurationSeconds)
        $screenScript="screenrecord --time-limit $seconds $screenBase.mp4 >$screenBase.log 2>&1 &`necho `$! >$screenBase.pid`n"
        [IO.File]::WriteAllText((Join-Path $runDir 'record.sh'),$screenScript,$utf8)
        Device @('push',(Join-Path $runDir 'record.sh'),"$screenBase.sh") | Out-Null
        Device @('shell','sh',"$screenBase.sh") | Out-Null
        $screenStarted=$true
    }
    Step "Starting $Mode"
    Device @('shell','am','force-stop','kr.co.navi.mobility') | Out-Null
    $launch=@('shell','am','start','-W','-n','kr.co.navi.mobility/.MainActivity','--es','jeonju_mode',$Mode,'--es','clip_id',$ClipId,'--es','run_id',$runId,'--es','backend_url',"http://127.0.0.1:$Port")
    if($Mode -eq 'Replay'){$launch+=@('--ez','auto_start','true')}
    Device $launch 30 | Out-Null
    $appLaunched=$true
    Capture-DeviceMetrics 'start'
    $until=(Get-Date).AddSeconds($DurationSeconds)
    $nextMetrics=(Get-Date).AddSeconds(30)
    $deviceSummary=$null
    do {
        Start-Sleep -Seconds 1
        try {$raw=Device @('shell','cat',"$appRemote/runs/$runId/run_summary.json") 5; $deviceSummary=$raw | ConvertFrom-Json} catch {}
        if($null -eq $deviceSummary -and (Get-Date) -ge $nextMetrics){Capture-DeviceMetrics (Get-Date -Format 'HHmmss');$nextMetrics=(Get-Date).AddSeconds(30)}
    } while($null -eq $deviceSummary -and (Get-Date) -lt $until)
    try { Device @('pull',"$appRemote/runs/$runId",(Join-Path $runDir 'app')) | Out-Null } catch { $summary.app_artifact_error=$_.Exception.Message }
    if($null -eq $deviceSummary){throw 'app_run_timeout: finish preparation and start, or inspect app/events.jsonl'}
    $summary.app=$deviceSummary
    if($Mode -eq 'Live' -and $deviceSummary.recording) {
        Device @('pull',"$appRemote/exports/$runId",(Join-Path $runDir 'exports')) | Out-Null
        $savedClip=Join-Path $runDir "exports/$ClipId"
        $targetClip=Join-Path $root "data/replay/jeonju_p0/$ClipId"
        if((Test-Path -LiteralPath (Join-Path $savedClip 'clip_manifest.json')) -and -not (Test-Path -LiteralPath $targetClip)) {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetClip) | Out-Null
            Copy-Item -LiteralPath $savedClip -Destination $targetClip -Recurse
            $summary.replay_input=$targetClip
        }
    }
    $expectedStatus=switch($Mode){'SelfTest'{'model_loading_verified'} 'Replay'{'replay_executed_acceptance_pending'} 'Live'{'live_recorded_acceptance_pending'}}
    if($deviceSummary.status -ne $expectedStatus){throw "app_not_completed ($($deviceSummary.status)): $($deviceSummary.error)"}
    $summary.status=$deviceSummary.status
} catch {
    $summary.status='failed';$summary.error=$_.Exception.Message
    Write-Warning $summary.error
} finally {
    if($DeviceSerial -and (Test-Path -LiteralPath $adb)) {
        if($appLaunched) {
            Capture-DeviceMetrics 'end'
            try {$exits=Device @('shell','dumpsys','activity','exit-info','kr.co.navi.mobility') 10;[IO.File]::WriteAllText((Join-Path $runDir 'app-exit-info.txt'),$exits,$utf8)} catch {}
        }
        if($screenStarted) {
            try {
                $screenPid=(Device @('shell','cat',"$screenBase.pid") 5).Trim()
                if($screenPid -match '^\d+$') {
                    $command=Device @('shell','cat',"/proc/$screenPid/cmdline") 5
                    if($command.Contains("$screenBase.mp4")){Device @('shell','kill','-2',$screenPid) 5 | Out-Null;Start-Sleep -Seconds 1}
                }
            } catch { $summary.screen_record_process_note=$_.Exception.Message }
            # screenrecord can finish normally at 180 seconds; collecting its file
            # must not depend on /proc still containing the recorder process.
            try {Device @('pull',"$screenBase.mp4",(Join-Path $runDir 'screenrecord.mp4')) | Out-Null}
            catch { $summary.screen_record_error=$_.Exception.Message }
        }
        try { Device @('shell','screencap','-p',"$screenBase.png") | Out-Null;Device @('pull',"$screenBase.png",(Join-Path $runDir 'screen.png')) | Out-Null } catch {}
        try { $log=Device @('logcat','-d','-v','threadtime','NaViJeonju:I','AndroidRuntime:E','*:S') 15;[IO.File]::WriteAllText((Join-Path $runDir 'logcat.txt'),$log,$utf8) } catch {}
    }
    if($null -ne $server -and -not $server.HasExited){Stop-Process -Id $server.Id}
    $summary.finished_at=(Get-Date).ToUniversalTime().ToString('o');Save-Summary
    Write-Output "Summary: $(Join-Path $runDir 'run_summary.json')"
}
if($summary.status -eq 'failed'){exit 1}
