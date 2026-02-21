# MatrixRTC Room-State Core (Upstream Candidate)

This folder contains a pure protocol-level MatrixRTC room-state core intended
for extraction into `trixnity-client`.

## Included
- `MatrixRtcModels.kt`
- `MatrixRtcService.kt`

## Intention
Provide deterministic room-level call state derived from normalized MatrixRTC
slot/member events:
- session open/close
- participants and expiry
- local joined flag
- incoming/in-call/idle phase

## Non-goals
- no UI integration
- no call provider logic
- no Element Call logic
- no app DI wiring

## Required Inputs
The service expects normalized events:
- `MatrixRtcSlotEvent`
- `MatrixRtcMemberEvent`

Event parsing/normalization is intentionally out of scope for this extraction
step and can be added in a separate follow-up PR.
