# MatrixRTC Protocol Notes (TeleCrypt)

This doc formalizes the call signaling layer we must implement in Tammy.
Media transport is handled by Element Call; MatrixRTC provides signaling.

## Scope
- Client side only (Tammy).
- Focus on event types, derived state, and client flow.
- Server side (LiveKit + auth) is assumed to exist.

## Event Types
Support both stable and unstable names and normalize them:
- Stable: `m.rtc.slot`, `m.rtc.member`
- Unstable: `org.matrix.msc4143.rtc.slot`, `org.matrix.msc4143.rtc.member`

Transport discovery endpoints:
- `/_matrix/client/v1/rtc/transports`
- Fallback: `/_matrix/client/unstable/org.matrix.msc4143/rtc/transports`

## Slot Event (state)
Type: `m.rtc.slot` (or unstable equivalent)
State key: `slot_id`

Rules:
- Empty content `{}` closes the slot (call ended).
- Non-empty content opens the slot.
- `application.type` must be `m.call`.
- `m.call.id` (UUID) identifies the current call session.
- If `m.call.id` changes for the same `slot_id`, treat as a new session.

Slot policy:
- One fixed slot per room (exact `slot_id` constant to be chosen in code).

Slot id decision (v1):
- Use a stable, fixed slot id per room (string constant).
- Suggested constant: `default` (simple, matches "single slot" intent).
- If we later support parallel calls, this becomes multi-slot.

Example (open slot):
```json
{
  "type": "m.rtc.slot",
  "state_key": "default",
  "content": {
    "application": {
      "type": "m.call",
      "url": "https://call.element.io",
      "m.call": {
        "id": "0f1b78d6-2b2d-4c8e-9d0e-2d33f6f4a7f1"
      }
    }
  }
}
```

Example (close slot):
```json
{
  "type": "m.rtc.slot",
  "state_key": "default",
  "content": {}
}
```

## Member Event (sticky)
Type: `m.rtc.member` (or unstable equivalent)

Rules:
- Sticky event identifies a device participation in the current slot.
- Join/refresh must include:
  - `slot_id`
  - `application` with `m.call.id`
  - `member` object with identity data
  - `rtc_transports`
  - `sticky_key` (unique per device)
- Refresh before TTL expires; expired entries are treated as left.
- Leave/disconnect is published by updating the same `sticky_key`
  to a non-connected state and stopping refresh.

Implementation note (TeleCrypt v1):
- Publish sticky membership via
  `PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}`
  with query param `org.matrix.msc4354.sticky_duration_ms`.
- Fallback (only if sticky send fails): publish `m.rtc.member` as a
  state event with `state_key = sticky_key`.
  The watcher accepts both sticky (ephemeral) and state-event forms.

TTL and refresh:
- Sticky events expire; the client must refresh before expiry.
- Refresh cadence should be comfortably below TTL (example: 1/2 TTL).
- TODO: confirm exact TTL field names and defaults from MSC4143.

Example (join/refresh):
```json
{
  "type": "m.rtc.member",
  "content": {
    "slot_id": "default",
    "application": {
      "type": "m.call",
      "m.call": {
        "id": "0f1b78d6-2b2d-4c8e-9d0e-2d33f6f4a7f1"
      }
    },
    "member": {
      "id": "device:OHHSLBTGCE",
      "claimed_user_id": "@fodder:telecrypt.io",
      "claimed_device_id": "OHHSLBTGCE"
    },
    "rtc_transports": [
      {
        "type": "livekit",
        "uri": "wss://example.sfu",
        "params": {
          "jwt": "TOKEN_FROM_SERVER"
        }
      }
    ],
    "sticky_key": "telecrypt-OHHSLBTGCE"
  }
}
```

Example (leave/disconnect):
```json
{
  "type": "m.rtc.member",
  "content": {
    "slot_id": "default",
    "application": {
      "type": "m.call",
      "m.call": {
        "id": "0f1b78d6-2b2d-4c8e-9d0e-2d33f6f4a7f1"
      }
    },
    "member": {
      "id": "device:OHHSLBTGCE",
      "claimed_user_id": "@fodder:telecrypt.io",
      "claimed_device_id": "OHHSLBTGCE"
    },
    "sticky_key": "telecrypt-OHHSLBTGCE",
    "device_id": "OHHSLBTGCE",
    "disconnected": true
  }
}
```

Note: the exact "disconnected" shape can vary; we only require that
the updated content is no longer considered "connected" by our watcher.

## Derived State (Watcher)
Per room, derive:
- `slotOpen` (bool)
- `activeCallId` (m.call.id)
- `activeMembers` (set of non-expired member events)
- `localJoined` (local device in activeMembers)
- `rtcActive` (slotOpen && activeMembers not empty)
- `incoming` (rtcActive && !localJoined && activeCallId is new)

Incoming detection:
- Maintain `lastSeenCallId` per room.
- If activeCallId differs from lastSeenCallId and localJoined is false,
  report incoming and update lastSeenCallId when UI is shown.

## Client Flow
Start call (outgoing):
1) Generate new `m.call.id`.
2) Write open slot with `m.call.id`.
3) Publish local member join and start refresh loop.
4) Launch Element Call with `intent=start_call` (or `start_call_dm`).
5) Transition to in-call only when localJoined becomes true.

Join call (incoming or existing):
1) Use current `slot_id` + `m.call.id`.
2) Publish local member join and start refresh loop.
3) Launch Element Call with `intent=join_existing`.
4) Transition to in-call only when localJoined becomes true.

Decline call:
- No MatrixRTC write required in v1.
- Hide incoming UI and update lastSeenCallId.

Leave call:
1) Stop refresh loop.
2) Publish member leave/disconnect for local `sticky_key`.
3) Close Element Call view.
4) UI returns to idle when watcher reports `rtcActive == false`.

Optional "end call for everyone":
- Close slot by writing `{}` to `m.rtc.slot`.
- This is a policy decision, not required for a local leave.

## Element Call Integration (minimal)
- Use Element Call only for media.
- Control via widget actions:
  - `io.element.join`
  - `im.vector.hangup`
  - `io.element.close`

## Implementation map (Tammy)
We will add two services and wire them into existing call UI and backend:

Read sources:
- Room state stream (for `m.rtc.slot` state events).
- Sticky event stream (for `m.rtc.member`) - may arrive as sticky/ephemeral
  or as a regular room message event depending on SDK support.

Write targets:
- State events (`m.rtc.slot`) written by coordinator.
- Sticky events (`m.rtc.member`) written and refreshed by coordinator.

Integration points in current code:
- `CallRoomHeader` should call `CallCoordinator.start(roomId)` or
  `CallCoordinator.join(roomId)` instead of launching a URL directly.
- `ElementCallLauncherImpl` remains the platform launcher for the call view.
- `callModule` (Koin) registers:
  - `MatrixRtcWatcher`
  - `CallCoordinator`
  - storage for `lastSeenCallId`

We keep URL building in `ElementCallUrl.kt`, but it becomes a detail of
the coordinator rather than UI.

## References
- MSC4143 (MatrixRTC): `m.rtc.slot`, `m.rtc.member`
- MSC3401 (call signaling history)
