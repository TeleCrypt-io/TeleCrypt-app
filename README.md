<p align="right">
  English | <a href="README.ru.md">Русский</a>
</p>

# TeleCrypt Messenger

TeleCrypt is our branded fork of the Tammy Matrix client (Kotlin Multiplatform + Compose).  
This repository contains the TeleCrypt flavour, branding automation, and GitLab CI that builds
Android, Desktop, and Web artefacts on every merge request and on `main`.

## Repository Highlights
- `branding/branding.json` — declarative branding data (app name, Android/iOS identifiers, icon bundle).
- `tools/brandify.sh` / `tools/brandify.kts` — idempotent branding scripts used locally and in CI.
- `.gitlab-ci.yml` — pipeline definition (Linux runners) with manual steps for Windows/macOS/iOS.
- `docs/` — process notes, backlog, build/publish cookbook.

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
   - `iconDir` — root folder with Android/iOS/Desktop icons (see `docs/branding.md` for structure).
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
- **Merge Requests & `main`** trigger Linux runners:
  - `build:android` → runs `uploadAndroidDistributable` (assembles AAB/APK, uploads to GitLab package registry).
  - `build:linux-x64`, `build:web`, `build:prepare-website`, `build:website` keep desktop/web artefacts fresh.
- **Manual jobs** (need dedicated runners + secrets):
  - `build:windows-x64`, `build:macos-x64`, `build:macos-arm64`, `release:*` stages.
  - iOS job template is commented until a macOS runner with 8 GB+ RAM is available.
- **Secrets** are injected through GitLab CI/CD → Variables (masked & protected). Never commit raw credentials.

### Required CI Variables
| Variable | Purpose | Format |
| --- | --- | --- |
| `ANDROID_RELEASE_STORE_FILE_BASE64` | Android signing keystore | Base64-encoded `.jks` |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore password | Plain text |
| `ANDROID_RELEASE_KEY_ALIAS` | Alias inside keystore | Plain text |
| `ANDROID_RELEASE_KEY_PASSWORD` | Key password | Plain text |
| `ANDROID_SERVICE_ACCOUNT_JSON_BASE64` | Play Console service account | Base64-encoded JSON |
| `APPLE_KEYCHAIN_FILE_BASE64` | Temporary keychain for macOS jobs | Base64-encoded keychain |
| `APPLE_KEYCHAIN_PASSWORD` | Password for temporary keychain | Plain text |
| `APPLE_TEAM_ID` | Apple Developer Team ID | Plain text |
| `APPLE_ID` | Apple ID for notarisation/TestFlight | Plain text |
| `APPLE_NOTARIZATION_PASSWORD` | App-specific password (`app-specific-password`) | Plain text |
| `WINDOWS_CODE_SIGNING_THUMBPRINT` | Windows signing cert fingerprint | HEX string |
| `WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER` | Timestamp server URL | URL |
| `SSH_PASSWORD_APP` / `SSH_PASSWORD_WEBSITE` | Credentials for SFTP deployment jobs | Plain text |

Set these in project settings before enabling the respective jobs. For Play/TestFlight uploads, ensure the service
accounts/devices are authorised in their consoles.

### Runner Matrix
- **Linux**: GitLab SaaS shared runners (already active).
- **macOS**: Required for macOS DMG + notarisation and any iOS/TestFlight pipeline. Provide Xcode 16+, Ruby/Bundler, JDK 21.
- **Windows**: Required for MSIX packaging and code signing (needs Windows 11, Windows SDK, `signtool.exe`).

Document runner setup in `docs/` as you provision them (Homebrew packages, environment variables, cache strategy).

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

## Troubleshooting
- **BuildConfig expect/actual mismatch**: ensure branding scripts ran; `build/generatedSrc/**/BuildConfig.kt` should use
  package `de.connect2x.tammy` while exposing the branded identifiers.
- **`kotlinNpmInstall` “Name contains illegal characters”**: rerun `tools/brandify.sh` so that `settings.gradle.kts`
  uses the slugified project name (no spaces).
- **Android tasks complain about missing SDK**: configure `ANDROID_HOME` or create `local.properties` with `sdk.dir=<path>`.
- **Fastlane file paths**: when using app names with spaces, Fastlane expects the same filenames as Gradle produces
  (brandify already rewrites `fastlane/Appfile`); rerun brandify after every app name change.

For more detailed release steps, see `docs/HowToBuildAndPublish.md`.
