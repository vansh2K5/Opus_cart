# Screen-off camera recording on Android: what actually works

This is the hardest requirement of StudyTimelapse, so here is exactly what Android allows,
what this app does, and what it cannot promise.

## TL;DR

| Question | Answer |
|---|---|
| Can an app keep using the camera after you leave it or lock the phone? | **Yes, legitimately**, via a *foreground service of type `camera`* that was started while the app was visible. This is what the app does. |
| Does that work on every phone? | **No guarantee.** Stock Android (e.g. Pixel) keeps delivering frames with the screen off. Some manufacturers' power managers stop or throttle it. |
| Can the app silently pretend it's recording? | **Never.** A watchdog checks that frames keep arriving and alerts you if they stop. Every gap is counted and shown. |
| What's the always-reliable mode? | **Dim mode** (default): screen stays on, forced to minimum brightness, all-black UI, hidden system bars. |
| Can the app know which works on *your* phone? | **Yes**: Settings → *Screen-off test* records for ~30 s while you lock the phone and reports what happened. Real sessions also record it. |

## The rules (Android 9 → 15)

1. **Android 9+**: apps in the background cannot access the camera. An app with a visible
   activity *or a foreground service* is not "in the background".
2. **Android 11+**: camera access from a foreground service is *while-in-use*. It's only granted
   if the service was started while the app was visible, or from a notification tap. That's why the
   service is started from the Start button, and why it is `START_NOT_STICKY`: if Android killed
   it, restarting it silently in the background would be refused anyway.
3. **Android 14+**: the service must declare `android:foregroundServiceType="camera"`, hold
   `FOREGROUND_SERVICE_CAMERA` and have the runtime `CAMERA` permission *before*
   `startForeground()`. Done (see `AndroidManifest.xml` and `StudySessionService.goForeground`).
4. **Android 15**: camera foreground services can't be launched from `BOOT_COMPLETED`, and there's
   no time limit on the camera type (unlike `dataSync`). The app never auto-starts on boot. After a
   reboot it restores the session *paused*, at the last checkpoint, and asks you.
5. **Doze / App Standby**: a foreground service plus a `PARTIAL_WAKE_LOCK` keeps the CPU running
   so camera callbacks keep arriving with the screen off. The wake lock has a timeout, is renewed
   every 30 minutes while capturing, and is released whenever the camera is off (paused,
   overheating, storage full).
6. **Privacy indicator**: Android 12+ shows the green camera dot while recording. That's expected
   and correct.
7. **Camera priority**: a foreground app that opens the camera (Camera app, video call) takes it
   from us. CameraX reports `ERROR_CAMERA_IN_USE` or `PENDING_OPEN`; the app shows "Another app is
   using the camera", counts a gap, and reopens automatically when the camera is free. A normal
   voice call doesn't need the camera and doesn't interrupt recording.

## Manufacturer behaviour

Some vendors add power managers on top of Android: Xiaomi/MIUI/HyperOS, Huawei/EMUI,
OnePlus/Oppo/Realme (ColorOS), Samsung "Sleeping apps", Vivo. On these phones one of three things
can happen when the screen turns off:

* frames keep coming (the best case),
* the camera is closed after a delay (frames stop: we detect it within about 20 s),
* the whole app is killed (the notification disappears; the timer is restored when you reopen
  the app, and the gap is recorded).

What the app does about it:

* **Screen-off test**, so you know before a 3-hour session.
* **Watchdog alert**: "Timelapse interrupted — Your phone stopped the camera while the screen was
  off. Use Dim mode…". The study timer is never affected.
* **Settings → Battery optimisation settings** shortcut, plus a pointer to dontkillmyapp.com for
  vendor-specific switches ("No restrictions", "Autostart", "Lock app in recents").

## Techniques considered and rejected

| Technique | Why not |
|---|---|
| `PROXIMITY_SCREEN_OFF_WAKE_LOCK` | Only turns the screen off when something covers the sensor. It's meant for calls, and a phone facing the desk isn't covered. |
| Drawing over the lock screen / `showWhenLocked` to "keep the app visible" | Doesn't save any power over Dim mode, and it's fragile. |
| Device-admin / accessibility tricks to fake foreground state | Policy violation, and dishonest. |
| Restarting the camera from the background after a kill | Refused by Android 11+ (while-in-use). Pretending otherwise would violate "never silently stop". |

## Why Dim mode is a good default

On OLED phones (most mid-range and all flagship phones since ~2019), a black pixel is *off*. A
black UI at minimum brightness with a small dim timer draws little power: typically tens of mW,
versus a few hundred mW for the camera pipeline itself. On LCD phones the backlight still draws
power, and there the screen-off mode (if your phone passes the test) is worth more.

Extra details:

* `FLAG_KEEP_SCREEN_ON` so the phone never auto-locks and triggers vendor camera kills.
* `screenBrightness = 0.01` window override (not the system setting, which is restored when you
  leave).
* System bars hidden; controls fade after 8 s; any tap wakes them.
* The timer shifts by a few pixels every minute to avoid OLED burn-in on 4-hour sessions.

## Timelapse pipeline: approaches compared

| Approach | Verdict |
|---|---|
| **CameraX `ImageAnalysis` → 1 frame per interval → hardware `MediaCodec` H.264 → `MediaMuxer` segments** (chosen) | Constant memory, no JPEGs, hardware encoder. The camera is asked for its lowest AE frame rate. Crash-safe segments. No preview surface needed, so it works with no UI. |
| `ImageCapture.takePicture()` every N seconds | Runs a full still-capture pipeline (3A, full-res JPEG) per frame: more power and heat, thousands of JPEGs on disk, and a slow encode at the end. |
| CameraX `VideoCapture` / `MediaRecorder` with capture rate | The camera and encoder run continuously at 30 fps. The legacy time-lapse profiles are poorly supported on Camera2 devices. One MP4 means a crash loses everything. |
| FFmpeg | Unnecessary: the platform `MediaCodec`/`MediaMuxer`/`MediaExtractor` do everything, in hardware, with no 20-MB native library. |

### Numbers for the default (1 frame / 2 s, 720p, "Medium")

* 2 h session → 3,600 frames → **2:00** of 30 fps video (60× speed-up)
* Bitrate 3 Mbps → **~45 MB** per 2 h (Battery Saver: 10 s interval, 480p → ~3 MB)
* RAM: a few encoder buffers (~1.4 MB each) regardless of session length
* Worst-case loss on a crash or power loss: the current segment (≤ 10 minutes of study, a few
  seconds of video). On low battery the segment is closed early.

### Battery: be realistic

The camera sensor and ISP must stay powered to deliver *any* frame, so a camera-on session will
use noticeably more battery than an idle phone. Expect something like **5–15 % per hour** depending
on the phone, the brightness, and whether Dim or screen-off mode is used. Measure your own phone
with the testing checklist. To reduce it: Battery Saver Timelapse, a longer interval, 480p, or
charging during long sessions.

## Thermal handling

`PowerManager.addThermalStatusListener` (Android 10+):

* `SEVERE` → capture interval doubled (max 30 s), with the in-app and notification message "Your
  phone is getting warm. We've reduced capture frequency to protect the device."
* `CRITICAL` or worse → camera closed and the current segment finalised. The timer keeps running,
  recording resumes automatically at `MODERATE` or cooler, and the gap is counted.

## Interruption matrix

| Event | Timer | Timelapse |
|---|---|---|
| Lock screen / screen off | continues | continues where the device allows it; otherwise alert and gap |
| Open another app | continues | continues (foreground service) |
| Voice call | continues | continues |
| Video call / Camera app | continues | paused by Android, auto-resumes, gap counted |
| Battery saver mode on | continues | usually continues (foreground services are exempt); watchdog covers vendors that don't |
| Overheating | continues | slower, then paused, then resumes |
| Storage < 150 MB | continues | stopped, footage so far saved |
| App killed by Android | continues like a stopwatch (restored from the database) | stops; "Restart camera" button; gap counted |
| Phone reboot | restored **paused** at the last checkpoint (≤ 30 s lost); you decide | segments up to the last checkpoint are kept |
| Camera permission revoked mid-session | continues | stops; clear message |
