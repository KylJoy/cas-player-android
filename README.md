# CAS Player (Android)

A simple .CAS-to-audio player for TRS‑80. Supports FM 250/500 and FSK 1500.

## Easiest way to get an APK (GitHub-only, no Android Studio required)

1. Create a free GitHub account and sign in.
2. Click the **+** in the top-right → **New repository** → name it e.g. `cas-player-android` → **Create repository**.
3. On the new repo page, click **"uploading an existing file"** and drag-drop the *contents* of this folder (not the folder itself).
4. Click **Commit changes**.
5. Go to the **Actions** tab → open the latest run → under **Artifacts** download **app-debug** → inside is `app-debug.apk`.

Install the APK on your phone (enable "Install unknown apps").

## Without GitHub: Android Studio
1. Install Android Studio.
2. Open this project folder.
3. Menu: **Build → Build APK(s)** → get `app/build/outputs/apk/debug/app-debug.apk`.

## Playback tips
- Start with 500 FM, MSB→LSB, Leader 1500 ms, volume ~75–85%.
- Disable phone EQ/Dolby; use airplane mode to avoid notifications.
