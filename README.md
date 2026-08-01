# Billie Backup

Native Android utility that gathers files related to Billie Eilish into one organized backup folder.

## What it does
- Remembers multiple source folders selected with Android's system folder picker.
- Recursively matches `Billie`, `Eilish`, combinations such as `Billie_Eilish`, and files inside matching folders.
- Previews match count and total known size before changing anything.
- Copies by default; optional move mode is clearly labeled.
- Sorts into Images, Videos, Audio, Documents, and Other while preserving source/subfolder structure.
- Avoids overwriting existing files and skips equal-size duplicates.
- Uses a foreground data-sync service for long backups.
- Requires no internet permission and no broad all-files permission.

## Build
Open in Android Studio or run `gradle :app:assembleDebug` with Android SDK 35 installed.

The included GitHub Actions workflow compiles and signature-verifies the debug APK.
