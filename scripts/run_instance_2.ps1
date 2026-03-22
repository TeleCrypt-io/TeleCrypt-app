$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot
try {
	$env:TRIXNITY_MESSENGER_MULTI_INSTANCE = "1"
	$env:TRIXNITY_MESSENGER_ROOT_PATH = "app-data-2"
	& .\gradlew.bat run --no-daemon --console=plain
	exit $LASTEXITCODE
} finally {
	Pop-Location
}
