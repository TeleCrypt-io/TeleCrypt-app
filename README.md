# Tammy

White label messenger based on [Trixnity Messenger](https://gitlab.com/connect2x/trixnity-messenger/trixnity-messenger). Please consult the Readme there for additional information.

## Run locally
If you run the messenger from the IDE or command line, the 
### Desktop
`./gradlew run`

### Android
In Android Studio or IntelliJ, choose the Android configuration and run on an emulated  or your physical device.

### Web
Please **note**: web is still experimental and is missing some features that are present in the other versions. We are working on it.

`./gradlew webBrowserDevelopmentRun`

To use the local version, you need to disable cross-origin checking in your browser. On Mac, you can use the following: `open -a Google\ Chrome --args --disable-web-security --user-data-dir="tmp"`