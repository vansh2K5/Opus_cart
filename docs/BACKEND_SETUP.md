# Backend setup (Firebase): about 15 minutes, free tier

The app works fully **offline and without an account**. You only need this to compete with a
friend. It uses **Firebase Authentication** (email + password) and **Cloud Firestore**, both on the
free *Spark* plan. There's no server of your own to run and no Cloud Functions (those would
need the paid plan).

Only statistics are ever uploaded: session start and end, study time, subject (optional), goal,
and achievements. Videos, thumbnails and notes stay on the phone.

## 1. Create the project

1. Go to <https://console.firebase.google.com> → **Create a project** → name it `StudyTimelapse`.
2. Google Analytics: **turn it off** (not needed).

## 2. Enable email/password sign-in

1. **Build → Authentication → Get started**.
2. **Sign-in method → Email/Password → Enable** (leave "Email link" off) → **Save**.

## 3. Create the Firestore database

1. **Build → Firestore Database → Create database**.
2. Choose a location near you (e.g. `asia-south1` for India, `europe-west1`, `us-central1`).
   *This can't be changed later.*
3. Start in **production mode** (everything is denied until you publish the rules below).

## 4. Publish the security rules

The rules in [`firebase/firestore.rules`](../firebase/firestore.rules) enforce:

* only you can write your data;
* your friend can read your profile and sessions **only after the friendship is accepted**;
* nobody can list users or read strangers' sessions;
* every uploaded session is physically possible: study time ≤ session length, session ≤ 24 h, not
  in the future, a server timestamp, and timing that can't be edited later. This keeps the
  leaderboard honest without a custom server.

**Option A: console (no tools needed).** Firestore → **Rules** tab → replace everything with the
contents of `firebase/firestore.rules` → **Publish**.

**Option B: Firebase CLI**

```bash
npm install -g firebase-tools
firebase login
firebase use --add            # pick your project
firebase deploy --only firestore:rules
```

No custom indexes are needed. All queries use single-field indexes that Firestore creates
automatically.

## 5. Register the Android app(s)

Debug builds use the package `com.studytimelapse.app.debug`; release builds use
`com.studytimelapse.app`. Register **both** so one config file works for both.

1. Project settings (⚙) → **Your apps → Add app → Android**.
2. Package name `com.studytimelapse.app` → nickname "release" → **Register app**. You can skip the
   SHA-1: it isn't needed for email/password sign-in.
3. Repeat with `com.studytimelapse.app.debug`.
4. Download **`google-services.json`** (download it *after* adding both apps so it contains both).
5. Put it at **`app/google-services.json`**.

That file is listed in `.gitignore`, so it is never committed. The build detects it automatically
(`app/build.gradle.kts`): without the file you get an on-device-only app, with it you get
accounts and friends. Rebuild after adding it.

> **Is the API key in that file secret?** Firebase API keys identify the project; they don't
> grant access. Security comes from the rules above. We still keep the file out of git. For
> extra hardening, open Google Cloud Console → *APIs & Services → Credentials*, restrict the key
> to Android apps with your package names and SHA-1s, and optionally enable **Firebase App Check**.

## 6. Test it

1. Install the app on **your** phone → onboarding → *Create account* (email + password ≥ 6 chars).
2. Firebase console → Authentication → **Users**: your account appears.
3. Install on your **friend's** phone → create a second account.
4. On phone A: **Friends → Create invite code** (e.g. `A7K29P`) → Send code.
5. On phone B: **Friends → Have a code? → Add friend**.
6. On phone A: *"… wants to be study friends"* → **Accept**.
7. Both phones now show the leaderboard. Finish a short session on phone A (online). Within
   seconds phone B shows it under *Activity* and in the weekly study time.
8. Firestore → Data: you should see `users/{uid}`, `users/{uid}/sessions/{id}`, `friendships/…`,
   `inviteCodes/…`. There are no video files anywhere.

### Troubleshooting

| Symptom | Fix |
|---|---|
| "Accounts aren't set up in this build" | `app/google-services.json` is missing or in the wrong folder. Rebuild after adding it. |
| `PERMISSION_DENIED` in Logcat | The rules weren't published, or you published them to a different project. |
| Friend sees nothing | The friendship isn't accepted yet, or the session hasn't synced (sync needs a connection; it retries automatically). |
| Sign-up fails on a debug build | The debug package `com.studytimelapse.app.debug` isn't registered in the Firebase project. |

## Account deletion

**Settings → Account → Delete account** asks for your password, then deletes your profile, all
synced sessions, friendships, challenges and invite codes from Firestore, and then the auth
account. Data on the phone is kept until you also choose **Delete all local data**.

## Cost

Two users produce a few hundred document reads and writes per day, far below the Spark free
quota (50k reads and 20k writes per day). Keep **billing disabled** and it can't cost anything.
