# RUNBOOK

## Prereqs
- JDK 21 (Gradle toolchain).
- Android SDK for local Android builds (`ANDROID_HOME` or `local.properties`).
- Ruby + Bundler only if running Fastlane locally.

## Branding
Run before builds or after upstream sync:
- `tools/brandify.sh branding/branding.json`
- `tools/brandify.kts branding/branding.json`

## Local builds
- Desktop dev run: `./gradlew run`
- Desktop release artifacts: `./gradlew createReleaseDistributable packageReleasePlatformZip`
- Web dev server: `./gradlew webBrowserDevelopmentRun`
- Web distributable zip: `./gradlew uploadWebZipDistributable`
- Android release: `./gradlew bundleRelease assembleRelease`
- iOS archive (manual):
  `cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme "Tammy for iOS" -configuration Release -archivePath build/TeleCrypt.xcarchive archive`

## Windows helper
- `run.ps1` / `run.bat` runs the latest desktop JAR if present, otherwise `gradlew run`.

## CI
- GitHub Actions workflow: `.github/workflows/ci.yml`.
- Secrets are required for signing and store uploads.

## Troubleshooting
- Clear caches: stop Gradle and remove `.gradle`, `.kotlin`, `.konan`.
- If branding is wrong, rerun `tools/brandify.sh`.
