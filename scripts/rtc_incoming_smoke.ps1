# MatrixRTC incoming-call smoke test (terminal-only).
# Starts two app instances (separate data roots), sends slot+member via REST,
# and checks logs for incoming=true without auto-launch on callee.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\rtc_incoming_smoke.ps1 `
#     -RoomId "!ROOM:server"
#
#   pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\rtc_incoming_smoke.ps1 `
#     -RoomId "!ROOM:server"
#
param(
    [Parameter(Mandatory = $true)]
    [string]$RoomId,
    [string]$CallerRoot = "app-data",
    [string]$CalleeRoot = "app-data-2",
    [int]$StartupWaitSec = 20,
    [int]$AfterCallWaitSec = 10
)

$ErrorActionPreference = "Stop"

function Resolve-PowerShellExe {
    $pwsh = Get-Command "pwsh" -ErrorAction SilentlyContinue
    if ($pwsh) { return $pwsh.Source }

    $powershell = Get-Command "powershell" -ErrorAction SilentlyContinue
    if ($powershell) { return $powershell.Source }

    throw "Neither 'pwsh' nor 'powershell' was found in PATH."
}

$psExe = Resolve-PowerShellExe

$repo = (Get-Location).Path
$logsDir = Join-Path $repo "logs"
if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }
$log1 = Join-Path $logsDir "rtc-instance-1.out.log"
$log2 = Join-Path $logsDir "rtc-instance-2.out.log"
$log1err = Join-Path $logsDir "rtc-instance-1.err.log"
$log2err = Join-Path $logsDir "rtc-instance-2.err.log"

Remove-Item -Path $log1,$log2,$log1err,$log2err -ErrorAction SilentlyContinue

function Start-TeleCryptInstance([string]$rootPath, [string]$stdout, [string]$stderr) {
    $commandTemplate = @'
$ErrorActionPreference = "Stop"
$env:TRIXNITY_MESSENGER_ROOT_PATH = "{0}"
$env:TRIXNITY_MESSENGER_MULTI_INSTANCE = "1"
& .\\gradlew.bat run --no-daemon --console=plain
'@
    $command = [string]::Format($commandTemplate, $rootPath)
    return Start-Process -FilePath $psExe -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        $command
    ) -WorkingDirectory $repo -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
}

function Find-AccountDb([string]$rootPath) {
    if (-not (Test-Path $rootPath)) { return $null }
    return Get-ChildItem -Path $rootPath -Recurse -File -Filter database -ErrorAction SilentlyContinue `
        | Where-Object { $_.Directory.Name -eq "database" } `
        | Sort-Object LastWriteTime -Descending `
        | Select-Object -First 1
}

$proc1 = $null
$proc2 = $null

try {
    Write-Host "Starting instance-1..."
    $proc1 = Start-TeleCryptInstance $CallerRoot $log1 $log1err

    Write-Host "Starting instance-2 (multi-instance)..."
    $proc2 = Start-TeleCryptInstance $CalleeRoot $log2 $log2err

    Write-Host "Waiting $StartupWaitSec sec for startup..."
    Start-Sleep -Seconds $StartupWaitSec

    $callerDb = Find-AccountDb $CallerRoot
    $calleeDb = Find-AccountDb $CalleeRoot
    if (-not $callerDb -or -not $calleeDb) {
        throw "Account DB not found under '$CallerRoot' and/or '$CalleeRoot'. Open both instances and log in first, then re-run this smoke test."
    }

    Write-Host "Sending MatrixRTC slot+member via REST..."
    & $psExe -NoProfile -ExecutionPolicy Bypass -File .\scripts\rtc_terminal_test.ps1 `
        -RoomId $RoomId -CallerRoot $CallerRoot -CalleeRoot $CalleeRoot | Out-Host

    Write-Host "Waiting $AfterCallWaitSec sec for logs..."
    Start-Sleep -Seconds $AfterCallWaitSec

    Write-Host "Analyzing callee logs..."
    $incoming = Select-String -Path $log2 -Pattern "incoming=true" -SimpleMatch -ErrorAction SilentlyContinue
    $launch = Select-String -Path $log2 -Pattern "Launching Element Call" -SimpleMatch -ErrorAction SilentlyContinue

    if ($incoming) {
        Write-Host "OK: incoming=true observed in callee log."
    } else {
        Write-Host "WARN: incoming=true NOT found in callee log.";
    }

    if ($launch) {
        Write-Host "WARN: callee launched Element Call automatically.";
    } else {
        Write-Host "OK: callee did NOT auto-launch Element Call.";
    }

    Write-Host "Logs: $log1, $log2"
} finally {
    Write-Host "Stopping instances..."
    if ($proc1 -and -not $proc1.HasExited) { Stop-Process -Id $proc1.Id -Force -ErrorAction SilentlyContinue }
    if ($proc2 -and -not $proc2.HasExited) { Stop-Process -Id $proc2.Id -Force -ErrorAction SilentlyContinue }
}
