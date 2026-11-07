# SHADOWBOX X
**Your Digital World. Your Privacy.**

Offline-first encrypted personal vault for Android (Kotlin, Jetpack Compose, Material 3, MVVM + clean layering,
Room, DataStore, Android Keystore, WorkManager, Media3).

## Build
Requirements: JDK 17, Android SDK 34 (Android Studio Koala/Ladybug or newer).

1. Open the folder in Android Studio and let Gradle sync (Studio supplies Gradle 8.7), or run `gradle :app:assembleDebug`.
2. APK: `app/build/outputs/apk/debug/app-debug.apk`.
3. GitHub: push the repo; `.github/workflows/android-build.yml` builds the debug APK and uploads it as an artifact.

> No Gradle wrapper jar is included (binary). Run `gradle wrapper --gradle-version 8.7` once if you want `./gradlew`.

## IMPORTANT - status
This project was generated without access to an Android SDK or network, so **it has not been compiled or run**.
Expect to fix a handful of compile errors on first build. Nothing here has been security-audited.

## Features
Master lock (password / 4 / 6 / custom PIN, biometrics, auto-lock, lockout back-off, recovery key) - encrypted file vault with
folders (nested, per-folder PIN/biometrics, colors/icons) - password manager - encrypted notes with attachments and autosave -
Shadow Gallery (Media3) - Shadow Sync (QR pairing, local Wi-Fi, resumable, integrity-checked) - encrypted backup/restore/verify/schedule.

## Known limitations (documented, not hidden)
- Previews/playback decrypt to a temp file in app cache (wiped on close/lock/start). Large videos need free space equal to their size. No streaming DataSource.
- Dependency injection is a hand-written composition root (`AppContainer`), not Hilt.
- Domain layer is models + tools; repositories are concrete classes (no interfaces).
- Scheduled backup runs only while the vault is unlocked (the key is RAM-only); a due backup runs right after next unlock.
- "Cloud backup" = you pick any cloud-backed folder via the system file picker. No cloud SDK is bundled.
- Shadow Sync syncs files and folders only (not passwords/notes). Pairing secret is one-time and not forward-secret.
- Lockout timers use wall-clock time (can be bypassed by changing the device clock).
- Temp-file wiping is best-effort overwrite; flash storage may keep remnants.
- FLAG_SECURE is applied to the whole app. Rooted/compromised devices, keyloggers and screen-readers are out of scope.
- Room schema export is off and there are no migrations yet (v1). Never use destructive migration.
