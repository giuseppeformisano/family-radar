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

Inside `MAIN_RADAR` there is a second, independent level of navigation: `MainRadarScreen` is a
`BottomSheetScaffold` whose *content* is the full-screen osmdroid map and whose *sheet* holds four
panels (`RadarPanel`: MEMBERS / CHAT / PLACES / SETTINGS) switched by a pill selector. There is no
bottom navigation bar — the sheet peeks at `Sizes.sheetPeek` and expands over the map. Deep links
select a panel and expand or collapse the sheet accordingly.

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

**Group switching is fragile — three rules.** The "switch group" button used to bounce the user
straight back in, for two compounding reasons, both now fixed and easy to reintroduce:

1. `users/{uid}.lastApprovedGroupId` is a **one-shot** signal. The user-doc listener must prefer
   `currentGroupId` and fall back to `lastApprovedGroupId` only when nothing is selected; `selectGroup`
   nulls it once consumed. The old order (`lastApproved ?: currentGroupId`) meant the last approved
   group won forever and no other group could ever be selected.
2. `selectGroup` / `clearCurrentGroupSelection` must **persist to Firestore**, not just mutate the
   StateFlow. The `users/{uid}` doc re-emits constantly (FCM token, `lastUpdated`), and each emit
   re-derives the active group from the stored fields.
3. `repository.isChoosingGroup` is true while the user sits on `GroupSelectScreen`;
   `MainActivity` must honour it before its "auto-select the first active group" branch, which would
   otherwise re-enter the group that was just left. `groupIdDismissedByUser` does the same job at the
   listener level — but only for the group just left, so a *newly approved* group still auto-enters.

`selectGroup` also calls `cleanupGroupListeners()` before `listenToGroupData()`; skipping it leaves two
groups' listeners attached at once (duplicate notifications, mixed member lists).

### Notifications: two parallel paths that must stay in sync

1. **Client-side.** `listenToGroupData()` attaches snapshot listeners on `messages` and `events`;
   each client turns newly added docs into local notifications via
   `FirebaseRepository.showLocalNotification()`, filtering out its own `senderId`. Listeners are
   scoped with `whereGreaterThan("timestamp", joinTime)` and `lastObservedEventTimestamp` /
   `lastObservedMessageTimestamp` so historical docs don't fire as fresh alerts.
2. **Server-side FCM.** `FamilyRadarMessagingService` renders pushes from the data payload.

Both paths share one contract: a `type` (`chat_message`, `sos_alert`, `geofence_entry`,
`geofence_exit`, `join_request`, `low_battery`) that maps to a `destination`
(`CHAT` / `ALERT` / `MAP` / `MEMBERS`). The mapping lives in
`FamilyRadarMessagingService.destinationFor()` and `MainActivity.handleIntent()`, which feeds
`repository.setDeepLinkTarget(...)`. Adding a notification type means touching both.

**Both paths build their notifications through `notification/RadarNotifier.kt`** — that is the point
of the file. They used to construct notifications independently with different channels and ID
schemes, so chat messages from FCM and from the Firestore listener could not be grouped together or
cancelled as a set. Three channels, all `IMPORTANCE_HIGH`: `CHANNEL_CHAT` (per-group `InboxStyle`
summary + children via `setGroup`/`setGroupSummary`), `CHANNEL_PLACES` (geofence, `setFullScreenIntent`
for a guaranteed heads-up banner) and `CHANNEL_SOS`.

`RadarNotifier` keeps an in-memory map of outstanding chat notification IDs per group so
`clearChatNotifications(groupId)` can dismiss them — called from `repository.markChatRead()` when the
Chat panel opens. Unread counting is timestamp-based (`chat_last_read_<groupId>` in
`family_radar_settings_prefs`), exposed as `unreadChatCount`; the badge previously showed
`messages.size`, i.e. the whole history rather than unread.

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

`updateLocation` also holds the **jitter / anti-drift filter** (`evaluateLocationGate`). A fix is
written to Firestore only if: it's the first one, the heartbeat is due
(`HEARTBEAT_INTERVAL_MS`, 5 min — keeps online status and battery fresh while stationary), speed
exceeds `MOVING_SPEED_THRESHOLD_MS` (1.5 m/s), or displacement exceeds `MIN_DISPLACEMENT_METERS`
(18 m) *and* is larger than the fix's own `accuracy` radius. Two invariants:

- The geofence evaluation runs on **every** fix, before the gate — a place entry must not be lost
  just because the movement was small.
- Don't add a second distance filter in `LocationTrackingService`; it deliberately requests fixes
  with `setMinUpdateDistanceMeters(0f)` so the heartbeat still fires when the device is still.

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

Two things about `OsmMapView` that are easy to get wrong:

- **Dark mode is a colour matrix on the tile overlay**, applied in the `update` block (not `factory`)
  so it can be added and removed when the theme flips. `isDark` reads `RadarTheme.palette.isDark`.
- **Re-centring on the same coordinates needs `focusToken`.** The recenter `LaunchedEffect` is keyed
  on `(targetFocusPoint, focusToken)`; a `Pair` with unchanged coordinates is structurally equal, so
  without bumping the token, pressing "centre on me" twice would do nothing the second time.
  `MainRadarScreen.focusMapOn()` is the only thing that should set both.

Because the map is an `AndroidView`, its pixels are drawn by the Android view system and are **not**
in Compose's graphics layer. Backdrop-blur libraries (haze and friends) cannot capture it — that is
why overlays use `GlassSurface` (translucent tint + border + scrim gradient) rather than real blur.

### Design system

`ui/theme/` is a token layer, and screens are expected to consume it rather than hardcode values:

- `Color.kt` — raw palette (private) → semantic light/dark roles + `RadarSemantic` (presence,
  battery, place categories — things Material 3 has no slot for) + `RadarGradients`.
- `Dimens.kt` — `Spacing` / `Radius` / `Elevation` / `Sizes` scales, plus `RadarShapes`.
- `Type.kt` — `RadarTypography`, tighter and heavier than the M3 default, plus `MetricTextStyle`
  and `BadgeTextStyle`.
- `Theme.kt` — `MyApplicationTheme(themeMode, dynamicColor)`. Material You dynamic colour is **on by
  default** on API 31+, falling back to the brand palette. Non-Material tokens travel through
  `LocalRadarPalette`, read as `RadarTheme.palette`.

Shared components live in `ui/components/Foundation.kt` (`GlassSurface`, `RadarAvatar`, `PillChip`,
`PresenceDot`, `BatteryBadge`, `EmptyState`, `InfoBanner`, `SectionHeader`, `SheetHandle`…),
with `Skeletons.kt` for shimmer loading states and `LottieBox.kt` for animations.

**Lottie assets are optional by design.** `LottieBox` resolves `res/raw/<name>` via
`resources.getIdentifier` at runtime instead of a compile-time `R.raw.*` reference, and renders a
Compose fallback when the file is absent. So the project builds with no animation files at all, and
each animation lights up the moment its `.json` is dropped in. Currently referenced names:
`empty_members`, `empty_chat`, `empty_places`, `empty_groups`. Android resource names must be
lowercase alphanumeric + underscore — never put a `README.md` or any dotted filename in `res/raw`,
it breaks the resource compiler.

`RadarPulseAnimation` is deliberately hand-drawn Compose rather than Lottie: it has to take the theme
colour (including dynamic Material You), which a baked `.json` cannot.

### Persisted settings (SharedPreferences, no DataStore)

- `family_radar_settings_prefs` — `tracking_freq_sec`, `bg_tracking_enabled`, `global_ghost_mode`
- `family_radar_theme_prefs` — via the `ThemePreferences` object, which exposes a `StateFlow` and
  **must** be `init()`-ed in `MainActivity.onCreate` before the theme is read
- `family_radar_language_prefs` — via `LanguagePreferences`, same shape as `ThemePreferences`
- `fcm_prefs` — cached FCM token
- `osmdroid` — tile cache config

### Localisation (it / en) — migration in progress

Italian is the default locale (`res/values/`), English lives in `res/values-en/`. **The extraction is
only partly done**: notification channels, all notification and updater copy, the foreground-service
notification and the Appearance card read from resources; the rest of the UI is still hardcoded
Italian. When you touch a screen, extract the strings you pass through rather than adding new literals.

`LanguagePreferences` (`ui/theme/`) holds `SYSTEM / ITALIAN / ENGLISH`. The locale is applied in
`MainActivity.attachBaseContext`, so it covers the whole Compose tree without providing `LocalContext`
by hand — which is also why changing language calls `recreate()`: `attachBaseContext` runs once per
Activity instance.

Three rules that are easy to break:

1. **App and notifications must share one language.** Notifications are built outside Compose from the
   application or a Service context, which knows nothing about the in-app choice. Every such call site
   (`RadarNotifier`, `FamilyRadarMessagingService`, `LocationTrackingService`, `AppUpdater`) must read
   strings through `LanguagePreferences.localizedContext(context)`.
2. **`LanguagePreferences.readStored()` reads SharedPreferences synchronously, not the StateFlow.** An
   FCM push can land on a freshly created process where `init()` has not run yet; reading the flow
   would yield `SYSTEM` and send the notification in the wrong language exactly in the common
   app-closed case.
3. **Never put an apostrophe in a string resource.** aapt2 requires `\'` and reports a bare one as
   `invalid unicode escape sequence`, which points nowhere near the cause. Worse, XML formatters strip
   the backslash again, so the build breaks a second time. Reword instead: `Consenti a X di installare`
   rather than `Consenti l'installazione`. Many Italian phrases need this (`l'utente`, `dell'app`,
   `un'immagine`).

Placeholders are positional (`%1$s`, `%2$s`) because word order differs between the two languages. Keys
must exist in both files — a missing one silently falls back to Italian. `app_name` is deliberately
untranslated.

Note the limit that localisation does not fix: system chat messages and `events` docs
(`"Marco è arrivato a Casa"`) are written to Firestore **already rendered**, in the language of the
device that generated them. Localising those properly means storing `type` + parameters and composing
the sentence on the reading client.

## Conventions

- **UI copy is Italian by default.** See the localisation section above before adding text: prefer a
  string resource over a literal, and match the surrounding language in the parts not yet extracted.
- **Pull spacing, radii and sizes from the token objects**, not literal `dp` values — `Spacing.lg`,
  not `16.dp`. Colours come from `MaterialTheme.colorScheme` or `RadarSemantic`; a raw `Color(0xFF…)`
  in a screen is a bug, because it will not survive the light/dark or dynamic-colour switch.
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
