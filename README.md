# M3U Converter (Android APK)

MAC (Stalker) portal and Xtream Codes -> M3U, running **on your phone**. Because the phone itself talks to the portal, there are no blocked data-center IPs (the HTTP 403 you get on Render) and no IP-bound stream tokens.

How it works: the app starts a small playlist server on `127.0.0.1:8765` (a notification shows while it runs) and shows the same web UI as the Render version inside the app.

## Get the APK (no Android Studio needed)
1. Create a new GitHub repository and upload everything in this folder (keep `.github/workflows/build-apk.yml`).
2. Open the repo -> **Actions** tab -> **Build APK** -> wait for the green tick (about 3-5 minutes). If it did not start, press **Run workflow**.
3. Open the finished run -> **Artifacts** -> download `M3U-Converter-APK` -> unzip -> `app-debug.apk`.
4. Copy the APK to the phone and install it (allow "install unknown apps" once).

Android 10 or newer is needed.

## Use
1. Open the app and allow notifications (keeps the server alive in the background).
2. **MAC Portal** tab: portal URL, MAC, Device ID 1, Device ID 2, serial -> **Test connection**.
3. **Load categories**, tick what you want (for example Tamil), **Create M3U link**.
4. **Open in player app** (VLC, TiviMate, IPTV Smarters ...), or **Copy** and paste the link into your player, or **Download .m3u** (saved in the Downloads folder).

The link looks like `http://127.0.0.1:8765/portal.m3u?...`. It works only on the same phone while the app is running. Channel links are resolved when you press play, so they never expire. For a list of resolved links that does not need the app, use the Termux `cli.py` of the web repo.

Xtream Codes tab works the same way.

## Tips
- If the playlist stops loading after a while, open the app once (some phones kill background apps). Turn off battery optimization for "M3U Converter" to be safe.
- 10000+ channels is fine, but pick categories (Tamil, Sports ...) so your player loads fast.
- Playlist links contain your MAC / device ids. Do not share them.

## Project layout
- `app/src/main/java/app/m3uconv/core/` - portal client, Xtream, M3U builder and the local HTTP server (plain Java, no Android classes)
- `app/src/main/java/app/m3uconv/MainActivity.java`, `ServerService.java` - the thin Android shell (WebView + foreground service)
- `app/src/main/assets/index.html` - the UI
- `tools/CoreSmoke.java` + `tools/mock_portal.py` - test of the core on a normal JVM against a fake portal

Run the core test (needs Java 17 and Python with Flask):

    pip install flask && python3 tools/mock_portal.py   # prints the fake portal port, keep it running
    javac -d /tmp/out $(find app/src/main/java/app/m3uconv/core -name '*.java') tools/CoreSmoke.java
    java -cp /tmp/out CoreSmoke <fake portal port>
