# TeleCrypt URL Protocol Registration Script
# Run this script as Administrator to register the com.zendev.telecrypt:// URL scheme

$ErrorActionPreference = "Stop"

# Get the directory where this script is located
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Path to the URL handler batch file
$HandlerPath = Join-Path $ScriptDir "telecrypt-url-handler.bat"

# Check if handler exists
if (-not (Test-Path $HandlerPath)) {
    Write-Error "Handler script not found: $HandlerPath"
    exit 1
}

# Escape backslashes for registry
$HandlerPathEscaped = $HandlerPath.Replace('\', '\\')

# Create registry entries
Write-Host "Registering URL protocol: com.zendev.telecrypt://" -ForegroundColor Green

# Create the protocol key
$protocolKey = "HKCU:\Software\Classes\com.zendev.telecrypt"
New-Item -Path $protocolKey -Force | Out-Null
Set-ItemProperty -Path $protocolKey -Name "(Default)" -Value "URL:TeleCrypt Protocol"
Set-ItemProperty -Path $protocolKey -Name "URL Protocol" -Value ""

# Create shell\open\command key
$commandKey = "$protocolKey\shell\open\command"
New-Item -Path $commandKey -Force | Out-Null

# Set the command to run the handler with the URL as argument
$command = "`"$HandlerPath`" `"%1`""
Set-ItemProperty -Path $commandKey -Name "(Default)" -Value $command

Write-Host "URL protocol registered successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "Handler path: $HandlerPath" -ForegroundColor Cyan
Write-Host "Command: $command" -ForegroundColor Cyan
Write-Host ""
Write-Host "You can now use com.zendev.telecrypt:// URLs" -ForegroundColor Yellow
