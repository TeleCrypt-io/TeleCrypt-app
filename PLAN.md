# PLAN

Next: Send/receive real m.call.invite events and show incoming call UI (replace link-only behavior).

Now:
- Decide on desktop in-app call window approach (JCEF vs system webview).

Later:
- Decide on CI version suffix (drop -DEV- or keep) and update build suffix logic if approved.
- Prepare minimal CallOverlay hook PR for trixnity-messenger.

Done:
- Send call link message with HTML anchor and plain-text fallback.
- Normalize Element Call URL building to omit invalid roomId param across platforms.
- Unify Element Call URL building in common code to avoid platform drift.
- Resolve roomId/displayName for calls on JVM and post call link message to the room.
- Desktop SSO callback now resumes login in-app when possible (no restart hint).
- Desktop SSO now waits for Koin before resume/login to avoid startup race.
- Desktop SSO now searches Koin scopes for login view model/client during runtime injection.
- Desktop SSO now falls back to settings-based resume using MatrixClients when view model is unavailable.
- Desktop SSO fallback now scans all scopes for matching SSO state and waits for settings init.
- Desktop SSO now emits resume URL into UrlHandler to trigger RootRouter resume flow.
- Element Call URL now uses /#/room/<roomId> (no roomId param) to match Element Call routing.
- Add SPEC/RUNBOOK/TEAM_INBOX baseline docs.
