# TaskFlow

Simple task list with a daily schedule, monthly calendar overview, and custom recurring tasks.

## Build and test

Use JDK 21 and the Android SDK with platform `android-36.1` and build tools `36.0.0`.
Set `ANDROID_HOME` to the SDK directory, or set `sdk.dir` in an untracked `local.properties` file.
The checked-in wrapper downloads Gradle 9.3.1 and verifies its SHA-256 checksum.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The installable APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Android generates a local debug signing key automatically. If `debug.keystore` exists at the
repository root, that key is used instead. An update to an installed APK requires the same
signing key; export a local JSON backup before uninstalling a differently signed version.
Google Drive sign-in also requires the signing certificate to be registered with the app's
Google OAuth configuration. Local task management and JSON export/import work without sign-in.

Release builds use `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` with the `upload`
key alias when a release keystore is available; otherwise the existing debug signing fallback applies.
