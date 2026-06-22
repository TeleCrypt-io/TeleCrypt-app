# TeleCrypt Messenger

TeleCrypt is our branded fork of the Tammy Matrix client (Kotlin Multiplatform + Compose).  
This repository contains the TeleCrypt flavour, branding automation, and GitLab CI that builds
Android, Desktop, and Web artefacts on every merge request and on `main`.

## Features
- **E2EE audio/video calls** via MatrixRTC (MSC4143/MSC4354) — end-to-end encrypted calls on macOS, Windows, Linux, and Android from a single codebase.
- **Unified crypto context** — Element Call runs as a widget inside TeleCrypt; Olm/Megolm keys stay in the host app (no split-brain).
- **Kotlin Multiplatform** — shared call signaling and UI logic across all platforms via Compose Multiplatform.

## Repository Highlights
- `branding/branding.json` — declarative branding data (app name, Android/iOS identifiers, icon bundle).
- `tools/brandify.sh` / `tools/brandify.kts` — idempotent branding scripts used locally and in CI.
- `.github/workflows/ci.yml` — GitHub Actions workflow orchestrating Android, desktop (Linux/Windows/macOS) and optional iOS builds.
- `TeleCrypt/docs/CALLS.md` — call feature overview and current status.
- `TeleCrypt/docs/ARCHITECTURE.md` — component architecture and call signaling layer.
- `TeleCrypt/docs/CALLS_PROTOCOL.md` — MatrixRTC protocol details (events, state machine, transport).

## Prerequisites
- JDK 21 (toolchain resolved automatically via Gradle).
- Android SDK (for Android builds outside CI). Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
- Node/Yarn are provisioned automatically by the Kotlin JS plugin.
- Ruby + Bundler only when running Fastlane locally (`bundle install`).

## Branding Workflow
1. Edit `branding/branding.json`:
   - `appName` — display name.
   - `androidAppId` — Android `applicationId` (dev builds get `.dev` appended automatically).
   - `iosBundleId` — optional (falls back to the Android id).
   - `iconDir` — root folder with Android/iOS/Desktop icons.
2. Run the branding script (shell or Kotlin variant):
   ```bash
   tools/brandify.sh branding/branding.json
   # or
   tools/brandify.kts branding/branding.json
   ```
3. The script:
   - Updates `build.gradle.kts`, `settings.gradle.kts` (slugified project name), `fastlane/Appfile`,
     iOS config files, Flatpak templates, and `google-services.json`.
   - Keeps Kotlin source packages (`de.connect2x.tammy`) unchanged to avoid massive refactors.
   - Replaces launcher icons across Android/iOS/Desktop from `branding/icons`.

Run the script before every build (CI does this in `before_script`), especially after syncing upstream.

## Build Targets (local)
Use a project-local Gradle cache to keep the workspace self-contained:

| Target | Command | Output |
| --- | --- | --- |
| Android (release AAB/APK) | `GRADLE_USER_HOME=$PWD/.gradle ./gradlew bundleRelease assembleRelease` | `build/outputs/bundle/release/<archiveBase>-release.aab`, `build/outputs/apk/release/<archiveBase>-release.apk` |
| Desktop (current OS) | `GRADLE_USER_HOME=$PWD/.gradle ./gradlew createReleaseDistributable packageReleasePlatformZip` | `build/compose/binaries/main-release/**` |
| Desktop extras | `./gradlew packageReleaseDmg packageReleaseMsix packageReleaseFlatpakBundle packageReleaseFlatpakSources packageReleaseWebZip` | DMG/MSIX/Flatpak/Web bundles under `build/compose/binaries/main-release/**` |
| Web dev | `./gradlew webBrowserDevelopmentRun` | Runs dev server |
| Web distributable | `./gradlew uploadWebZipDistributable` | Upload-ready zip (also used in CI) |
| iOS archive (manual) | `cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme "Tammy for iOS" -configuration Release -archivePath build/TeleCrypt.xcarchive archive` | `iosApp/build/TeleCrypt.xcarchive` |

> Android tasks require a configured SDK when run locally. On shared runners the Docker image already ships with it.

## CI/CD Overview
- **GitHub Actions** (`.github/workflows/ci.yml`) runs on every push to `main`, on each pull request, and via manual `workflow_dispatch`:
  - `Android Release Build` (Ubuntu) — executes `bundleRelease assembleRelease`, publishing AAB/APK artefacts.
  - `Desktop & Web (Linux)` — Compose desktop/web packaging on Linux (`createReleaseDistributable packageReleasePlatformZip packageReleaseWebZip`).
  - `Desktop (Windows)` — Compose packaging on Windows hosts (portable ZIP artefacts; MSIX/signing hooks can be enabled later).
  - `Desktop (macOS)` — DMG/ZIP creation on macOS with automatic signing/notarisation when Apple secrets are present.
  - `iOS Archive` — optional job (enabled by the `ENABLE_IOS_BUILD` secret) running `xcodebuild … archive` on macOS and uploading the `.xcarchive`.
- **Secrets** live in GitHub Actions (Settings → Secrets and variables → Actions). Never commit raw credentials. Keep the same names if you also mirror the pipeline to another CI.
- GitLab CI was retired; GitHub Actions is the canonical automation surface now.

### Required CI Variables
| Variable | Purpose | Format |
| --- | --- | --- |
| `ANDROID_RELEASE_STORE_FILE_BASE64` | Android signing keystore | Base64-encoded `.jks` |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore password | Plain text |
| `ANDROID_RELEASE_KEY_ALIAS` | Alias inside keystore | Plain text |
| `ANDROID_RELEASE_KEY_PASSWORD` | Key password | Plain text |
| `ANDROID_SERVICE_ACCOUNT_JSON_BASE64` | Play Console service account | Base64-encoded JSON |
| `APPLE_KEYCHAIN_FILE_BASE64` | Temporary keychain for macOS/iOS jobs | Base64-encoded keychain |
| `APPLE_KEYCHAIN_PASSWORD` | Password for temporary keychain | Plain text |
| `APPLE_TEAM_ID` | Apple Developer Team ID | Plain text |
| `APPLE_ID` | Apple ID for notarisation/TestFlight | Plain text |
| `APPLE_NOTARIZATION_PASSWORD` | App-specific password (`app-specific-password`) | Plain text |
| `WINDOWS_CODE_SIGNING_THUMBPRINT` | Windows signing cert fingerprint | HEX string |
| `WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER` | Timestamp server URL | URL |
| `SSH_PASSWORD_APP` / `SSH_PASSWORD_WEBSITE` | Legacy SFTP release passwords (only for the old GitLab jobs) | Plain text |
| `ENABLE_IOS_BUILD` | Toggle iOS job (`true` to enable) | `true` / `false` |

Set these in project settings before enabling the respective jobs. For Play/TestFlight uploads, ensure the service
accounts/devices are authorised in their consoles.

### Runner Matrix
- **Linux (`ubuntu-latest`)**: GitHub-hosted runners cover Android/Linux/Web smoke builds out of the box.
- **Windows (`windows-latest`)**: GitHub-hosted runners (free, counted x2 toward the minute quota) yield Windows desktop ZIPs; enable MSIX/signing when certificates are configured.
- **macOS (`macos-latest`)**: GitHub-hosted runners (x10 minute multiplier) produce DMG and optional iOS archives. A self-hosted Mac mini/VM is an alternative when quotas become tight.

## Upstream Sync
`tools/upstream_sync.sh` automates merging Tammy’s `main` into our fork and reapplies branding.

```bash
bash tools/upstream_sync.sh            # sync main branch, auto-push to origin
UPSTREAM_REMOTE=upstream-dev \
UPSTREAM_URL=git@github.com:connect2x/tammy.git \
PUSH_UPDATES=false \
  bash tools/upstream_sync.sh develop  # custom remote/branch without pushing
```

Requirements:
- Clean working tree (script aborts if there are uncommitted changes).
- Upstream remote is added automatically if missing.
- Branding is reapplied via `tools/brandify.sh` when `branding/branding.json` exists.
