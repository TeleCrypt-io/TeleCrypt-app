# Architecture Overview

## Layering

TeleCrypt sits on four layers (bottom → top):

| Layer | Packages | Maven / upstream | Provides |
|-------|----------|------------------|---------|
| **Trixnity** (Matrix SDK) | `net.folivo.trixnity.*` | gitlab.com/connect2x/trixnity (project 26519650) | Matrix CS API, sync loop, Olm/Megolm crypto, event/serialization models |
| **trixnity-messenger** (messenger framework) | `de.connect2x.trixnity.messenger.*`, `de.connect2x.messenger.compose.view.*` | `de.connect2x:trixnity-messenger:3.9.0`, `de.connect2x:trixnity-messenger-compose-view:3.9.0` (gitlab project 47538655) | `MatrixMultiMessengerConfiguration`, ViewModels, i18n, settings, notifications, `composeViewModule` |
| **Tammy** (app shell — this codebase) | `de.connect2x.tammy.*` | gitlab.com/connect2x/tammy.git | Platform configs, BuildConfig, tammyModule, FlatpakPlugin |
| **TeleCrypt** (branding + features) | still `de.connect2x.tammy.*` (NOT renamed — merge hygiene) | this repo | `telecryptModules/call/`, `trixnity/callRtc/`, branding via `pre_merge.sh`/`post_merge.sh` |

**Why packages are NOT renamed:** TeleCrypt keeps `de.connect2x.tammy.*` source packages to ensure clean `git merge` from upstream Tammy. Renaming would cause conflicts on every source file on every upstream release. Users never see package names — they see app id `io.telecrypt.app` and URL scheme `telecrypt://`. See `docs/FORK_MAINTENANCE.md`.

## Direct-Trixnity RTC Side-Channel

`de.connect2x.tammy.trixnity.callRtc.*` and `de.connect2x.tammy.telecryptModules.call.callRtc.*` bypass trixnity-messenger and talk to Trixnity directly (`MatrixClient`, `SyncApiClient`, `OlmDecrypter`, `EventContentSerializerMappings`, sync `Filters`). This is intentional and temporary — trixnity-messenger does not yet expose MatrixRTC support. See comments in `src/commonMain/kotlin/de/connect2x/tammy/trixnity/callRtc/MatrixRtcModels.kt:13-23`. Once trixnity-messenger moves onto the `de.connect2x.trixnity` fork and exposes MatrixRTC, this side-channel can be replaced by the upstream types plus a thin mapping layer.

## Stack
- Kotlin Multiplatform + Compose Multiplatform
- Matrix client stack: Trixnity → trixnity-messenger → Tammy (this codebase) → TeleCrypt (branding + features)
- Koin for DI

## Main Entry Points
- `src/commonMain/kotlin/de/connect2x/tammy/tammyConfiguration.kt`: registers common modules (`composeViewModule`, `notificationsModule`, `tammyModule`, `callModule`).
- Platform-specific configs:
  - `src/desktopMain/kotlin/de/connect2x/tammy/tammyConfiguration.desktop.kt`
  - `src/androidMain/kotlin/de/connect2x/tammy/tammyConfiguration.android.kt`
  - `src/webMain/kotlin/de/connect2x/tammy/tammyConfiguration.web.kt`
  - `src/iosMain/kotlin/de/connect2x/tammy/tammyConfiguration.ios.kt`

## Feature Modules
Custom TeleCrypt features live under:
`src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/`

Example: calls live in `telecryptModules/call` with common interfaces and platform-specific implementations.

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
Branding is generated from `branding/branding.json` via `tools/post_merge.sh` (re-applies TeleCrypt branding after upstream merge or in CI) and `tools/pre_merge.sh` (reverts branded files to upstream state before merge). See `docs/FORK_MAINTENANCE.md` for the full fork-maintenance workflow.
