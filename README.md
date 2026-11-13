# Xtream & MAC to M3U Converter (Android)

MAC Portal (Stalker) or Xtream login -> **M3U with real stream links**, saved as a file and/or hosted as a link.

- No localhost, no server, no background service, no notification.
- Runs on your phone, so the provider sees your phone IP (no datacenter 403).

## Fast
- **Smart paste**: paste all details (URL, MAC, serial, device IDs - or an Xtream `get.php?username=..&password=..` link) and every field is filled automatically.
- Handshake endpoints are tried **in parallel**, genres + channels + account info load **at the same time**, replies are gzip-compressed.
- The channel list is **saved on the phone** - opening a source again is instant (refresh with the ⟳ button).
- Live connect steps (reach -> login -> channels -> categories).

## Choose exactly what you want
- Tick / untick **individual channels**, All / None / Invert for what you see, search, category filter chips.
- **Pick categories** sheet: one tap ticks or unticks a whole category.
- Your selection is remembered per source (by stable channel id, so it survives list refreshes).
- Exclude words (e.g. adult), EPG url, speed, sort.

## Output
- Saves `Downloads/<Name>_<date>.m3u` (open in player / share).
- **Category-wise M3U**: one file per category + `<Name>_Full_Channels.m3u`.
- **Host as link**: secret GitHub Gist -> permanent raw links (full + per category). **Refresh M3U** (home) re-exports your saved selection and updates the same gist, so the link never changes.
- Copy link / Play / Share / Copy M3U code.

## Hosting setup (once)
Home -> Settings -> paste a GitHub token: github.com/settings/tokens -> Generate new token (classic) -> tick only `gist`.
The token stays on the phone. A secret gist is unlisted: anyone with the link can read it (it contains your stream tokens) - don't post it. GitHub caches raw gist files a few minutes after a refresh.

## Build the APK (GitHub Actions)
1. New GitHub repo, upload everything in this folder (keep the structure incl. `.github/workflows/build-apk.yml` and `app/src/main/AndroidManifest.xml`).
2. Actions -> "Build APK" -> Run workflow.
3. Download artifact -> install `app-debug.apk`.

## Notes
- Links contain your portal token / credentials. Do not share the .m3u publicly.
- Portal tokens can expire after a few hours (some bind to your IP). When a channel stops playing tap **Refresh M3U**.
- Credentials and the channel cache live only inside the app on your phone.

## Dev check (no Android SDK needed)
`tools/CoreSmoke.java` + `tools/mock_portal.py` + `tools/mock_gist.py` test the whole engine on a plain JVM:
start `python3 tools/mock_portal.py` and `python3 tools/mock_gist.py` (each prints a port), then `java -cp out CoreSmoke <portal port> <gist port>`.
