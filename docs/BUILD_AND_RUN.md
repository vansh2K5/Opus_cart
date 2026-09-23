# Build, install and run

## Option 0: no Android Studio at all (GitHub Actions)

Every push runs [`.github/workflows/android.yml`](../.github/workflows/android.yml), which runs the
unit tests and builds a debug APK. On GitHub go to **Actions → latest "Android build" run →
Artifacts → `StudyTimelapse-debug-apk`**, download it, unzip it and install the `.apk` (see step 6).

To include Firebase in the CI build, add the *contents* of `google-services.json` as a repository
secret `GOOGLE_SERVICES_JSON` and add this step before the build:
`run: echo "$GOOGLE_SERVICES_JSON" > app/google-services.json` with
`env: GOOGLE_SERVICES_JSON: ${{ secrets.GOOGLE_SERVICES_JSON }}`.

## 1. Open the project in Android Studio

1. Install the latest stable **Android Studio** (it bundles JDK 17+ and the SDK manager).
2. **File → Open…** → select the repository folder (the one containing `settings.gradle.kts`).
3. Trust the project when asked.

## 2. Sync Gradle

Studio syncs automatically. The wrapper downloads Gradle 8.14.3, and Android SDK 35 is installed
on first sync (accept the licence prompt if one appears).

* If Studio offers to **upgrade the Android Gradle Plugin**, accepting is fine.
* Command line equivalent: `./gradlew help` (`gradlew.bat help` on Windows).

## 3. (Optional) Backend credentials

Follow [BACKEND_SETUP.md](BACKEND_SETUP.md) and drop `google-services.json` into `app/`. Skip this
to run in on-device mode. Never commit that file, keystores or `keystore.properties`; they're all
in `.gitignore`.

## 4. Prepare your phone (physical device required)

The emulator's camera is fake, so the timelapse, screen-off behaviour, heat and battery **must**
be tested on a real phone.

1. **Settings → About phone → tap "Build number" 7 times** (on Xiaomi it's "MIUI/OS version").
   You'll see "You are now a developer".
2. **Settings → System → Developer options → USB debugging: on.**
   (Xiaomi also needs "Install via USB" and "USB debugging (Security settings)".)
3. Connect with a USB cable and accept the **"Allow USB debugging?"** prompt on the phone.
4. In Studio, pick your phone in the device dropdown (`adb devices` should list it).

Wireless alternative: Developer options → **Wireless debugging** → *Pair device with pairing code*,
then in Studio use **Pair Devices Using Wi-Fi**.

## 5. Run

Press **Run ▶** (Shift+F10). Or from the command line:

```bash
./gradlew installDebug
adb shell am start -n com.studytimelapse.app.debug/com.studytimelapse.app.MainActivity
```

## 6. Build and install a debug APK

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Without a computer: copy the APK to the phone and open it. Android asks you to allow installing
from that source (Files or Chrome) once.

## 7. Release build (APK + AAB)

1. Create a signing key **once** and keep it safe. If you lose it you can't update the app.

   ```bash
   keytool -genkeypair -v -keystore studytimelapse-release.jks -alias studytimelapse \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` in the project root (git-ignored):

   ```properties
   storeFile=studytimelapse-release.jks
   storePassword=********
   keyAlias=studytimelapse
   keyPassword=********
   ```

3. Build:

   ```bash
   ./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
   ./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab  (Play Store)
   ```

Release builds are minified with R8. Without `keystore.properties` the release build falls back
to the debug key, which is fine for sideloading but not for Play.

## 8. Tests

```bash
./gradlew testDebugUnitTest              # JVM: streaks, midnight, pause/resume, goals, leaderboard
./gradlew connectedDebugAndroidTest      # device: Room database behaviour
```

Then work through the manual checklist in [TESTING_CHECKLIST.md](TESTING_CHECKLIST.md).

## 9. Verify a real session on the phone (5 minutes)

1. Open the app → finish onboarding → **allow Camera and Notifications**.
2. **Settings → Screen-off test** → *Start test* → lock the phone for ~20 s → unlock → *See
   result*. This tells you whether your phone can record with the screen locked.
3. Home → **Start study session** → pick a subject → target 25 m → interval **1 s** (for a quick
   test) → frame the desk → **Start**.
4. Study mode shows `● TIMELAPSE RECORDING · N frames` and "Last frame 0–1 s ago". The notification
   shows a running timer.
5. Wait for the screen to dim (8 s). With **Dim** mode, leave it. With **Lock screen** mode, press
   the power button.
6. After a few minutes come back: the frame count should have grown by about (minutes × 60 /
   interval). If frames stopped, the app shows it and a notification alerted you.
7. **Finish → Finish & process timelapse**. The summary shows "Creating your timelapse…" and then
   a thumbnail. Tap **▶** to watch, and try 4× / 16×.
8. Home shows today's time, streak and weekly totals. Activity lists the session.
9. Optional, to confirm where the file lives:
   `adb shell run-as com.studytimelapse.app.debug ls -la files/timelapses/<id>/`
   (not accessible to other apps).
