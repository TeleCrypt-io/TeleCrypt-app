# TeleCrypt Messenger

TeleCrypt is our branded fork of the Tammy Matrix client (Kotlin Multiplatform + Compose).  
This repository contains the TeleCrypt flavour, branding automation, and GitLab CI that builds
Android, Desktop, and Web artefacts on every merge request and on `main`.

## Repository Highlights
- `branding/branding.json` — declarative branding data (app name, Android/iOS identifiers, icon bundle).
- `tools/brandify.sh` / `tools/brandify.kts` — idempotent branding scripts used locally and in CI.
- `.github/workflows/ci.yml` — GitHub Actions workflow orchestrating Android, desktop (Linux/Windows/macOS) and optional iOS builds.

## Prerequisites
- JDK 21 (toolchain resolved automatically via Gradle).
- Android SDK (for Android builds outside CI). Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
- Node/Yarn are provisioned automatically by the Kotlin JS plugin.
- Ruby + Bundler only when running Fastlane locally (`bundle install`).

### What each platform build includes

- **Android** — Gradle module under `src/androidMain` packaged via the shared `composeApp` target. Release artefacts are signed
  with the keystore from `ANDROID_RELEASE_*` secrets and can be uploaded with Fastlane lanes in `fastlane/Fastfile`.
- **iOS** — Xcode project in `iosApp/iosApp.xcodeproj` that wraps the shared Kotlin code from `src/iosMain`. The CI archive step
  currently fails during `.p12` import: if `APPLE_CERTIFICATE_PASSWORD`/`APPLE_KEYCHAIN_PASSWORD` do not match the certificate,
  the macOS runner cannot see the distribution identity. Local archives on a Mac succeed, so when CI breaks the first action is
  to compare the stored secrets with the password embedded in the `.p12` and the `TeleCrypt iOS App Store` provisioning profile.
- **Desktop (macOS/Linux)** — Compose Desktop targets under `src/desktopMain`; Linux and macOS release DMG/ZIP/Flatpak bundles are
  produced by `createReleaseDistributable` (plus `packageReleasePlatformZip`). When `APPLE_ID`/`APPLE_NOTARIZATION_PASSWORD` are
  present the macOS job enables automatic signing and notarisation.
- **Desktop (Windows)** — Windows artefacts are built via `packageReleasePlatformZip`/`packageReleaseExe`. To ship signed ZIP/EXE files,
  provide the certificate thumbprint and timestamp server URL through repository secrets.
- **Web** — Kotlin/JS target in `src/webMain` bundled through the helper Gradle project `kotlin-js-store` and exposed as a
  zipped distributable.

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
| Desktop extras | `./gradlew packageReleaseDmg packageReleaseFlatpakBundle packageReleaseFlatpakSources packageReleaseWebZip` | DMG/Flatpak/Web bundles under `build/compose/binaries/main-release/**` |
| Web dev | `./gradlew webBrowserDevelopmentRun` | Runs dev server |
| Web distributable | `./gradlew uploadWebZipDistributable` | Upload-ready zip (also used in CI) |
| iOS archive (manual) | `cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme "Tammy for iOS" -configuration Release -archivePath build/TeleCrypt.xcarchive archive` | `iosApp/build/TeleCrypt.xcarchive` |

> Android tasks require a configured SDK when run locally. On shared runners the Docker image already ships with it.

## CI/CD Overview
- **GitHub Actions** (`.github/workflows/ci.yml`) runs on every push to `main`, on each pull request, and via manual `workflow_dispatch`:
  - `Android Release Build` (Ubuntu) — executes `bundleRelease assembleRelease`, publishing AAB/APK artefacts.
  - `Desktop & Web (Linux)` — Compose desktop/web packaging on Linux (`createReleaseDistributable packageReleasePlatformZip packageReleaseWebZip`).
  - `Desktop (Windows)` — Compose packaging on Windows hosts (`packageReleaseExe packageReleasePlatformZip`), add signature to all binaries.
  - `Desktop (macOS)` — DMG/ZIP creation on macOS with automatic signing/notarisation when Apple secrets are present.
  - `iOS Archive` — optional job (enabled by the `ENABLE_IOS_BUILD` secret) running `xcodebuild … archive` on macOS and uploading the `.xcarchive`.
- **Secrets** live in GitHub Actions (Settings → Secrets and variables → Actions). Never commit raw credentials. Keep the same names if you also mirror the pipeline to another CI.
- GitLab CI was retired; GitHub Actions is the canonical automation surface now.

### Required CI Variables
| Variable                                                            | Purpose                                                      | Format                            |
|---------------------------------------------------------------------|--------------------------------------------------------------|-----------------------------------|
| `ANDROID_RELEASE_STORE_FILE_BASE64`                                 | Android signing keystore                                     | Base64-encoded `.jks`             |
| `ANDROID_RELEASE_STORE_PASSWORD`                                    | Keystore password                                            | Plain text                        |
| `ANDROID_RELEASE_KEY_ALIAS`                                         | Alias inside keystore                                        | Plain text                        |
| `ANDROID_RELEASE_KEY_PASSWORD`                                      | Key password                                                 | Plain text                        |
| `ANDROID_SERVICE_ACCOUNT_JSON_BASE64`                               | Play Console service account                                 | Base64-encoded JSON               |
| `APP_STORE_CONNECT_API_KEY`                                         | Fastlane App Store Connect key JSON                          | Base64-encoded JSON               |
| `APP_STORE_CONNECT_KEY_ID`                                          | App Store Connect key identifier                             | Plain text                        |
| `APP_STORE_CONNECT_ISSUER_ID`                                       | App Store Connect issuer ID                                  | UUID                              |
| `APPLE_KEYCHAIN_FILE_BASE64`                                        | Temporary keychain for macOS/iOS jobs                        | Base64-encoded keychain           |
| `APPLE_KEYCHAIN_PASSWORD`                                           | Password for temporary keychain                              | Plain text                        |
| `APPLE_CERTIFICATE_PASSWORD`                                        | Password used to import the `.p12`                           | Plain text                        |
| `IOS_MOBILEPROVISION_BASE64`                                        | Provisioning profile for App Store builds                    | Base64-encoded `.mobileprovision` |
| `APPLE_TEAM_ID`                                                     | Apple Developer Team ID                                      | Plain text                        |
| `APPLE_ID`                                                          | Apple ID for notarisation/TestFlight                         | Plain text                        |
| `APPLE_NOTARIZATION_PASSWORD`                                       | App-specific password (`app-specific-password`)              | Plain text                        |
| `WINDOWS_CODE_SIGNING_THUMBPRINT`                                   | Windows signing cert fingerprint                             | HEX string                        |
| `WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER`                             | Timestamp server URL                                         | URL                               |
| `WINDOWS_CERT_FILE_BASE64`                                          | Windows signing keyfile                                      | Base64-encoded `.pfx`             |
| `WINDOWS_CERT_PASSWORD`                                             | Windows keyfile password                                     | Plaint text                       |
| `SSH_PASSWORD_APP` / `SSH_PASSWORD_WEBSITE`                         | Legacy SFTP release passwords (only for the old GitLab jobs) | Plain text                        |
| `ENABLE_IOS_BUILD`                                                  | Toggle iOS job (`true` to enable)                            | `true` / `false`                  |
| `UPLOAD_TO_STORES`                                                  | Enables Fastlane upload steps                                | `true` / `false`                  |

Set these in project settings before enabling the respective jobs. For Play/TestFlight uploads, ensure the service
accounts/devices are authorised in their consoles.

### Runner Matrix
- **Linux (`ubuntu-latest`)**: GitHub-hosted runners cover Android/Linux/Web smoke builds out of the box.
- **Windows (`windows-latest`)**: GitHub-hosted runners (free, counted x2 toward the minute quota) yield Windows desktop ZIPs and .exe;
- **macOS (`macos-latest`)**: GitHub-hosted runners (x10 minute multiplier) produce DMG and optional iOS archives. A self-hosted Mac mini/VM is an alternative when quotas become tight.

## Secrets & Credential Management

- Keep the authoritative copies of signing materials (`.jks`, `.p12`, provisioning profiles, notarisation passwords) in a shared, access-controlled vault (e.g. Yandex Disk folder with restricted access + checksum manifest).
- All CI secrets referenced in this README live under **Settings → Secrets and variables → Actions** in GitHub. When rotating, update both the vault and the repository settings in the same session.
- For iOS, always regenerate the provisioning profile after issuing or renewing the distribution certificate. Import the `.mobileprovision` into Xcode locally to verify it before base64-encoding for CI.
- For Windows, request the organisation’s code-signing certificate and add `WINDOWS_CODE_SIGNING_THUMBPRINT`/`WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER` only after the certificate is installable on the GitHub-hosted runner (or provide a secure ZIP + password for self-hosted runners).

## Upstream Sync
`tools/upstream_sync.sh` is the one-button workflow for pulling Tammy upstream changes and reapplying TeleCrypt branding.

```bash
bash tools/upstream_sync.sh            # default scenario: sync main and push to origin
UPSTREAM_REMOTE=upstream-dev \
UPSTREAM_URL=git@github.com:connect2x/tammy.git \
PUSH_UPDATES=false \
  bash tools/upstream_sync.sh develop  # alternate remote/branch without pushing
```

Key rules:
- Start with a clean working tree.
- If the `upstream` remote is missing the script adds it automatically.
- After syncing the script invokes `tools/brandify.sh` to restore TeleCrypt icons and identifiers.

While running, the script prompts you with three yes/no questions:
1. Commit the brandify result immediately? (default `y`).
2. Push the updates to origin? (default `y`).
3. Allow an automatic `git rebase` when fast-forward is impossible? (default `y`).

Answer with `y` or `n` (pressing Enter keeps the default). When the rebase hits a conflict the script stops at the conflicting
state. Fix the files, run `git add`, then continue with `git rebase --continue`. If you need to roll back, use `git rebase --abort`
and rerun the script—`--no-auto-commit` is handy when you want to review the diff first.
