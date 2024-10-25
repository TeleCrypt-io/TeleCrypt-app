# Tammy

Website: https://tammy.connect2x.de

White label messenger based on [Trixnity Messenger](https://gitlab.com/connect2x/trixnity-messenger/trixnity-messenger).
Please consult the Readme there for additional information.

## Run locally

If you run the messenger from the IDE or command line, the

### Desktop

`./gradlew run`

### Android

In Android Studio or IntelliJ, choose the Android configuration and run on an emulated or your physical device.

### Web

Please **note**: web is still experimental and is missing some features that are present in the other versions. We are
working on it.

`./gradlew webBrowserDevelopmentRun`

## Create release

To create a release, you need to create a version-tag of the form `v1.2.3` in GitLab. The version must be the same as in
`gradle/lib.versions.toml`. This will trigger a pipeline creating all distributions, uploading them into package
registry and linking them in a newly created GitLab release.

## Fastlane

When running locally, you must set `TAMMY_BUILD_FLAVOR` to `PROD` (e.g. by prepending `TAMMY_BUILD_FLAVOR=PROD` to each
command).

### Create screenshots

Create new screenshots for the App.

When using a Mac, you may do first (this needs Android SDK command line tools to be installed):

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/emulator:$HOME/Library/Android/sdk/tools:$HOME/Library/Android/sdk/cmdline-tools/latest/bin/"
```

After that you can start the emulators:

```bash
./fastlane/run_screenshot_emulators.sh
```

And create screenshots:

```bash
TAMMY_BUILD_FLAVOR=PROD fastlane android screenshots
```

After that you can stop the first script and delete the emulators:

```bash
./fastlane/delete_screenshot_emulators.sh
```

## Important environment variables

This does not include default GitLab environment variables that are used.

- WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER
- WINDOWS_CODE_SIGNING_THUMBPRINT
- ANDROID_RELEASE_STORE_FILE_BASE64
- ANDROID_RELEASE_STORE_PASSWORD
- ANDROID_RELEASE_KEY_ALIAS
- ANDROID_RELEASE_KEY_PASSWORD
- APPLE_KEYCHAIN_FILE_BASE64
- APPLE_KEYCHAIN_PASSWORD
- APPLE_TEAM_ID
- APPLE_ID
- APPLE_NOTARIZATION_PASSWORD
- SSH_PASSWORD_APP: Web app deployment
- SSH_PASSWORD_WEBSITE: Website deployment