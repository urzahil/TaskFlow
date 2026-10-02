# TaskFlow

TaskFlow is a native Android task-management application built around a local Room database. It provides a daily task schedule, monthly calendar views, recurring tasks, categories, completion tracking, JSON backup/import, and optional Google Drive backup and restore.

The repository is currently version **2.8.15** (version code **21**).

## Features

- **Daily task management**
  - Create, edit, complete, and delete tasks.
  - Task descriptions, priorities, categories, colours, and dates.
  - Tasks can be scheduled for today or future dates.
- **Recurring tasks**
  - Repeat at a configurable day interval.
  - Optionally restrict occurrences to selected days of the week.
  - Optional end dates.
- **Calendar and list views**
  - Daily schedule.
  - Monthly calendar overview.
  - Configurable task-list visibility and starting view.
- **Categories**
  - Built-in categories such as General, Work, Personal, Health, Fitness, Home, and Study.
  - Custom category names, colours, and icons.
  - Deleting a category atomically reassigns its tasks to General.
- **Automatic daily rollover**
  - Runs when the application enters the foreground.
  - Completed past non-recurring tasks are cleaned up.
  - Uncompleted past tasks are moved forward.
  - Recurring task occurrences are advanced without treating future completions as past completions.
  - Rollover is serialized to prevent concurrent cleanup.
- **Local backup and restore**
  - Export task data to JSON.
  - Import task data from JSON.
  - Restore operations replace the local task/category dataset as a single database transaction.
- **Google Drive backup and restore**
  - Optional Google sign-in.
  - Backup is stored as `taskflow_backup.json` in Drive's application data space.
  - Automatic backups can be enabled or disabled.
  - Pending automatic backups are persisted so a failed backup can be retried after the app starts or returns to the foreground.
  - Backup and restore operations are serialized to avoid concurrent Drive operations.
- **Appearance and preferences**
  - Light/dark theme support.
  - Optional Android dynamic colours.
  - Configurable calendar/list display preferences.

## Technology stack

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | Activity + ViewModel + Repository |
| Persistence | Room |
| Async work | Kotlin Coroutines + Flow |
| Networking | Retrofit / OkHttp |
| JSON | Moshi |
| Cloud backup | Google Drive API |
| Authentication | Google Play Services Sign-In |
| Testing | JUnit, AndroidX Test, Robolectric, Roborazzi |
| Build | Gradle + Android Gradle Plugin |
| CI | GitHub Actions |

The project uses a Gradle version catalog in `gradle/libs.versions.toml` to keep dependency versions centralized.

## Project structure

The main application code lives under `app/src/main/java/com/example`.

```text
TaskFlow/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── db/              # Room database and DAO
│   │   │   │   │   ├── drive/            # Google Drive integration
│   │   │   │   │   ├── model/            # Database/domain models
│   │   │   │   │   └── repository/       # Data access abstraction
│   │   │   │   └── ui/
│   │   │   │       ├── MainScreen.kt
│   │   │   │       ├── TaskViewModel.kt
│   │   │   │       ├── model/             # UI state/models
│   │   │   │       ├── settings/          # Settings UI
│   │   │   │       └── theme/             # Compose theme
│   │   │   ├── res/                       # Android resources
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                          # JVM/unit and Robolectric tests
│   │   └── androidTest/                   # Instrumented Android tests
│   └── build.gradle.kts
├── .github/workflows/
│   ├── build-apk.yml                      # Debug APK on main
│   ├── release-apk.yml                    # Signed releases/tags
│   └── reliability-test-build.yml         # Reliability branch/PR validation
├── gradle/
│   └── libs.versions.toml
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── README.md
```

## Application architecture

```text
Compose UI
    │
    ▼
TaskViewModel
    │
    ▼
TaskRepository
    │
    ├── Room / TaskDao ──► local SQLite database
    │
    └── GoogleDriveBackupManager
             │
             ▼
       GoogleDriveService ──► Google Drive API
```

### UI

Jetpack Compose screens render state exposed by `TaskViewModel`. User actions are passed back to the ViewModel rather than manipulating the database directly.

### ViewModel

`TaskViewModel` coordinates task creation/editing, completion changes, filtering and view selection, daily rollover, JSON export/import, Google Drive backup/restore, backup retry handling, and user preferences.

Coroutine work is scoped to the ViewModel lifecycle.

### Repository

`TaskRepository` provides the application-facing data layer. Database mutations that need to be atomic are implemented by transactional DAO methods rather than as independent operations.

This is particularly important for:

- deleting a task together with its completion records;
- renaming/updating categories and updating their tasks;
- deleting a category and reassigning its tasks;
- restoring a complete backup;
- daily rollover cleanup.

### Room database

The Room database is the authoritative local source of task data.

The current database name is:

```text
taskflow_database
```

Android backup configuration references this database name so the database participates correctly in Android backup/data-extraction mechanisms.

## Task rollover

TaskFlow performs daily maintenance when the app enters the foreground. The rollover logic is based on the current date rather than assuming that the app is opened every day.

For non-recurring tasks:

1. Past completed tasks are removed.
2. Their associated completion records are removed atomically.
3. Past uncompleted tasks are moved to the current date.

For recurring tasks:

1. Old occurrences are accounted for.
2. The next valid occurrence is calculated.
3. Day-of-week restrictions and optional end dates are respected.
4. Expired recurring tasks are removed.
5. Future occurrences remain future occurrences.

A completion recorded for a future date is not allowed to make a task look completed for an earlier date, and future completion records are not removed merely because rollover cleanup is running.

Rollover execution is protected by a coroutine `Mutex`, preventing duplicate foreground/date-change events from performing the same cleanup concurrently.

## Backup and restore

### JSON backup

TaskFlow can serialize tasks, completion records, and categories into JSON. JSON export/import is local and does not require Google sign-in.

Importing a backup restores the complete dataset through a Room transaction, avoiding a partially restored database if the operation fails midway.

### Google Drive backup

Google Drive integration is implemented in:

- `GoogleDriveBackupManager.kt`
- `GoogleDriveService.kt`
- `GoogleOAuthHelper.kt`

The backup file is:

```text
taskflow_backup.json
```

It is stored in Google Drive's `appDataFolder`, making it application-specific rather than a normal user-visible Drive document.

The app requests:

- `drive.file`
- `drive.appdata`

Automatic backup uses a durable pending-backup flag. If an automatic backup cannot complete, the pending state survives the current ViewModel instance and can be retried on a later startup/foreground event. A successful backup clears the pending state.

Backup and restore share a synchronization lock so they cannot race each other.

### Fresh-install restore

On a fresh installation, TaskFlow can check the signed-in Google account for an existing Drive backup. If local tasks already exist, the app does not automatically overwrite them.

## Google Sign-In configuration

Google Drive functionality requires an Android OAuth configuration matching the application's package and signing certificate.

Application ID:

```text
com.aistudio.taskflow.plvqrm
```

A local build can still be used without Google Drive configuration. Local task management and JSON export/import do not require Drive sign-in.

For a Google Drive-enabled production build, the appropriate OAuth client and Firebase/Google Services configuration must be available. The project uses the Google Services Gradle plugin and the Secrets Gradle Plugin.

Do not commit private credentials, signing keys, or local secret files.

## Requirements

The project currently targets:

- Android API **36.1** for compilation.
- Android **24** as the minimum supported API.
- Android API **36** as the target SDK.
- Gradle **9.3.1** through the checked-in Gradle wrapper.
- JDK **21** for the documented local development environment.

The Gradle wrapper verifies its configured distribution checksum.

### Android SDK

Set:

```text
ANDROID_HOME=/path/to/Android/Sdk
```

or create an untracked `local.properties` containing:

```properties
sdk.dir=/path/to/Android/Sdk
```

Do not commit `local.properties`.

## Building locally

Make the wrapper executable if necessary:

```sh
chmod +x ./gradlew
```

Build the debug APK:

```sh
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Build the release variant:

```sh
./gradlew assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

## Running tests and checks

Complete JVM test suite:

```sh
./gradlew testDebugUnitTest
```

Android lint:

```sh
./gradlew lintDebug
```

Combined validation:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

### Targeted reliability tests

Focused regression coverage includes:

```text
app/src/test/java/com/example/ProductionDatabaseAndRepositoryTest.kt
app/src/test/java/com/example/TaskCleanupAndRolloverTest.kt
```

Run the production database/repository suite with:

```sh
./gradlew testDebugUnitTest \
  --tests com.example.ProductionDatabaseAndRepositoryTest
```

The reliability CI workflow uses this focused suite as its gating regression check.

## Known test-suite limitation

Some Robolectric-based tests depend on an Android SDK/runtime combination that is not available in every CI environment. In the reliability validation environment, these tests have been observed to fail during Robolectric SDK initialization rather than because of application assertions:

- `BackupSerializationTest`
- `ExampleRobolectricTest`
- `GreetingScreenshotTest`

The reliability workflow therefore runs the focused `ProductionDatabaseAndRepositoryTest` suite for its gating regression check while still producing build/test logs as artifacts.

If changing Robolectric configuration, SDK availability, or screenshot infrastructure, run the full suite locally and investigate these tests separately.

## APK signing

### Debug builds

Android's normal debug signing is used for debug builds.

If a `debug.keystore` exists at the repository root, the project uses it for the debug signing configuration. Otherwise Android/Gradle can generate/use the normal local debug key.

Android only permits an installed application to be updated when the new APK is signed by a compatible key. A locally generated/debug-signed APK may therefore require uninstalling an existing production-signed TaskFlow installation before it can be installed.

Export important TaskFlow data before uninstalling an incompatible installation.

### Release builds

The release build looks for a release keystore using:

```text
KEYSTORE_PATH
STORE_PASSWORD
KEY_PASSWORD
```

The expected key alias is:

```text
upload
```

If no release keystore is present, the Gradle configuration falls back to the debug signing configuration. This makes local release builds possible without requiring production signing material.

**Do not use a debug-signed APK as a production release.** Production distribution should use the protected release keystore.

Release builds currently have R8/minification disabled.

## GitHub Actions

The repository contains three workflows.

### Debug APK

`.github/workflows/build-apk.yml`

Runs on pushes to `main` and can be started manually. It checks out the repository, installs JDK 17 in the GitHub-hosted environment, builds `assembleDebug`, and uploads the APK as a workflow artifact.

### Production release

`.github/workflows/release-apk.yml`

Runs manually or when a tag beginning with `v` is pushed.

Required GitHub Actions secrets:

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Password for the `upload` key |

The workflow restores the keystore into the runner's temporary directory, builds `assembleRelease`, uploads the APK as an artifact, and creates a GitHub Release for version tags.

Never commit the keystore or passwords.

### Reliability validation

`.github/workflows/reliability-test-build.yml`

This workflow is intended for reliability-hardening work and pull requests targeting `main`.

It:

1. Runs the focused production repository regression tests.
2. Builds the release variant.
3. Uploads test/build logs.
4. Uploads the generated release APK.
5. Fails if the targeted tests or release build fail.

When no production keystore is supplied, its release APK uses the debug-signing fallback. Such an APK is suitable for testing but not production distribution.

## Release process

A typical production release should:

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`.
2. Run relevant unit tests.
3. Run lint.
4. Build and manually test the release APK.
5. Verify the APK is signed with the production release key.
6. Commit the release changes.
7. Create and push a version tag, for example:

```sh
git tag v2.8.16
git push origin v2.8.16
```

8. Let GitHub Actions build the signed release and create the GitHub Release.

Keep version codes monotonically increasing because Android uses `versionCode` to determine whether an APK can update an installed application.

## Data safety and backups

TaskFlow's primary data store is local Room storage. Google Drive backup is optional.

Before uninstalling a differently signed APK, changing development signing keys, or performing invasive database work:

1. Export a JSON backup from TaskFlow.
2. Keep the backup outside the application installation.
3. Reinstall/update the application.
4. Import the JSON backup if necessary.

The application also participates in Android's backup/data-extraction system. The configured backup rules reference the actual Room database name, `taskflow_database`.

## Development guidelines

When modifying persistence behavior:

- Prefer Room transactions for multi-step mutations.
- Keep task and completion changes atomic where they represent one logical operation.
- Do not use future completion records to determine whether a task was completed in the past.
- Preserve future tasks during daily rollover.
- Keep category and task colour state consistent when deleting/reassigning categories.

When modifying coroutine-based operations:

- Preserve coroutine cancellation.
- Do not swallow `CancellationException` inside broad `catch (Exception)` blocks.
- Avoid launching duplicate foreground operations.
- Use synchronization primitives such as `Mutex` where the same stateful operation can be triggered concurrently.

When modifying backup/restore:

- Treat restore as destructive.
- Keep database replacement atomic.
- Serialize backup and restore operations.
- Never persist secrets in source control.

## Contributing

For a normal change:

1. Create a feature/fix branch from `main`.
2. Make the smallest coherent change.
3. Add or update regression tests for behavior that can break silently.
4. Run relevant tests and lint locally.
5. Build an APK when the change affects user-visible behavior.
6. Open a pull request against `main`.
7. Do not merge until CI and review requirements are satisfied.

For reliability-sensitive changes, prefer focused tests around the affected repository/ViewModel behavior in addition to the general suite.

## Troubleshooting

### Gradle cannot find the Android SDK

Set `ANDROID_HOME` or add `sdk.dir` to an untracked `local.properties`.

### A new APK will not install over the existing application

The APK is probably signed with a different key. Export your data, uninstall the existing application, and install the test APK. For production updates, always use the same production signing key.

### Google Drive sign-in does not work

Check:

- Google OAuth client configuration.
- Application package name.
- Signing certificate registered with Google.
- Google Services configuration.
- Requested Drive scopes.

Debug/local builds and production builds can have different signing certificates, so OAuth configuration must account for the certificate used by the build being tested.

### Automatic backup appears not to run

Check that:

- Google Drive is signed in.
- Automatic backup is enabled.
- The device has network connectivity.
- The Drive account still grants the required permissions.

A failed automatic backup is retained as a pending backup and can be retried when the application starts or returns to the foreground.

### Full Robolectric tests fail in CI

Distinguish application assertion failures from Robolectric SDK initialization failures. If the failure occurs before a test reaches its assertions and references `DefaultSdkProvider`, check the available Android SDK platform and Robolectric configuration before attributing it to application code.

## Current version

```text
Version name: 2.8.15
Version code: 21
Application ID: com.aistudio.taskflow.plvqrm
Minimum SDK: 24
Target SDK: 36
Compile SDK: 36.1
```

## License

No explicit open-source license is currently declared in this repository. Until a license file is added, treat the repository as **all rights reserved** and do not assume that the code may be redistributed or reused under an open-source license.
