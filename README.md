# M3U Converter (Android APK)

MAC Portal (Stalker) / Xtream login -> **M3U playlist with real stream links**.

- No localhost, no server, no background service, no notification. Just a normal app.
- Runs on your phone, so the portal sees your phone IP (not a datacenter IP -> no 403).
- Saved sources, one-tap **Refresh M3U** (re-uses your saved filters), freshness dot.
- Category chips with counts, search, exclude words, tap a channel to test-play.
- Fast parallel link resolving, progress + ETA, cancel, retry failed, screen stays on.
- Saves to `Downloads/<Name>_<date>.m3u`, then Open in player / Share.

## Build the APK (GitHub Actions)
1. Create a NEW GitHub repo and upload everything in this folder (keep the folder structure, including `.github/workflows/build-apk.yml` and `app/src/main/AndroidManifest.xml`).
2. Repo -> Actions -> "Build APK" -> Run workflow (or just push).
3. Download artifact `M3U-Converter-debug` -> unzip -> install `app-debug.apk`.

## Notes
- Links contain your portal token / credentials. Do not share the .m3u publicly.
- Portal tokens can expire after a few hours (some bind to your IP). When a channel stops playing, tap **Refresh M3U**.
- Credentials are stored only inside the app on your phone.

## Dev check (no Android SDK needed)
`tools/CoreSmoke.java` + `tools/mock_portal.py` test the whole engine on a plain JVM.
