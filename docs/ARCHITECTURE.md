# Architecture Overview

## Stack
- Kotlin Multiplatform + Compose.
- Matrix client stack from Tammy/Trixnity.
- Koin for DI.

## Main Entry Points
- `src/commonMain/kotlin/de/connect2x/tammy/tammyConfiguration.kt`:
  registers common modules.
- Platform-specific configs:
  - `src/desktopMain/kotlin/de/connect2x/tammy/tammyConfiguration.desktop.kt`
  - `src/androidMain/kotlin/de/connect2x/tammy/tammyConfiguration.android.kt`
  - `src/webMain/kotlin/de/connect2x/tammy/tammyConfiguration.web.kt`
  - `src/iosMain/kotlin/de/connect2x/tammy/tammyConfiguration.ios.kt`

## Feature Modules
Custom TeleCrypt features live under:
`src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/`

Example: calls live in `telecryptModules/call` with
common interfaces and platform-specific implementations.

## Call Signaling Layer (implemented)
We implement MatrixRTC signaling in a small call stack that lives in
`telecryptModules/call` and is wired via Koin in `tammyConfiguration`.

Core services (all implemented):
- `MatrixRtcWatcher`: subscribes to room state + sticky events, normalizes
  stable/unstable MatrixRTC types, and produces derived call state.
- `MatrixRtcSyncEventHandler`: bridges Matrix sync events to the watcher.
- `MatrixRtcAutoStart`: auto-starts RTC handlers when clients connect.
- `CallCoordinator`: state machine (idle/incoming/outgoing/joining/in_call)
  that drives UX and writes MatrixRTC events.
- `IncomingCallManager`: global manager for incoming call state, exposes
  `StateFlow<IncomingCall?>` for full-screen overlay UI.
- `ElementCallLauncher` / bridge: opens the call view and sends widget actions
  (`io.element.join`, `im.vector.hangup`, `io.element.close`).

Data flow:
1) Room state + sticky stream -> MatrixRtcWatcher -> derived state flow.
2) UI actions -> CallCoordinator -> MatrixRTC writes + Element Call control.
3) Coordinator reacts to watcher state to close or recover the call UI.
4) IncomingCallManager observes watcher states and exposes global incoming flow.

## Call Integration Points (current code)
Room UI entry:
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callUi/CallRoomHeader.kt`
  handles the call icon and triggers call launch today.

Incoming call UI (Telegram-style):
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callUi/IncomingCallScreen.kt`
  Full-screen overlay with caller info, pulsing avatar animation, and accept/decline buttons.

Element Call URL and session:
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallUrl.kt`
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallSession.kt`

Call launcher interface + platform impl:
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.kt`
- `src/desktopMain/.../callBackend/CallLauncher.desktop.kt`
- `src/androidMain/.../callBackend/CallLauncher.android.kt`
- `src/nativeMain/.../callBackend/CallLauncher.native.kt`
- `src/webMain/.../callBackend/CallLauncher.web.kt`

Koin module wiring:
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callModule.kt`
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callUiModule.kt`
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callRtcModule.kt`
- `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackendModule.kt`
- platform `callBackendModule.*.kt`

## Branding
Branding is generated from `branding/branding.json`
via `tools/brandify.sh` or `tools/brandify.kts`.
