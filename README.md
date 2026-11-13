# M3U Converter (Android APK)

MAC Portal (Stalker) / Xtream login -> **M3U playlist with real stream links**.

- No localhost, no server, no background service, no notification. Just a normal app.
- Runs on your phone, so the portal sees your phone IP (not a datacenter IP -> no 403).
- Saved sources, one-tap **Refresh M3U** (re-uses your saved filters), freshness dot.
- Category chips with counts, search, exclude words, tap a channel to test-play.
- Fast parallel link resolving, progress + ETA, cancel, retry failed, screen stays on.
- Saves to `Downloads/<Name>_<date>.m3u`, then Open in player / Share.
- **Category-wise M3U**: one file per category + the full `<Name>_Full_Channels.m3u` file.
- **Host as link**: uploads to a *secret* GitHub Gist -> permanent raw M3U links (full + per category). Refresh updates the same gist, so the link never changes. Copy link / Play / Share, or Copy M3U code.
- Auto bearer-token fetch (portal handshake), no proxy needed.

## Hosting setup (once)
Home -> Settings -> paste a GitHub token: github.com/settings/tokens -> Generate new token (classic) -> tick only `gist`.
The token stays on the phone. Secret gist = unlisted, anyone with the link can read it (it contains your stream tokens) - don't post it. GitHub caches raw gist files a few minutes after a refresh.

## Build the APK (GitHub Actions)
1. Create a NEW GitHub repo and upload everything in this folder (keep the folder structure, including `.github/workflows/build-apk.yml` and `app/src/main/AndroidManifest.xml`).
2. Repo -> Actions -> "Build APK" -> Run workflow (or just push).
3. Download artifact `M3U-Converter-debug` -> unzip -> install `app-debug.apk`.

## Notes
- Links contain your portal token / credentials. Do not share the .m3u publicly.
- Portal tokens can expire after a few hours (some bind to your IP). When a channel stops playing, tap **Refresh M3U**.
- Credentials are stored only inside the app on your phone.

## Dev check (no Android SDK needed)
`tools/CoreSmoke.java` + `tools/mock_portal.py` + `tools/mock_gist.py` test the whole engine on a plain JVM:
`python3 tools/mock_portal.py` / `python3 tools/mock_gist.py` (each prints a port), then `java -cp out CoreSmoke <portal port> <gist port>`.
