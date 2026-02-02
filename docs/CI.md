# CI Overview

GitHub Actions lives in `.github/workflows/ci.yml`.

## Jobs
- Android Release Build (AAB/APK)
- Desktop & Web (Linux)
- Desktop (Windows)
- Desktop (macOS)
- iOS Archive (optional, gated by `ENABLE_IOS_BUILD`)

## Secrets (examples)
- Android signing + Play Store service account.
- Apple signing + notarization secrets.
- Windows code signing (optional).

For the full list, see `README.md`.
