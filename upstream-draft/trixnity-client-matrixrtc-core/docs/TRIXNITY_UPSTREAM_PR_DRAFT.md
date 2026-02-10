# Trixnity Upstream PR Draft (Local Only)

Status: draft for team review. Do not post externally yet.

## Goal
Submit a minimal PR to `trixnity-client` with pure MatrixRTC room state logic.

## Proposed Scope (include)
- `MatrixRtcModels`-like data model (slot/member/session/participant/room state).
- `MatrixRtcService`-like state machine that:
  - accepts normalized slot/member events,
  - tracks active call/session,
  - tracks participants and expiry,
  - computes `incoming`/`in_call`/`idle` room state,
  - exposes state via Flow.
- Unit tests for state transitions and edge cases.

## Out Of Scope (exclude)
- Element Call integration.
- UI/screens/navigation.
- platform launchers/webview/browser code.
- app DI wiring specific to TeleCrypt.
- sync event transport/parsing glue tied to app event pipeline.

## Current TeleCrypt Sources (candidate for extraction)
- `src/commonMain/kotlin/de/connect2x/tammy/trixnityProposal/callRtc/MatrixRtcModels.kt`
- `src/commonMain/kotlin/de/connect2x/tammy/trixnityProposal/callRtc/MatrixRtcService.kt`
- `src/commonTest/kotlin/de/connect2x/tammy/telecryptModules/call/callRtc/MatrixRtcServiceTest.kt`

## Quality Gate Before Opening PR
- Tests pass locally.
- Names/packages adjusted to trixnity conventions.
- No dependency on Element Call/UI/TeleCrypt modules.
- Clear KDoc on state semantics.
- Explicit stable/unstable normalization policy documented.

## Open Technical Questions For Team (before posting upstream)
- Should incoming detection rely only on `lastSeenCallId`, or also on local membership timestamps?
- Do we want participants keyed only by sticky key or by `(userId, deviceId)` internally?
- Should service expose room-level flow only, or also low-level event decisions for debugging?

## Suggested PR Sequence
1. PR-1: models + service + tests (current target).
2. PR-2: normalized parser + integration hooks (if maintainer agrees with API).
3. PR-3: higher-level `RtcService` API shape and usage examples.

## Draft Upstream Comment (do not send yet)
```
We prepared an isolated MatrixRTC room-state core for trixnity-client.
Scope is intentionally small: normalized slot/member input -> derived room call state output (Flow-based), with tests.
No UI, no call provider, no Element Call specifics.

If this direction matches your expectation, we will open a first small PR with only:
- models,
- state machine,
- tests.
```
