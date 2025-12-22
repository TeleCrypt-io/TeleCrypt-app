# SPEC

## Overview
TeleCrypt Messenger is a branded fork of the Tammy Matrix client (Kotlin Multiplatform + Compose).
This repo holds TeleCrypt branding, build automation, and TeleCrypt-specific modules (for example, calls).

## Goals
- Ship a stable Matrix client for Android, iOS, Desktop, and Web with TeleCrypt branding.
- Keep changes minimal and compatible with upstream Tammy/Trixnity.
- Add TeleCrypt-specific features (Element Call integration) without rewriting the stack.

## Non-goals
- Replacing the tech stack or upstream architecture.
- Changing Matrix protocol/E2EE behavior without explicit spec.

## Platforms
- Android (APK/AAB)
- iOS (Xcode archive)
- Desktop (Windows/macOS/Linux)
- Web (Kotlin/JS)

## Architecture
- Kotlin Multiplatform + Compose UI.
- Trixnity/Tammy view models and UI modules.
- Koin for DI.
- Branding via `branding/branding.json` and `tools/brandify.*`.
- Call feature modules live under `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call`.

## UX flows
- Sign-in (including SSO on desktop/web).
- Room list -> timeline view -> room header actions.
- Call button in room header opens Element Call (external browser today).

## Constraints
- Do not repackage secrets in repo.
- Keep branding automation idempotent.
- Maintain compatibility with upstream Tammy structure.

## Security
- No custom crypto changes without explicit requirements.
- Network calls should have timeouts and error handling.
