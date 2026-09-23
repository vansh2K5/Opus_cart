# Manual test checklist (real phone)

Automated tests cover the maths (`app/src/test`) and the database (`app/src/androidTest`). The
camera and power behaviour can only be verified on a real device. Record the results for each
phone you use.

Phone: ______________  Android: ____  Mode: Dim / Lock  Interval: __ s  Resolution: ____

## Long sessions (battery, heat, storage)

| Session | Start % | End % | Frames expected | Frames got | Gaps | Video length | File size | Max warmth |
|---|---|---|---|---|---|---|---|---|
| 30 min |  |  | 900 @2s |  |  |  |  |  |
| 1 h |  |  | 1,800 |  |  |  |  |  |
| 2 h |  |  | 3,600 |  |  |  |  |  |
| 4 h (charging ok) |  |  | 7,200 |  |  |  |  |  |

Pass: frames got ≥ 97 % of expected, 0 gaps unless explained, video plays, and the study time
matches a wall clock (minus pauses) to within a few seconds.

## Interruptions

| # | Scenario | Expected | ✓ |
|---|---|---|---|
| 1 | **Screen locked** (power button) during a session | Lock mode: frames continue on capable phones; otherwise within ~20 s an alert says the camera stopped and a gap is counted. Timer unaffected. | |
| 2 | **Screen off by timeout** in Dim mode | Can't happen: the screen stays on at minimum brightness. | |
| 3 | **App backgrounded** (Home button, open another app for 5 min) | Notification timer keeps running, frames continue, study mode shows the correct time when you return. | |
| 4 | **Incoming voice call**, answered for 1 min | Timer and frames continue. | |
| 5 | **Video call / open the Camera app** | Study mode shows "Another app is using the camera", gap +1, recording resumes after closing it. | |
| 6 | **Low battery** (drop below 15 %) | Warning shown; the current segment is closed (safe). | |
| 7 | **Battery Saver** system mode on | Recording continues, or the watchdog alert appears. | |
| 8 | **Low storage** (fill until < 500 MB) | Warning; below 150 MB the timelapse stops with an explanation, the timer continues, and the footage so far plays. | |
| 9 | **Camera permission denied** at setup | Setup shows "Allow camera"; a session can still run with the timelapse off. | |
| 10 | **Permission revoked mid-session** (Settings → Apps → Permissions) | Android kills the process. On reopen the session is restored; "Camera not running"; Restart camera asks for permission. | |
| 11 | **Camera unavailable** (another app holding it at start) | "Camera unavailable/waiting"; the timer runs. | |
| 12 | **Device rotation** during setup (landscape on desk) | Orientation Auto: the video comes out upright in landscape. | |
| 13 | **App force-stopped** (Settings → Force stop) mid-session | Reopen: session restored and still running (same boot), gap counted, *Restart camera* works, final video contains the footage from before and after. | |
| 14 | **Phone rebooted** mid-session | Reopen: "Your phone restarted…", session paused at last checkpoint (≤ 30 s lost), Resume or Finish both work, and the pre-reboot footage is in the video. | |
| 15 | **Pause 20 min → Resume** | Study time excludes the pause, the summary shows paused time, the camera is off (no green dot) while paused. | |
| 16 | **Overheating** (hot room, charging, sun) | "Phone getting warm" → slower capture; if critical → paused and later resumed; video intact. | |
| 17 | **Finish then immediately close the app** | Processing still completes (WorkManager); the timelapse appears later. | |
| 18 | **Airplane mode** whole session | Everything works; sync happens when back online. | |
| 19 | **23:50 → 00:20 session** | 10 min on the first day, 20 min on the second; the streak counts both if over the threshold. | |
| 20 | **Change time zone** mid-week | Days follow the configured zone; no double-counting. | |

## Social (two phones)

| # | Scenario | Expected | ✓ |
|---|---|---|---|
| S1 | Invite code flow | Code → request → accept → both see the leaderboard. | |
| S2 | Leaderboard after a 2 h session | The friend sees +2h within seconds (online). | |
| S3 | Ten 3-minute sessions | Study time +30 min, sessions metric +0. | |
| S4 | Remove friend | Both lose access immediately. | |
| S5 | Privacy | Firestore has no video, thumbnail or notes. With "Share subjects" off, sessions show as "Study". | |
| S6 | Delete account | All the user's Firestore docs are gone; the friend's leaderboard empties. | |

## Final scenario (the product test)

Install APK → sign in → Home → **Start study session** → *Cybersecurity* → target **2 h** →
**2 s** interval → frame the desk → Start → Dim (or Lock if your phone passed the test) → study
2 h → Finish → the summary shows **2h 00m** → timelapse ≈ 2:00 plays → streak +1 → Home and Activity
updated → friend's phone shows 2h → leaderboard updated → the video exists only on your phone.
