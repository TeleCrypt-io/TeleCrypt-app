# Developer Setup

## Prerequisites
- JDK 21 (Gradle toolchain resolves automatically).
- Android SDK for local Android builds (`ANDROID_HOME` or `local.properties`).
- Node/Yarn are handled by the Kotlin JS plugin.
- Ruby + Bundler only if running Fastlane locally.

## Quick Start (Desktop)
From repo root:
```
./run.ps1
```
This builds and runs the desktop app for your current OS.

## Common Build Commands
- Desktop (current OS):
  ```
  ./gradlew createReleaseDistributable packageReleasePlatformZip
  ```
- Web dev server:
  ```
  ./gradlew webBrowserDevelopmentRun
  ```
- Android:
  ```
  ./gradlew bundleRelease assembleRelease
  ```
- iOS archive (manual):
  ```
  cd iosApp
  xcodebuild -workspace iosApp.xcworkspace -scheme "Tammy for iOS" -configuration Release -archivePath build/TeleCrypt.xcarchive archive
  ```

## Logs
Desktop logs are written to `./app-data/messenger.log`.
