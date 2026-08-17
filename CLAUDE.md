# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Single-module Android app (Kotlin + Jetpack Compose): a real-time family location radar with
OpenStreetMap map, background GPS tracking, client-side geofencing, group management, chat with
media, and SOS alerts — all on Firebase. Originated from Google AI Studio (see `metadata.json`,
`README.md`).

- Module: `:app`, namespace `com.example`, applicationId `com.aistudio.familyradar.rkwvpm`
- minSdk 24 / targetSdk 36 / compileSdk 36.1, Java 11
- Version bump lives in `app/build.gradle.kts` (`versionCode` / `versionName`)

## Build & test

There is **no Gradle wrapper** in this repo (`gradlew` is absent). Use Android Studio, or a
system-installed Gradle matching AGP 9.1.1. Commands below assume `gradle` is on PATH.

```bash
gradle :app:assembleDebug
```

```bash
gradle :app:testDebugUnitTest
```

Single test class / method:

```bash
gradle :app:testDebugUnitTest --tests "com.example.ExampleRobolectricTest"
```

Roborazzi screenshot tests — record new goldens (writes `app/src/test/screenshots/`):

```bash
gradle :app:testDebugUnitTest -Proborazzi.test.record=true
```

Instrumented tests (device/emulator required):

```bash
gradle :app:connectedDebugAndroidTest
```

Build gotchas:

- `debug` build type is signed with `debugConfig`, which expects `debug.keystore` at the repo root —
  that file is gitignored. If it's missing, either drop it in or remove
  `signingConfig = signingConfigs.getByName("debugConfig")` from `app/build.gradle.kts` (the README
  tells users to do exactly this).
- `release` signing reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` from the environment.
- Secrets come from `.env` (gitignored) with `.env.example` as fallback, via the Secrets Gradle
  Plugin — **not** `local.properties`. `GEMINI_API_KEY` is declared there but no code currently
  calls Gemini (the `firebase-ai` dependency is unused).
- Gradle configuration cache and parallel builds are on; Kotlin compiles in-process
  (`gradle.properties`).

## Architecture

### No ViewModels, no nav graph — one repository singleton

`FirebaseRepository` (`app/src/main/java/com/example/repository/FirebaseRepository.kt`, ~2100 lines)
is the entire application layer: auth, Firestore listeners, FCM token sync, location upload,
geofence evaluation, notification posting, image compression, and settings persistence. It is a
context-scoped singleton (`FirebaseRepository.getInstance(context)`) exposing `MutableStateFlow`
state (`currentUserState`, `userGroupsState`, `currentGroupLocations`, `currentGroupMembers`,
`currentGroupMessages`, `currentGroupPlaces`, `currentGroupSnapshots`, `activeGeofenceAlerts`, …).

Composables collect those flows directly with `collectAsState()`. There are no ViewModels. Room,
Retrofit/Moshi/OkHttp are declared dependencies but there is **no local database and no REST layer** —
don't assume one exists.

Navigation is a `AppScreen` enum (`AUTH` / `GROUP_SELECT` / `MAIN_RADAR`) plus a `Crossfade` in
[MainActivity.kt](app/src/main/java/com/example/MainActivity.kt); a `LaunchedEffect` reconciles the
screen against auth state, group membership, and any pending deep link. `navigation-compose` is a
dependency but unused.

### Firestore schema

```
users/{uid}
groups/{groupId}
  ├─ members/{uid}     role owner|admin|member, status ACTIVE|PENDING, isTrackingActive, batteryLevel
  ├─ locations/{uid}   last known position, battery, currentPlaceName
  ├─ places/{id}       saved geofences (lat/lon/radiusMeters/category)
  ├─ messages/{id}     chat
  ├─ events/{id}       geofence_entry|geofence_exit|sos_alert|join_request|low_battery
  └─ snapshots/{id}    photo pins dropped on the map
```

Group membership uses a join code (`generateJoinCode()`) plus optional owner approval
(`GroupData.requiresApproval`, member `status = PENDING` → `approveJoinRequest` / `rejectJoinRequest`).

### Notifications: two parallel paths that must stay in sync

1. **Client-side.** `listenToGroupData()` attaches snapshot listeners on `messages` and `events`;
   each client turns newly added docs into local notifications via
   `FirebaseRepository.showLocalNotification()`, filtering out its own `senderId`. Listeners are
   scoped with `whereGreaterThan("timestamp", joinTime)` and `lastObservedEventTimestamp` /
   `lastObservedMessageTimestamp` so historical docs don't fire as fresh alerts.
2. **Server-side FCM.** `FamilyRadarMessagingService` renders pushes from the data payload.

Both paths share one contract: a `type` (`chat_message`, `sos_alert`, `geofence_entry`,
`geofence_exit`, `join_request`, `low_battery`) that maps to a `destination`
(`CHAT` / `ALERT` / `MAP` / `MEMBERS`). That mapping is duplicated in
`FamilyRadarMessagingService.sendPushNotification()` and `MainActivity.handleIntent()`, which feeds
`repository.setDeepLinkTarget(...)`. Adding a notification type means touching all of these.

### Listener lifecycle

`listenToGroupData(groupId)` opens six group listeners plus per-member status listeners.
`cleanupGroupListeners()` and `cleanupUserRealtimeListeners()` must run on group switch and sign-out —
leaking these produces duplicate notifications and stale state.

### Location tracking

Two independent producers, one sink:

- `LocationTrackingService` — foreground service with an ongoing notification, `START_STICKY`,
  controlled by `isBackgroundTrackingEnabled`; driven by `ACTION_START` / `ACTION_STOP` /
  `ACTION_UPDATE_INTERVAL` intents through its companion `start/stop/updateInterval` helpers.
- `FirebaseRepository.startSilentLocationTracking()` — in-app FusedLocation updates with no
  notification.

Both call `repository.updateLocation()`, which is the single enforcement point for global ghost mode,
per-group `isTrackingActive`, place matching, and the Firestore write. Put any new gating logic there
rather than in the producers.

### Geofencing is hand-rolled

`GeofenceHelper` does plain distance-vs-radius math on `SavedPlace`. The Play Services **Geofencing
API is not used** — only the moving user's own device detects entry/exit (`checkGeofenceAlert`) and
writes an `events` doc; every other client learns about it through the events listener.

### Images are Base64 inside Firestore

Firebase Storage is deliberately not used (the dependency is commented out). Avatars, chat images and
place snapshots are downscaled to ≤1280px, JPEG q85, and stored as Base64 strings in Firestore
documents (`ImageUtils`, `repository.compressImageToBase64`). Firestore's 1 MB document limit is the
real constraint on image size. `ChatMessage.getImageSource()` wraps the string into a `data:` URL for
Coil. Camera capture goes through the `FileProvider` authority `${applicationId}.fileprovider`
(`ImageUtils.createTempImageUri`).

### Map

osmdroid (OpenStreetMap), not Google Maps. `OsmMapView` is an `AndroidView` wrapper; member, place
and snapshot markers are generated as `Canvas` drawables at runtime
(`createMemberMarkerDrawable` / `createPlaceMarkerDrawable` / `createSnapshotMarkerDrawable`), and
nearby snapshots are grouped by `clusterSnapshots(threshold 30m)`.
`FamilyRadarApplication` configures the osmdroid user agent and cache directories — tiles fail to load
without it.

### Persisted settings (SharedPreferences, no DataStore)

- `family_radar_settings_prefs` — `tracking_freq_sec`, `bg_tracking_enabled`, `global_ghost_mode`
- `family_radar_theme_prefs` — via the `ThemePreferences` object, which exposes a `StateFlow` and
  **must** be `init()`-ed in `MainActivity.onCreate` before the theme is read
- `fcm_prefs` — cached FCM token
- `osmdroid` — tile cache config

## Conventions

- **UI copy is Italian and hardcoded in Composables.** `strings.xml` holds only `app_name`. Match the
  surrounding language when adding UI text.
- **Defensive-degradation style.** `FirebaseAuth` / `FirebaseFirestore` are nullable and every
  Firebase/Android call is wrapped in `try/catch` that logs and continues. Combined with
  `googleServices.missing.passthrough=true` and `MissingGoogleServicesStrategy.WARN`, this is what lets
  the app boot — and Robolectric tests render the full tree — without a real `google-services.json`.
  Keep new Firebase calls equally tolerant.
- Firestore writes use explicit `hashMapOf(...)` field maps rather than object serialization; model
  classes give every field a default so `toObject`-style reads and Firestore's no-arg requirement work.
- Unit tests run under Robolectric with `@Config(sdk = [36])` and `isIncludeAndroidResources = true`.
  `GreetingScreenshotTest` renders the whole `FamilyRadarApp` tree into
  `app/src/test/screenshots/greeting.png`.
- `app/build.gradle.kts` keeps unused dependencies commented out rather than deleted — follow that if
  you drop one.
