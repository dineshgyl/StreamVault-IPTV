param(
    [string]$Scenario = "manual",
    [string]$PackageName = "com.streamvault.app",
    [string]$OutputRoot = "artifacts/runtime-validation"
)

$ErrorActionPreference = "Stop"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found."
    }
}

Require-Command adb

$adbState = (& adb get-state 2>$null)
if ($LASTEXITCODE -ne 0 -or $adbState.Trim() -ne "device") {
    throw "No connected Android device was found. Connect a device and ensure 'adb get-state' returns 'device'."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$safeScenario = ($Scenario -replace "[^A-Za-z0-9._-]", "_")
$outputDir = Join-Path $OutputRoot "$timestamp-$safeScenario"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

Write-Host "Capturing runtime diagnostics for scenario '$Scenario' into $outputDir"

& adb shell dumpsys meminfo $PackageName | Out-File (Join-Path $outputDir "meminfo.txt") -Encoding utf8
& adb shell dumpsys gfxinfo $PackageName framestats | Out-File (Join-Path $outputDir "gfxinfo-framestats.txt") -Encoding utf8
& adb shell dumpsys activity processes | Out-File (Join-Path $outputDir "activity-processes.txt") -Encoding utf8
& adb shell dumpsys thermalservice | Out-File (Join-Path $outputDir "thermalservice.txt") -Encoding utf8
& adb shell top -n 1 -o PID,RES,CPU%,ARGS | Out-File (Join-Path $outputDir "top.txt") -Encoding utf8

$runtimeLogPath = Join-Path $outputDir "runtime-memory.log"
$runAsOutput = & adb shell run-as $PackageName cat files/diagnostics/runtime-memory.log 2>&1
if ($LASTEXITCODE -eq 0) {
    $runAsOutput | Out-File $runtimeLogPath -Encoding utf8
} else {
    $runAsOutput | Out-File (Join-Path $outputDir "run-as-error.txt") -Encoding utf8
}

& adb logcat -d LeakCanary:V RuntimeDiagnostics:I AndroidRuntime:E *:S | Out-File (Join-Path $outputDir "targeted-logcat.txt") -Encoding utf8

@"
scenario=$Scenario
package=$PackageName
capturedAt=$timestamp
deviceState=$adbState
"@ | Out-File (Join-Path $outputDir "capture-metadata.txt") -Encoding utf8

Write-Host "Capture complete."
