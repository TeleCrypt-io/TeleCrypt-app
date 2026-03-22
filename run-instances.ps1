# Runs two TeleCrypt instances with separate data roots and captures logs.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\run-instances.ps1
#   pwsh       -NoProfile -ExecutionPolicy Bypass -File .\run-instances.ps1
#
# It writes logs to:
#   .\logs\instance-1.log
#   .\logs\instance-2.log
# and prints a short [Call]-focused summary at the end.

$ErrorActionPreference = "Stop"

function Resolve-PowerShellExe {
    $pwsh = Get-Command "pwsh" -ErrorAction SilentlyContinue
    if ($pwsh) { return $pwsh.Source }

    $powershell = Get-Command "powershell" -ErrorAction SilentlyContinue
    if ($powershell) { return $powershell.Source }

    throw "Neither 'pwsh' nor 'powershell' was found in PATH."
}

$psExe = Resolve-PowerShellExe

function Start-TeleCryptInstance(
    [Parameter(Mandatory = $true)][string]$RootPath,
    [Parameter(Mandatory = $true)][string]$Stdout,
    [Parameter(Mandatory = $true)][string]$Stderr
) {
    $commandTemplate = @'
$ErrorActionPreference = "Stop"
$env:TRIXNITY_MESSENGER_ROOT_PATH = "{0}"
$env:TRIXNITY_MESSENGER_MULTI_INSTANCE = "1"
& .\\gradlew.bat run --no-daemon --console=plain
'@
    $command = [string]::Format($commandTemplate, $RootPath)

     return Start-Process -FilePath $psExe -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        $command
    ) -PassThru -NoNewWindow -WorkingDirectory $root `
        -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr
}

    $root = (Get-Location).Path
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$log1Out = Join-Path $logDir "instance-1.out.log"
$log1Err = Join-Path $logDir "instance-1.err.log"
$log2Out = Join-Path $logDir "instance-2.out.log"
$log2Err = Join-Path $logDir "instance-2.err.log"
Remove-Item -Force $log1Out, $log1Err, $log2Out, $log2Err -ErrorAction SilentlyContinue

Write-Host "Starting instance 1 (app-data) ..."
$p1 = Start-TeleCryptInstance -RootPath "app-data" -Stdout $log1Out -Stderr $log1Err

Start-Sleep -Seconds 2

Write-Host "Starting instance 2 (app-data-2) ..."
$p2 = Start-TeleCryptInstance -RootPath "app-data-2" -Stdout $log2Out -Stderr $log2Err

Write-Host "Instances started. Waiting for startup logs..."
Start-Sleep -Seconds 12

Write-Host ""
Write-Host "---- [Call] log summary (instance 1) ----"
Get-Content -Path $log1Out, $log1Err | Select-String -Pattern "\[Call\]" | Select-Object -Last 50 | ForEach-Object { $_.Line }

Write-Host ""
Write-Host "---- [Call] log summary (instance 2) ----"
Get-Content -Path $log2Out, $log2Err | Select-String -Pattern "\[Call\]" | Select-Object -Last 50 | ForEach-Object { $_.Line }

Write-Host ""
Write-Host "PIDs: instance-1=$($p1.Id) instance-2=$($p2.Id)"
Write-Host "To stop them: Stop-Process -Id $($p1.Id),$($p2.Id)"
