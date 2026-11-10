# Xtream & MAC to M3U Converter

A small self-hosted web app (plus a Termux / terminal CLI) that turns **Xtream Codes** or **MAC (Stalker) portal** credentials into a clean M3U playlist. Filter by category keywords (only `tamil`, only `sports`, exclude `adult` ...). Deploys to Render in a few clicks.

## Features
- **Xtream Codes:** server URL + username + password
  - Live TV and Movies (VOD), `.ts` or `.m3u8`, proper `tvg-*` tags and EPG (`xmltv.php`) link
- **MAC portal (Stalker):** portal URL + MAC + Device ID 1 + Device ID 2 + serial number
  - Behaves like a real MAG250 box: handshake, get_profile, channel list, create_link
  - **Test connection** button: shows channel count, account expiry and proves a link can be created
  - Stream links are resolved on demand, so they never expire in your player
  - Auto re-login when the portal token expires, paginated channel fallback, unknown categories kept as "Other"
  - **Redirect mode** (fast) or **Proxy mode** (server relays the stream, fixes IP-bound links, HLS supported)
  - Optional EPG URL, channel numbers (`tvg-chno`), absolute logo URLs
  - MAC can be typed as `00:1A:..`, `00-1a-..` or `001A..`
- **Category filtering:** include / exclude keywords or a checklist with channel counts
- Sort A-Z, copy the link, download the `.m3u`
- Optional `ACCESS_KEY`; SSRF protection (private / local addresses blocked); nothing is stored, only an in-memory cache
- **Termux CLI** (`cli.py`) writes a ready M3U with already-resolved links, for one or many portals

## Deploy on Render
1. Push this folder to a GitHub repository.
2. In Render choose **New -> Blueprint** and select the repository (`render.yaml` configures everything).
3. Set `ACCESS_KEY` in the dashboard (recommended, otherwise anyone with your URL can use it).
4. Optional: `XTREAM_URL` (short Xtream links), `DEFAULT_EPG` (XMLTV url for MAC portal playlists).

Start command (already in `render.yaml`):

    gunicorn app:app --bind 0.0.0.0:$PORT --workers 1 --threads 16 --timeout 120 --graceful-timeout 20

One worker on purpose: the portal token and cache live in memory, and many portals kick out an old session when the same MAC logs in twice. Threads (not sync workers) are needed so proxy mode can stream for a long time.

## Use the web app
Open the site, choose **MAC Portal**, fill the 5 fields, press **Test connection**, then **Load categories**, tick what you want, **Create M3U link** and paste the link into your player (TiviMate, IPTV Smarters, VLC ...).

The generated link looks like `https://<app>/portal.m3u?url=...&mac=...&d1=...&d2=...&sn=...&cats=1,2&proxy=1`. It contains your credentials, so do not share it.

| Parameter | Meaning |
|---|---|
| `url`, `mac`, `d1`, `d2`, `sn` | Portal URL, MAC, Device ID 1 and 2, serial (d2 blank = same as d1) |
| `keywords=tamil,sports` | Only categories containing these words |
| `exclude=adult,xxx` | Skip categories containing these words |
| `cats=1,5,other` | Exact category IDs (`other` = channels without a known category) |
| `proxy=1` | Proxy mode (see below) |
| `epg=https://...xml` | Adds `x-tvg-url` to the playlist |
| `sort=1` | Sort A-Z |
| `download=1` | Download as file |
| `key=...` | Your `ACCESS_KEY`, if set |

### Redirect or Proxy?
- **Redirect (default):** the player asks this server, the server resolves the link and redirects your player to the real stream. Light on bandwidth.
- **Proxy:** use it when channels do not play. Many portals tie the stream token to the IP that created the link. This server created it, your phone plays it, so the portal refuses. Proxy mode makes the server fetch and relay the stream (HLS playlists are rewritten, segments are signed). It uses the server's bandwidth.
- If the portal blocks the hosting provider's IP completely (handshake fails or "non-JSON reply" on Render but works from your phone), run the CLI in Termux instead.

### Xtream links

    https://<app>/<host:port>/<username>/<password>.m3u

With `XTREAM_URL` set: `https://<app>/<username>/<password>.m3u`. Parameters: `kind=vod`, `fmt=m3u8`, `keywords`, `exclude`, `cats`, `sort=0`, `https=1`, `epg`, `key`.

## Termux / terminal CLI
Setup (once):

    pkg update -y && pkg upgrade -y
    pkg install python git nano -y
    pip install requests
    termux-setup-storage

Get the code and run:

    git clone https://github.com/<you>/<repo>.git && cd <repo>
    python cli.py --url "http://host/stalker_portal/c/" --mac 00:1A:79:XX:XX:XX \
                  --sn SERIAL --d1 DEVICEID --d2 DEVICEID -o ~/storage/shared/playlist.m3u

Several portals: copy `portals.example.json` to `portals.json`, edit it (it is git-ignored), then

    python cli.py --config portals.json --outdir ~/storage/shared

Useful options: `--keywords tamil,sports`, `--exclude adult`, `--only 2`, `--epg URL`, `--threads 8`, `--no-resolve`.
Links in the CLI file are real stream links created at that moment; if channels stop playing later, run the command again.

## Run locally

    pip install -r requirements.txt
    python app.py            # http://localhost:5000

Portal on your home network? `ALLOW_PRIVATE=1 python app.py` (private addresses are blocked by default).

## Tests

    python -m unittest discover -s tests -v

Uses a built-in fake Stalker portal and fake Xtream server, no real network needed.

## Environment variables
| Name | Purpose |
|---|---|
| `ACCESS_KEY` | Protects the whole app (`key=` parameter) |
| `SECRET_KEY` | Signs proxy segment links (Render generates it) |
| `XTREAM_URL` | Short Xtream links |
| `DEFAULT_EPG` | Default XMLTV url for MAC portal playlists |
| `ALLOW_PRIVATE` | `1` = allow private / LAN addresses |

## Troubleshooting
| Message | Meaning / fix |
|---|---|
| `Handshake failed ... non-JSON reply` | Wrong portal URL, or the portal blocks the server's IP. Try the CLI from your phone. |
| `Handshake failed ... no token` | MAC not registered / account expired. |
| `Portal returned 0 channels` | Account expired, MAC not active, or device IDs / serial wrong. |
| `Portal gave no playable link` | Channel blocked, account expired, or too many devices on this MAC. Close the box / other app using the same MAC. |
| Channels do not play, but the link test says OK | Use **Proxy** mode (IP-bound stream token). |
| `Private / local addresses are not allowed` | Set `ALLOW_PRIVATE=1` when running at home. |
| First request slow on Render | Free plan sleeps after 15 minutes idle. |

## Notes
- A generated link contains your credentials. Do not share it publicly, and set `ACCESS_KEY`.
- Do not commit `portals.json` or real credentials to GitHub.
- Use this only with services you have a valid subscription or authorization for.
