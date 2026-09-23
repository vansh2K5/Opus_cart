# Architecture

**Priorities:** working > simple > reliable > battery-efficient > beautiful > feature-rich.

## Stack

| Concern | Choice | Why |
|---|---|---|
| Language/UI | Kotlin, Jetpack Compose, Material 3 | Modern native Android, fast iteration |
| Camera | CameraX `ImageAnalysis` (+ Camera2 interop for lowest FPS) | Handles device quirks; no preview surface needed while recording |
| Video | `MediaCodec` (H.264, hardware) + `MediaMuxer` / `MediaExtractor` | Progressive encoding, constant RAM, no FFmpeg |
| Background | Camera-type **foreground service** + partial wake lock | The only supported way to keep the camera while the app isn't visible |
| Local DB | Room (SQLite) | Offline source of truth |
| Settings | DataStore | Async, type-safe prefs |
| Deferred work | WorkManager | Video joining, sync, reminders survive process death |
| Player | Media3 ExoPlayer | Speeds 0.5×–16×, seek |
| Backend | Firebase Auth + Firestore (optional) | Free, managed, security rules, offline cache |
| DI | Manual `AppContainer` | Small app; a framework would be ceremony |
| Min / target SDK | 29 (Android 10) / 35 | ~95% of devices; thermal API and FGS types need 29 |

## Layers

```
ui/ (Compose screens)  ──observe──►  repositories (Flow)  ──►  Room / DataStore
     │                                    ▲
     │ start/pause/finish                 │ persist every 30 s + on change
     ▼                                    │
service/SessionManager (in-process, owns the timer: SessionClock)
     │ commands (CameraHost)       ▲ callbacks (frames, segments, errors)
     ▼                              │
service/StudySessionService (FGS type=camera, wake lock, watchdog, thermal/battery/storage)
     │
camera/CapturePipeline (CameraX ImageAnalysis @ lowest fps, 1 frame / interval)
     │ YUV frame
timelapse/TimelapseEncoder (MediaCodec H.264 → rolling MP4 segments, crash-safe)
     │ segments in DB
worker/TimelapseProcessingWorker → SegmentConcatenator (remux) → timelapse.mp4 + thumb.jpg
worker/SyncWorker → data/remote (Firestore: metadata only)
domain/ (pure Kotlin: stats, streaks, goals, achievements, leaderboard) ← unit tested on JVM
```

### Key design decisions

1. **The timer and the camera are separate.** `SessionManager` owns the study clock (monotonic,
   pause-aware, persisted). The camera is an optional add-on hosted by the service. A camera
   failure produces a *gap*, never lost study time.
2. **Stopwatch semantics survive process death.** The running span's monotonic start
   (`elapsedRealtime`) and boot count are persisted. After a kill in the same boot the timer
   continues exactly. After a reboot it resumes *paused* at the last checkpoint: time the app
   couldn't observe is never counted silently.
3. **Crash-safe video.** A single encoder feeds rolling MP4 segments of about 10 minutes of study
   each (a sync frame is requested at each boundary). A crash loses at most the open segment.
   Joining is a lossless remux, and unreadable or mismatched segments are skipped.
4. **Honesty.** A watchdog compares "last frame" against the interval. Every camera error, stall,
   thermal pause or storage stop is shown in study mode, sent as a notification and counted
   (`captureGaps`). The screen-off capability is measured, not assumed.
5. **Offline first.** Everything happens against Room. `SyncWorker` uploads dirty sessions when a
   network is available. Deletions are soft-deleted locally until synced.
6. **Privacy by construction.** No code path uploads files. Videos live in `files/timelapses/`
   (private, excluded from backups). Notes are never synced. Sharing is always an explicit share
   sheet or "Save to gallery".
7. **Fair competition without a server.** Firestore rules reject physically impossible sessions,
   and timing is immutable after upload. Both clients run the same deterministic
   `LeaderboardCalculator`: overlap merging, study time ≤ wall time, ≤ 24 h, and only sessions ≥ 10
   min count as "sessions".
8. **Days are local, instants are UTC.** Timestamps are stored as UTC millis. Study spans are split
   at local midnight in the user's configured zone, so 23:59 → 00:01 counts correctly on both days.

## Data model (Room)

* `sessions`: id, subject, title, startUtc, endUtc, studyMs, pausedMs, pauseCount, targetMs,
  status (ACTIVE/INTERRUPTED/FINISHED/DELETED), captureIntervalSec, timelapseStatus
  (NONE/RECORDING/PROCESSING/READY/FAILED/DELETED), timelapsePath, thumbnailPath,
  timelapseDurationMs, timelapseBytes, frameCount, framesScreenOff, captureGaps,
  rotationDegrees, notes, zoneId, runningSinceUtc, runningSinceElapsed, bootCount,
  lastCheckpointUtc, createdAt, updatedAt, syncState
* `study_spans`: sessionId → [startUtc, endUtc] of actual studying (pause-free)
* `timelapse_segments`: sessionId, index, path, frames, durationUs
* `subjects`, `goals` (period DAY/WEEK/MONTH, targetMinutes), `achievements` (type, unlockedAt)

The User and Friendship entities from the brief live in Firestore (`users`, `friendships`), where
they need to be shared. Firestore's offline cache is the friend-data cache.

## Firestore model

```
users/{uid}                 username, avatar, zone, weeklyGoalMinutes, achievements[], live{subject,startedAt,targetMinutes}
users/{uid}/sessions/{id}   startUtc, endUtc, studyMs, pausedMs, subject, targetMs, zone, uploadedAt
inviteCodes/{CODE}          ownerUid, createdAt, expiresAt
friendships/{uidA_uidB}     users[2], requester, requesterName, receiver, status, code
challenges/{id}             users[2], type, targetMinutes, startDate, endDate, createdBy
```

## Project structure

```
app/src/main/java/com/studytimelapse/app/
  StudyApp.kt, AppContainer.kt, MainActivity.kt
  domain/        pure Kotlin logic (no Android imports)
  data/db/       Room entities, DAOs, database
  data/prefs/    DataStore settings
  data/repository/  sessions, stats dashboard, achievements, subjects
  data/remote/   Firebase auth + social (optional)
  camera/        CapturePipeline (CameraX)
  timelapse/     encoder, YUV copy, segment remux, thumbnails
  service/       SessionManager, StudySessionService, live state
  worker/        processing, sync, reminders
  notifications/ channels + notifier
  ui/theme, ui/components, ui/navigation, ui/screens
  util/          storage paths, sharing/export
app/src/test/        JVM unit tests (domain)
app/src/androidTest/ Room tests
firebase/            security rules
docs/                you are here
```

## Future-proofing

Multiple friends: the `friendships` collection already holds N documents and the UI takes the
first accepted one. Cloud backup: an explicit upload of `timelapse.mp4` to Storage behind a
setting. Widgets and Wear OS: read `SessionManager.live` and the `StatsRepository.dashboard` flow.
AI or posture analysis: an extra `ImageAnalysis` consumer on the same frames, on-device only.
