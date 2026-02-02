# MatrixRTC terminal smoke test.
# Sends m.rtc.slot + m.rtc.member from caller and confirms callee sees them via /sync.
#
# Usage:
#   pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\rtc_terminal_test.ps1 `
#     -RoomId "!ROOM:server" -CallerRoot "app-data" -CalleeRoot "app-data-2"
#
# Defaults:
#   -CallerRoot "app-data"
#   -CalleeRoot "app-data-2"
#   -SlotId "default"
#   -TimeoutSec 15

param(
    [Parameter(Mandatory = $true)]
    [string]$RoomId,
    [string]$CallerRoot = "app-data",
    [string]$CalleeRoot = "app-data-2",
    [string]$SlotId = "default",
    [int]$TimeoutSec = 15
)

$ErrorActionPreference = "Stop"

function Get-AccountDbCandidates([string]$root) {
    $dbs = Get-ChildItem -Path $root -Recurse -File -Filter database `
        | Where-Object { $_.Directory.Name -eq "database" } `
        | Sort-Object LastWriteTime -Descending
    if (-not $dbs) {
        throw "Account DB not found under $root"
    }
    return $dbs
}

function Get-Account([string]$dbPath) {
    $json = @'
import sqlite3, json, sys
path = sys.argv[1]
con = sqlite3.connect(path)
cur = con.cursor()
row = cur.execute("select baseUrl, userId, deviceId, accessToken, displayName from account limit 1").fetchone()
if not row:
    print("{}")
    raise SystemExit(0)
baseUrl, userId, deviceId, accessToken, displayName = row
print(json.dumps({
  "baseUrl": baseUrl or "",
  "userId": userId or "",
  "deviceId": deviceId or "",
  "accessToken": accessToken or "",
  "displayName": displayName or ""
}))
'@ | python - $dbPath
    return $json | ConvertFrom-Json
}

function Normalize-Homeserver([string]$baseUrl) {
    $trimmed = $baseUrl.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed)) { return "" }
    $noMatrix = $trimmed.Split("/_matrix")[0]
    return $noMatrix.TrimEnd("/")
}

function Encode-Path([string]$value) {
    return [System.Uri]::EscapeDataString($value)
}

function Invoke-Matrix($method, $hs, $path, $token, $body = $null) {
    $uri = "$hs$path"
    $headers = @{ Authorization = "Bearer $token" }
    if ($null -eq $body) {
        return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers
    }
    $json = $body | ConvertTo-Json -Depth 12
    return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -Body $json -ContentType "application/json"
}

function Get-Sync([string]$hs, [string]$token, [string]$since, [int]$timeoutSec) {
    $path = "/_matrix/client/v3/sync?timeout=$([int]($timeoutSec * 1000))"
    if (-not [string]::IsNullOrWhiteSpace($since)) {
        $path += "&since=$([System.Uri]::EscapeDataString($since))"
    }
    return Invoke-Matrix -method "GET" -hs $hs -path $path -token $token
}

Write-Host "Locating account DBs..."
$server = $RoomId.Split(":", 2)[1]
if ([string]::IsNullOrWhiteSpace($server)) {
    throw "Invalid room id: $RoomId"
}
$callerCandidates = Get-AccountDbCandidates $CallerRoot
$calleeCandidates = Get-AccountDbCandidates $CalleeRoot

function Select-Account([System.IO.FileInfo[]]$candidates, [string]$targetServer) {
    foreach ($db in $candidates) {
        $acc = Get-Account $db.FullName
        if ($acc.userId -and $acc.userId.EndsWith(":$targetServer")) {
            return @{ Db = $db.FullName; Account = $acc }
        }
    }
    $fallback = $candidates | Select-Object -First 1
    return @{ Db = $fallback.FullName; Account = (Get-Account $fallback.FullName) }
}

$callerPick = Select-Account $callerCandidates $server
$calleePick = Select-Account $calleeCandidates $server

Write-Host "Loading caller account from $($callerPick.Db)"
$caller = $callerPick.Account
Write-Host "Loading callee account from $($calleePick.Db)"
$callee = $calleePick.Account

if ([string]::IsNullOrWhiteSpace($caller.accessToken) -or [string]::IsNullOrWhiteSpace($callee.accessToken)) {
    throw "Missing accessToken in account DBs."
}

$hs = Normalize-Homeserver $caller.baseUrl
if ([string]::IsNullOrWhiteSpace($hs)) {
    $hs = Normalize-Homeserver $callee.baseUrl
}
if ([string]::IsNullOrWhiteSpace($hs)) {
    throw "Homeserver URL not found in account DBs."
}

Write-Host "Homeserver: $hs"
Write-Host "Caller: $($caller.userId) device=$($caller.deviceId)"
Write-Host "Callee: $($callee.userId) device=$($callee.deviceId)"

$roomPath = Encode-Path $RoomId
$slotPath = Encode-Path $SlotId
$callId = [guid]::NewGuid().ToString()
$stickyKey = if ($caller.deviceId) { "telecrypt-$($caller.deviceId)" } else { "telecrypt-$callId" }
$expiresTs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 60000

Write-Host "Priming callee sync..."
$sync0 = Get-Sync -hs $hs -token $callee.accessToken -since "" -timeoutSec 5
$since = $sync0.next_batch

Write-Host "Sending slot + member from caller..."
$slotContent = @{
    application = @{
        type = "m.call"
        "m.call" = @{ id = $callId }
    }
}
$memberContent = @{
    slot_id = $SlotId
    application = @{
        type = "m.call"
        "m.call" = @{ id = $callId }
    }
    member = @{
        id = if ($caller.deviceId) { "device:$($caller.deviceId)" } else { "device:unknown" }
        claimed_device_id = $caller.deviceId
        claimed_user_id = $caller.userId
    }
    rtc_transports = @()
    sticky_key = $stickyKey
    expires_ts = $expiresTs
}

$slotPathUrl = "/_matrix/client/v3/rooms/$roomPath/state/m.rtc.slot/$slotPath"
Invoke-Matrix -method "PUT" -hs $hs -path $slotPathUrl -token $caller.accessToken -body $slotContent | Out-Null

$txn = "rtc-" + ([guid]::NewGuid().ToString())
$txnPath = Encode-Path $txn
$memberPathUrl = "/_matrix/client/v3/rooms/$roomPath/send/m.rtc.member/$txnPath?org.matrix.msc4354.sticky_duration_ms=60000"
Invoke-Matrix -method "PUT" -hs $hs -path $memberPathUrl -token $caller.accessToken -body $memberContent | Out-Null

Write-Host "Waiting for callee /sync..."
$slotEvents = @()
$memberEvents = @()
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$nextSince = $since

while ((Get-Date) -lt $deadline) {
    $sync1 = Get-Sync -hs $hs -token $callee.accessToken -since $nextSince -timeoutSec 5
    $nextSince = $sync1.next_batch
    $joinRooms = $sync1.rooms.join
    $roomProp = $joinRooms.PSObject.Properties | Where-Object { $_.Name -eq $RoomId } | Select-Object -First 1
    if ($roomProp) {
        $room = $roomProp.Value
        if ($room.state -and $room.state.events) {
            $slotEvents += $room.state.events | Where-Object { $_.type -eq "m.rtc.slot" }
            $memberEvents += $room.state.events | Where-Object { $_.type -eq "m.rtc.member" }
        }
        if ($room.ephemeral -and $room.ephemeral.events) {
            $memberEvents += $room.ephemeral.events | Where-Object { $_.type -eq "m.rtc.member" }
        }
        if ($room.timeline -and $room.timeline.events) {
            $memberEvents += $room.timeline.events | Where-Object { $_.type -eq "m.rtc.member" }
        }
        if ($slotEvents.Count -gt 0 -or $memberEvents.Count -gt 0) {
            break
        }
    }
}

$memberSeen = $memberEvents.Count -gt 0
if (-not $memberSeen) {
    Write-Host "Sticky member not visible; sending state fallback..."
    $stateKeyPath = Encode-Path $stickyKey
    $memberStateUrl = "/_matrix/client/v3/rooms/$roomPath/state/m.rtc.member/$stateKeyPath"
    Invoke-Matrix -method "PUT" -hs $hs -path $memberStateUrl -token $caller.accessToken -body $memberContent | Out-Null

    $memberEvents = @()
    $deadline = (Get-Date).AddSeconds([Math]::Max(5, $TimeoutSec))
    while ((Get-Date) -lt $deadline) {
        $sync2 = Get-Sync -hs $hs -token $callee.accessToken -since $nextSince -timeoutSec 5
        $nextSince = $sync2.next_batch
        $joinRooms2 = $sync2.rooms.join
        $roomProp2 = $joinRooms2.PSObject.Properties | Where-Object { $_.Name -eq $RoomId } | Select-Object -First 1
        if ($roomProp2) {
            $room2 = $roomProp2.Value
            if ($room2.state -and $room2.state.events) {
                $memberEvents += $room2.state.events | Where-Object { $_.type -eq "m.rtc.member" }
            }
            if ($room2.ephemeral -and $room2.ephemeral.events) {
                $memberEvents += $room2.ephemeral.events | Where-Object { $_.type -eq "m.rtc.member" }
            }
            if ($room2.timeline -and $room2.timeline.events) {
                $memberEvents += $room2.timeline.events | Where-Object { $_.type -eq "m.rtc.member" }
            }
            if ($memberEvents.Count -gt 0) {
                break
            }
        }
    }
}

$slotOk = $false
$memberOk = $false
if ($slotEvents.Count -gt 0) {
    $slotOk = $slotEvents[0].content.application.'m.call'.id -eq $callId
} else {
    $slotStateUrl = "/_matrix/client/v3/rooms/$roomPath/state/m.rtc.slot/$slotPath"
    $slotState = Invoke-Matrix -method "GET" -hs $hs -path $slotStateUrl -token $callee.accessToken
    if ($slotState -and $slotState.application -and $slotState.application.'m.call') {
        $slotOk = $slotState.application.'m.call'.id -eq $callId
    }
}
if ($memberEvents.Count -gt 0) {
    $memberOk = $memberEvents | Where-Object { $_.content.application.'m.call'.id -eq $callId } | Select-Object -First 1
    $memberOk = [bool]$memberOk
}

Write-Host "SLOT_OK=$slotOk MEMBER_OK=$memberOk callId=$callId"

Write-Host "Cleaning up (disconnect + close slot)..."
$memberContent.disconnected = $true
$memberContent.expires_ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 1000
Invoke-Matrix -method "PUT" -hs $hs -path $memberPathUrl -token $caller.accessToken -body $memberContent | Out-Null
$closeSlot = @{}
Invoke-Matrix -method "PUT" -hs $hs -path $slotPathUrl -token $caller.accessToken -body $closeSlot | Out-Null

if (-not $slotOk -or -not $memberOk) {
    throw "RTC sync failed. SLOT_OK=$slotOk MEMBER_OK=$memberOk"
}

Write-Host "RTC terminal test OK."
