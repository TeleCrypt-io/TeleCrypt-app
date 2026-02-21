# TRIXNITY: WHAT TO WRITE FIRST (after MR !665 feedback)

## What exists in Trixnity right now

- Typed events are defined in `trixnity-core` and mapped in:
  - `trixnity-core/src/commonMain/kotlin/de/connect2x/trixnity/core/serialization/events/EventContentSerializerMappingsDefault.kt`
- Room state is already stored by client infrastructure:
  - `trixnity-client/src/commonMain/kotlin/de/connect2x/trixnity/client/room/RoomStateEventHandler.kt`
  - `trixnity-client/src/commonMain/kotlin/de/connect2x/trixnity/client/store/RoomStateStore.kt`
- Experimental MSC flow in codebase:
  - add annotation in `MSCAnnotations.kt`
  - use `@OptIn(MSCxxxx::class)` in mappings/usages

## What is missing for MatrixRTC

- No typed MatrixRTC events:
  - `m.rtc.slot`
  - `m.rtc.member`
- No `MSC4143` annotation.
- No completed support for:
  - MSC4354 sticky events
  - MSC4140 cancellable delayed events

## Why MR !665 was not accepted

- MR started from service/state-machine layer.
- Maintainer expects bottom-up integration:
  1. pure Matrix events (`@MSC4143`)
  2. MSC4354/MSC4140 infrastructure
  3. only then higher-level RTC service
- Custom call state store looked redundant because Trixnity already has store infrastructure.

## Practical order for next upstream work

1. Add `@MSC4143` annotation in core.
2. Add typed event content for `m.rtc.slot` and `m.rtc.member` (+ unstable aliases).
3. Register mappings in `EventContentSerializerMappingsDefault.kt`.
4. Add serialization tests for stable/unstable event types.
5. Implement MSC4354.
6. Implement MSC4140.
7. Return to RTC service API/state-machine.
