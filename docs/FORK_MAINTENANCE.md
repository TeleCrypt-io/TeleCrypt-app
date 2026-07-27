# Fork Maintenance

This document describes how TeleCrypt maintains its fork of upstream Tammy with clean automatic merges. The pattern is inspired by [SchildiChat](https://github.com/SchildiChat/schildichat-android-next/blob/main/readme.md).

## Conventions

### Additive code (preferred)
- New features go in `src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/` or new files placed in upstream packages.
- Use Koin DI overrides instead of patching upstream composables. Example: `CallRoomHeader.kt` lives in package `de.connect2x.messenger.compose.view.room.timeline` and overrides the upstream `RoomHeaderView` binding via `callUiModule.kt:10` (`single<RoomHeaderView> { CallRoomHeader() }`).
- Use Kotlin extension functions to augment upstream classes without modifying them.
- Keep Kotlin source packages as `de.connect2x.tammy.*` — do NOT rename to `io.telecrypt.app.*`. Renaming causes merge conflicts on every source file on every upstream release.

### Minimal upstream modifications
- Never modify upstream files unless absolutely necessary.
- If you must modify an upstream file, keep it to 1 line if possible and mark with a `// TeleCrypt:` comment.
- When wrapping upstream code in a new block (if-else, etc.), preserve the upstream indentation to minimize merge conflicts. Add comments `// TeleCrypt: begin` / `// TeleCrypt: end` if needed.
- Current upstream modifications (audit before each release):
  - `tammyConfiguration.kt:23` — `sendLogsEmailAddress = "support@telecrypt.io"` (override; upstream is `null`)
  - `tammyConfiguration.kt:26` — `pushUrl = "TODO: replace with TeleCrypt Sygnal push gateway URL"` (override; upstream uses Sygnal/UnifiedPush URLs)
  - `src/desktopMain/.../desktop/Main.kt:56,138` — deep-link scheme handling (`telecrypt://`)
  - `src/desktopMain/.../desktop/SsoCallbackServer.kt:73` — deep-link callback URL (`telecrypt://localhost/sso?...`)

### Branding files (managed by scripts — do NOT edit manually)
The following files are rebranded by `tools/post_merge.sh` (and reverted by `tools/pre_merge.sh`). Never edit them by hand — your changes will be lost on the next merge cycle. Both scripts are thin wrappers around `tools/apply_branding.py`, which is data-driven from the two JSON configs below.

Branding configs (edit THESE to change branding values):
- `branding/branding.json` — TeleCrypt branding source-of-truth (app name, identifiers, homepage, scheme, support email, etc.).
- `branding/upstream.json` — upstream Tammy reference values. Edit this if upstream Tammy changes its own branding (verify against `git show upstream/main:<file>` before each sync).

Build / config:
- `build.gradle.kts` — `appName`, `appIdentifier`, `baseName`, `homepage`, `websiteBaseUrl`
- `settings.gradle.kts` — `rootProject.name`
- `tammyConfiguration.kt` — `sendLogsEmailAddress`, `pushUrl`
- `fastlane/Appfile` — `app_identifier`, `package_name`
- `fastlane/Fastfile` — `scheme`, `package_name`

Source code deep-link scheme:
- `src/commonMain/.../telecryptModules/call/CallDeepLink.kt` — `telecrypt://` scheme
- `src/commonTest/.../CallDeepLinkTest.kt` — test assertion
- `src/desktopMain/.../desktop/Main.kt` — `telecrypt://` prefix checks
- `src/desktopMain/.../desktop/SsoCallbackServer.kt` — `telecrypt://localhost/sso?...`
- `src/webMain/resources/index.html` — `<title>`, JS bundle filename

iOS:
- `iosApp/Configuration/Config.xcconfig` — `PRODUCT_NAME`, `PRODUCT_BUNDLE_IDENTIFIER`
- `iosApp/iosApp/Info.plist` — URL-name (bundle id) and URL-scheme strings
- `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/TeleCrypt for iOS.xcscheme` — `BuildableName`, `BlueprintName` (NOTE: the filename was renamed once via `git mv`; the script manages file contents, not the filename)
- `iosApp/iosApp.xcodeproj/project.pbxproj` — target name (if changed)

Android:
- (none — the Android manifest uses `${applicationId}` / `@string/app_name`, both resolved by Gradle's manifest merger from `build.gradle.kts`; the only `de.connect2x.tammy` string in it is the `ElementCallActivity` Kotlin class FQN, which must stay unchanged)

Fastlane:
- `fastlane/metadata/android/{en-US,de-DE}/{title,short_description,full_description,changelogs/default}.txt` — Play Store listing text

Website:
- `website/hugo.yaml` — `baseURL`
- `website/i18n/en-US.yaml`, `website/i18n/de-DE.yaml` — `title`, `company`, download title
- `website/layouts/index.html` — Play Store id, download link names, appinstaller filename, web app URL, Matrix room, source URL
- `website/layouts/_default/single.html` — Matrix room, source URL
- `website/.gitignore` — appinstaller filenames
- `website/content/privacy.{en-US,de-DE}.md` — company, email, address
- `website/content/imprint.{en-US,de-DE}.md` — company, email, address

Windows URL-protocol scripts:
- `tools/telecrypt-url-handler.bat` — comment
- `tools/register-url-protocol.ps1` — protocol name, registry key
- `tools/register-url-protocol.reg` — registry keys

Icons:
- `src/androidMain/res/` — launcher icons (copied from `branding/icons/android/`)
- `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` — iOS icons (copied from `branding/icons/ios/`)
- `src/desktopMain/resources/` — desktop icons (copied from `branding/icons/desktop/`)

## Merge Workflow

### Automatic (recommended)
```bash
tools/upstream_sync.sh main
```
This runs: `pre_merge.sh` (revert branded files) → `git commit` (the reverted, unbranded state — required so the merge/rebase runs on a clean tree) → `git merge` or `git rebase` → `post_merge.sh` (re-apply TeleCrypt branding) → `git commit` → `git push`.

Options:
- `--no-push` — don't push to origin (review changes locally first)
- `--no-auto-commit` — leave post_merge changes uncommitted
- `--no-rebase` — fail if fast-forward isn't possible (don't rebase)

### Manual
```bash
# 1. Revert branded files to upstream state
tools/pre_merge.sh branding/upstream.json

# 2. Merge upstream
git fetch upstream
git merge upstream/main
# (resolve any conflicts in non-branded files)

# 3. Re-apply TeleCrypt branding
tools/post_merge.sh branding/branding.json

# 4. Review and commit
git status
git diff
git add -A
git commit -m "chore: merge upstream + rebrand"
```

### CI
CI runs `tools/post_merge.sh branding/branding.json` before each platform build (6 jobs in `.github/workflows/ci.yml`). CI does NOT run `pre_merge.sh` — it builds from the already-merged `main` branch.

## Known TODOs

| Item | File | Notes |
|------|------|-------|
| Firebase project | `google-services.json` (deleted) | Create a Firebase project for TeleCrypt, download `google-services.json`, commit it before enabling push notifications. The `google-services` Gradle plugin expects this file — if the build fails without it, create a stub with placeholder values + TODO. |
| Sygnal push gateway | `tammyConfiguration.kt:26` `pushUrl` | Currently `TODO: replace with TeleCrypt Sygnal push gateway URL`. Set up a Sygnal instance and update. |
| Error-report email | `tammyConfiguration.kt:23` | Currently `support@telecrypt.io`. Verify this inbox exists. |
| Website legal entity | `website/content/privacy.{en-US,de-DE}.md`, `website/content/imprint.{en-US,de-DE}.md` | Currently has TODO markers for TeleCrypt.IO legal entity name, address, registration. Fill in once the legal entity is established. |
| iOS Xcode target name | `iosApp/iosApp.xcodeproj/project.pbxproj` | Verify the target name was renamed consistently with the scheme rename (`Tammy for iOS` → `TeleCrypt for iOS`). |
| Matrix support room | `website/layouts/index.html`, `website/layouts/_default/single.html` | Currently points to GitHub Discussions. Add a real Matrix room when one exists. |

## Upstream Sync Source-of-Truth

- Upstream Tammy repo: `https://gitlab.com/connect2x/tammy.git` (NOT GitHub — the README example was wrong, fixed)
- Upstream trixnity-messenger: `https://gitlab.com/connect2x/trixnity-messenger`
- Upstream Trixnity: `https://gitlab.com/connect2x/trixnity`
- Upstream values reference: `branding/upstream.json` (verify against upstream before each sync — upstream may have changed its own branding)
