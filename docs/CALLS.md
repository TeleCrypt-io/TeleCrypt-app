# Calls (Element Call)

## Current Approach
We embed Element Call in a platform webview (desktop/mobile) or fall back
to the system browser when needed. The call link is built in
`src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallUrl.kt`.

## Flow (current)
1. User hits call button in a room header.
2. Client shows Audio/Video choice dialog.
3. CallCoordinator generates MatrixRTC `m.rtc.slot` + `m.rtc.member` events.
4. Client launches Element Call via `CallLauncher`.
5. A "Join call" message is sent to the room for others.

## Incoming Call Flow (Telegram-style)
1. MatrixRtcWatcher detects incoming call (slot open + remote members).
2. CallRoomHeader registers incoming to IncomingCallManager.
3. IncomingCallScreen shows full-screen overlay with:
   - Caller name and room info
   - Pulsing avatar animation  
   - Accept (Audio/Video) and Decline buttons
4. On accept: CallCoordinator.joinCall() is called.
5. On decline: IncomingCallManager.declineCall() acks the call.

## Protocol (MatrixRTC)
See `docs/CALLS_PROTOCOL.md` for:
- the MatrixRTC events we read/write (`m.rtc.slot`, `m.rtc.member`)
- transport discovery and selection
- watcher derived state and call state machine
- coordinator actions and Element Call bridge commands

## Gaps (known)
- Linux webview can be unstable; fallback to browser may be required.
- No ringtone/vibration yet.
- No background notifications for incoming calls.

## Target Behavior
Match Telegram-style calls:
- [x] Start call -> ring recipient, allow accept/decline.
- [x] Full-screen incoming call UI with animation.
- [ ] Ringtone for incoming calls.
- [ ] Group call -> open call room and wait for participants.
