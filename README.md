# Xtream & MAC to M3U Converter

A small self-hosted web app that turns **Xtream Codes** or **MAC (Stalker) portal** credentials into a clean M3U playlist. Optionally filter by category keywords (for example only `tamil`, only `sports`, or exclude `adult`). Deploys to Render in a few clicks.

## Features
- **Xtream Codes:** server URL + username + password
  - Live TV and Movies (VOD), `.ts` or `.m3u8` streams
  - Proper `tvg-id`, `tvg-name`, `tvg-logo`, `group-title` tags and an EPG (`xmltv.php`) link
- **MAC portal (Stalker):** portal URL + MAC address + Device ID 1 + Device ID 2 + serial number
  - Stream links are resolved on demand, so they never expire in your player
- **Category filtering:** include / exclude keywords, or pick categories with a checklist
- Sort A-Z, copy the link, or download the `.m3u` file
- Optional `ACCESS_KEY` to protect your deployment
- Nothing is stored; only an in-memory cache is used

## Clean M3U links

Xtream:

    https://<your-app>.onrender.com/<host:port>/<username>/<password>.m3u

If you set the `XTREAM_URL` environment variable (for example `http://host:port`), you get the short form:

    https://<your-app>.onrender.com/<username>/<password>.m3u

With no options, the playlist contains all live channels. Optional query parameters:

| Parameter | Meaning |
|---|---|
| `kind=vod` | Movies instead of live TV |
| `fmt=m3u8` | HLS streams instead of `.ts` |
| `keywords=tamil,sports` | Only categories containing these words |
| `exclude=adult,xxx` | Skip categories containing these words |
| `cats=1,5,9` | Exact category IDs |
| `sort=0` | Disable A-Z sorting |
| `https=1` | Provider uses https |
| `key=...` | Your `ACCESS_KEY`, if set |

Example (Tamil channels only): `https://<your-app>.onrender.com/<username>/<password>.m3u?keywords=tamil`

MAC portal (use the web UI to generate it):

    https://<your-app>.onrender.com/portal.m3u?url=...&mac=...&d1=...&d2=...&sn=...

## Deploy on Render
1. Push this folder to a GitHub repository.
2. In Render choose **New → Blueprint** and select the repository (`render.yaml` configures everything).
3. Set the `ACCESS_KEY` environment variable (recommended, otherwise anyone with your URL can use it).
4. Optionally set `XTREAM_URL` for short links.

Start command: `gunicorn app:app --bind 0.0.0.0:$PORT --timeout 180`

## Run locally

    pip install -r requirements.txt
    python app.py

Then open http://localhost:5000

## Notes
- A generated link contains your credentials. Do not share it publicly.
- Render's free plan sleeps after 15 minutes idle, so the first request can be slow.
- Portal servers differ slightly between providers. If the handshake fails, double-check the URL, MAC, device IDs and serial.
- Use this only with services you have a valid subscription or authorization for.
