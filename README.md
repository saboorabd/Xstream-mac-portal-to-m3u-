# Tamil IPTV Filter

Xtream Codes & MAC (Stalker) portal-la irunthu **Tamil category mattum** filter panni, correct M3U generate pannum.

## Features
- Xtream: URL + Username + Password (Live & VOD, .ts / .m3u8, EPG url auto)
- MAC Portal: URL + MAC + Device ID 1 & 2 + Serial (links on-demand resolve aagum, so expire aagathu)
- Category picker, include / exclude keywords, A-Z sort, copy link / download .m3u
- Optional `ACCESS_KEY` protection
- Nothing is stored on the server (only in-memory cache)

## Clean M3U links
    https://<your-app>.onrender.com/<host:port>/<username>/<password>.m3u
Env var `XTREAM_URL=http://host:port` set pannunga na short link:

    https://<your-app>.onrender.com/<username>/<password>.m3u

Default-aa Tamil live channels mattum. Options (optional): `?kind=vod`, `?fmt=m3u8`, `?exclude=xxx`, `?keywords=tamil,sun`, `?https=1`, `?key=ACCESS_KEY`.

## Deploy on Render
1. Intha folder-a GitHub repo-va push pannunga.
2. Render -> New -> Blueprint (or Web Service) -> repo select pannunga.
3. Start command: `gunicorn app:app --bind 0.0.0.0:$PORT --timeout 180`
4. Env var `ACCESS_KEY` set pannunga (illati yaarum use panna mudiyum).

## Local run
    pip install -r requirements.txt && python app.py

## Notes
- Playlist link-la un credentials irukkum - link-a share pannadheenga.
- Render free plan 15 min idle-na sleep aagum, first request slow-a irukkum.
- Nee own subscription / valid access ulla services-ku mattum use pannunga.
