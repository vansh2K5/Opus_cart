# StudyTimelapse

A calm, native Android study tracker for you and one friend. Put the phone on your desk and press
**Start**. The app times your actual study (pauses excluded), records a battery-conscious
timelapse, keeps your streak, statistics, goals and achievements, and lets you compete with a
friend on a fair weekly leaderboard. Videos never leave the phone unless you share them.

> Forest's calm + Strava's history + a study timelapse camera.

## Status

| Area | State |
|---|---|
| Camera → long-running timelapse → crash-safe storage → playable video | ✅ implemented (MediaCodec + CameraX + camera foreground service) |
| Screen off / power | ✅ Dim mode (works everywhere) + Lock mode with an on-device capability test and a frame watchdog — see [PLATFORM_LIMITS.md](docs/PLATFORM_LIMITS.md) |
| Sessions, pause/resume, crash and reboot recovery | ✅ |
| Stats (day/week/month/all-time), streaks, goals, calendar, heatmap, subjects, records, achievements | ✅ |
| Accounts, one friend (invite codes), leaderboard, friend profile/activity, challenges | ✅ with Firebase (optional) |
| Dark mode, accessibility, reduced motion, share card, CSV/JSON export, storage manager, reminders, delete account/data | ✅ |
| Automated tests | ✅ 28 JVM unit tests (streaks, midnight, time zones, pause/resume, goals, leaderboard fairness, challenges, achievements) + Room instrumented tests |

**How this was verified so far:**

* The pure-Kotlin domain layer compiles and its **28 unit tests pass**.
* The video encoder and segment joiner compile against the real Android 36 SDK (`android.jar`).
* The **whole app** (every source file) type-checks against the real Compose 1.8 UI, foundation,
  Material 3, navigation and lifecycle APIs plus `android.jar`. AndroidX/Firebase libraries that
  are only published on Google's Maven server (CameraX, Room, WorkManager, Media3, DataStore,
  core, Firebase) were checked against hand-written signature stubs.
* Not yet done: a real Gradle/AGP build (Room's KSP code generation, resources/aapt2, R8) and
  running on a phone. CI (`.github/workflows/android.yml`) or Android Studio does the former. For
  the camera and power behaviour, work through [TESTING_CHECKLIST.md](docs/TESTING_CHECKLIST.md).

## Quick start

```bash
# Android Studio: File → Open → this folder → Run ▶ on a USB-connected phone
# or from a terminal:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

* Full instructions (developer options, USB debugging, release APK/AAB, signing):
  **[docs/BUILD_AND_RUN.md](docs/BUILD_AND_RUN.md)**
* Accounts and friends (Firebase, about 15 minutes, free): **[docs/BACKEND_SETUP.md](docs/BACKEND_SETUP.md)**
* Architecture and data model: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**
* Android camera and screen-off constraints, explained: **[docs/PLATFORM_LIMITS.md](docs/PLATFORM_LIMITS.md)**

Without `app/google-services.json` the app builds and runs in private on-device mode.

## The one-paragraph technical answer

Android does not let an app use the camera invisibly in the background. It **does** allow a
*foreground service of type `camera`*, started while the app is visible, to keep the camera after
you leave the app or lock the screen. StudyTimelapse uses exactly that. Some manufacturers
still stop the camera when the screen turns off, so the app (a) defaults to **Dim mode**: screen on
at minimum brightness with a black OLED-friendly UI, which works everywhere; (b) offers a
30-second **screen-off test** so you know what your phone does; and (c) runs a **watchdog** that
alerts you within about 20 s if frames stop. It never claims to be recording when it isn't. Frames
go straight into the hardware H.264 encoder (one per interval, default 2 s) and are written as
crash-safe 10-minute segments, then losslessly joined. A 2-hour session gives a 2-minute, ~45 MB
video with constant memory use.

## Privacy

* Timelapses, thumbnails and notes stay in private app storage and are excluded from backups.
* Only study statistics sync, and only to your accepted friend (enforced by
  [Firestore rules](firebase/firestore.rules)).
* Permissions: Camera, Notifications, foreground service (camera), wake lock, internet. No
  storage, no microphone, no location.

## Design

Warm, flat and quiet. The structure follows the provided DESIGN.md (flat shadowless 28dp cards,
pill controls, large tightly-tracked semibold type, a single accent used sparingly), adapted to the
brief: warm paper background, **burnt-orange accent #B64400**, warm charcoal dark mode, and Inter
as the SF Pro substitute. Study mode is pure black with a dim warm timer.

## Licence notes

Inter font: SIL Open Font License (see `docs/INTER_LICENSE.txt`).
