# Upstream Issue Comment Draft (Do Not Send Yet)

Hello! We prepared a first isolated MatrixRTC contribution from our TeleCrypt prototype.

Scope is intentionally minimal and protocol-only:
- normalized MatrixRTC room-state models,
- Flow-based state machine (`slot/member` in -> derived room call state out),
- unit tests for state transitions and edge cases.

No UI integration, no Element Call integration, no app-specific DI code.

Current extraction candidate:
- `MatrixRtcModels`
- `MatrixRtcService`
- tests for incoming/ack, expiry, disconnect, slot close, participant filtering.

If this scope matches your expectation, we will open a small PR with only this
core to keep review focused.

Follow-up steps (separate PRs):
1. parser/normalization layer,
2. higher-level `RtcService` API shape,
3. messenger-level integration.
