# TeleCrypt Messenger

TeleCrypt Messenger is a Matrix client for Android, desktop, web, and iOS. It is a branded
Kotlin Multiplatform fork of Tammy and uses Trixnity for Matrix protocol support.

## Build

Requirements:

- JDK 21.
- Android SDK for Android targets.
- Xcode for iOS targets.

Common commands:

```bash
./gradlew test
./gradlew bundleRelease assembleRelease
./gradlew createReleaseDistributable packageReleasePlatformZip
./gradlew webBrowserDevelopmentRun
```

`branding/branding.json` is the public branding source. The scripts in `tools/` reapply that
branding after upstream updates, synchronize the upstream fork, and register the `telecrypt://`
URL protocol on Windows.

Do not commit signing keys, provisioning profiles, service-account files, Firebase configuration,
or release credentials.

## License

See [LICENSE](LICENSE). This fork retains the upstream AGPL-3.0 license requirements.
