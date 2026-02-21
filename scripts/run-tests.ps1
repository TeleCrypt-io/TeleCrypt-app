param(
    [string[]]$GradleArgs = @("desktopTest")
)

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$junctionRoot = Join-Path $env:TEMP "telecrypt-junction"

if (Test-Path $junctionRoot) {
    $item = Get-Item $junctionRoot -Force
    $isJunction = $item.Attributes -band [IO.FileAttributes]::ReparsePoint
    if (-not $isJunction) {
        throw "Junction path already exists and is not a junction: $junctionRoot"
    }
} else {
    New-Item -ItemType Junction -Path $junctionRoot -Target $repoRoot | Out-Null
}

$gradle = Join-Path $junctionRoot "gradlew.bat"
& $gradle -p $junctionRoot @GradleArgs
exit $LASTEXITCODE
