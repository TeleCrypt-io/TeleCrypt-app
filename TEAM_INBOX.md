# TEAM_INBOX

- 2025-12-14 | @sanyanime | Propose minimal UI hook PR to trixnity-messenger (CallOverlay interface + NoOp + DI + MainView insert). | status: todo | location: external repo trixnity-messenger-compose-view/.../DI.kt, MainView.kt
- 2025-12-15 | @alexanderpotemkin | Remove sync.py; confirm version suffix format and whether to drop -DEV-. | status: todo (version), done (sync.py absent) | location: buildSrc/src/main/kotlin/utils.kt
- 2025-12-15 | @sonicsupergedgehog/@sanyanime | Split call work: UI (button/screen) vs backend (Element Call URL + Matrix events). | status: doing | location: src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/**
- 2025-12-19 | @sanyanime | Implement ElementCallLauncherImpl.launchCall to generate valid Element Call URL and send invite event. | status: doing (URL + link message done; m.call.invite TBD) | location: src/**/callBackend/CallLauncher*.kt, src/**/callUi/CallRoomHeader.kt
- 2025-12-19 | @alexanderpotemkin | Desktop call should open in a separate window (Telegram-like), not external browser. | status: research | location: src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.desktop.kt
- 2025-12-21 | @alexanderpotemkin | Decide on removing "-DEV-" from CI version suffix. | status: todo | location: buildSrc/src/main/kotlin/utils.kt
- 2025-12-21 | @sanyanime | Verify Element Call URL works in public room "Test" on telecrypt.io (got "call not found" elsewhere). | status: todo | location: src/**/callBackend/CallLauncher*.kt
- 2025-12-22 | @sanyanime | Android webview opener not working; decide whether to rewrite and align platform behavior. | status: todo | location: src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.android.kt
- 2025-12-22 | @sanyanime | Unify Element Call URL generation across platforms (no expect/actual differences). | status: done | location: src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallUrl.kt
- 2025-12-22 | @sanyanime | Desktop: try native webview (Tauri-like); Windows ok, Linux webkitgtk crash -> fallback to browser, Mac unknown. | status: doing | location: src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.desktop.kt
- 2025-12-22 | @alexanderpotemkin | Finish calls by Friday for defense; at least one working feature to present. | status: doing | location: src/**/telecryptModules/call/**
- 2025-12-22 | @sonicsupergedgehog | Ask @sanyanime to build + test room "test" and verify call join; report if link works. | status: todo | location: src/**/telecryptModules/call/**
- 2025-12-22 | @sanyanime | Pushed small CI fix (5 lines); rebase feature/element-call before sharing changes. | status: todo | location: repo branch feature/element-call
