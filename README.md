# Tammy

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

## Important environment variables

This does not include default GitLab environment variables that are used.

- MSIX_CODE_SIGNING_TIMESTAMP_SERVER: timestamp server for MSIX signing
- MSIX_CODE_SIGNING_THUMBPRINT: thumbprint of certificate that should be used for MSIX signing
- ANDROID_RELEASE_STORE_FILE
- ANDROID_RELEASE_STORE_FILE_BASE64
- ANDROID_RELEASE_STORE_PASSWORD
- ANDROID_RELEASE_KEY_ALIAS
- ANDROID_RELEASE_KEY_PASSWORD