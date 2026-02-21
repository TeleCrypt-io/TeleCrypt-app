# MatrixRTC incoming-call smoke test (terminal-only).
# Starts two app instances (separate data roots), sends slot+member via REST,
# and checks logs for incoming=true without auto-launch on callee.
#
# Usage:
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

$repo = Get-Location
$logsDir = Join-Path $repo "logs"
if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }
$log1 = Join-Path $logsDir "rtc-instance-1.out.log"
$log2 = Join-Path $logsDir "rtc-instance-2.out.log"
$log1err = Join-Path $logsDir "rtc-instance-1.err.log"
$log2err = Join-Path $logsDir "rtc-instance-2.err.log"

Remove-Item -Path $log1,$log2,$log1err,$log2err -ErrorAction SilentlyContinue

function Start-Instance([string]$command, [string]$stdout, [string]$stderr) {
    return Start-Process -FilePath "pwsh" `
        -ArgumentList @("-NoProfile","-ExecutionPolicy","Bypass","-Command", $command) `
        -WorkingDirectory $repo `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
}

Write-Host "Starting instance-1..."
$proc1 = Start-Instance "& .\\run.ps1" $log1 $log1err

Write-Host "Starting instance-2 (multi-instance)..."
$proc2 = Start-Instance "& .\\scripts\\run_instance_2.ps1" $log2 $log2err

Write-Host "Waiting $StartupWaitSec sec for startup..."
Start-Sleep -Seconds $StartupWaitSec

Write-Host "Sending MatrixRTC slot+member via REST..."
& pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\rtc_terminal_test.ps1 `
    -RoomId $RoomId -CallerRoot $CallerRoot -CalleeRoot $CalleeRoot | Out-Host

Write-Host "Waiting $AfterCallWaitSec sec for logs..."
Start-Sleep -Seconds $AfterCallWaitSec

Write-Host "Stopping instances..."
if ($proc1 -and -not $proc1.HasExited) { Stop-Process -Id $proc1.Id -Force }
if ($proc2 -and -not $proc2.HasExited) { Stop-Process -Id $proc2.Id -Force }

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
