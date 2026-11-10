"""Xtream Codes & MAC (Stalker) portal -> M3U web app."""
import base64
import hashlib
import hmac
import ipaddress
import json
import logging
import os
import re
import secrets
import socket
import threading
import time
import zlib
from pathlib import Path
from urllib.parse import quote, urljoin, urlparse

import requests
from flask import Flask, Response, abort, jsonify, redirect, request
from werkzeug.exceptions import HTTPException
from werkzeug.middleware.proxy_fix import ProxyFix

from m3u import build_m3u, kw_list, matches
from stalker import STB_UA, Portal, PortalError, norm_mac

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("m3u")

app = Flask(__name__)
app.wsgi_app = ProxyFix(app.wsgi_app, x_proto=1, x_host=1)

ACCESS_KEY = os.environ.get("ACCESS_KEY", "")
XTREAM_URL = os.environ.get("XTREAM_URL", "")        # optional: provider host for short links
DEFAULT_EPG = os.environ.get("DEFAULT_EPG", "")      # optional: XMLTV url added to portal playlists
SECRET = (os.environ.get("SECRET_KEY") or ACCESS_KEY or secrets.token_hex(16)).encode()
TIMEOUT = 40
DEFAULT_KW = ""   # empty = no filter, all categories
INDEX_FILE = Path(__file__).with_name("static") / "index.html"


# ---------------------------------------------------------------- helpers
_cache, _cache_lock = {}, threading.Lock()


def cached(key, fn, ttl=300):
    with _cache_lock:
        v = _cache.get(key)
        if v and time.time() - v[0] < ttl:
            return v[1]
    d = fn()
    with _cache_lock:
        if len(_cache) > 200:                       # keep memory bounded
            for k in sorted(_cache, key=lambda k: _cache[k][0])[:50]:
                _cache.pop(k, None)
        _cache[key] = (time.time(), d)
    return d


def check_key():
    if ACCESS_KEY:
        given = (request.values.get("key") or "").encode()
        if not hmac.compare_digest(given, ACCESS_KEY.encode()):
            abort(401, "Invalid access key")


def guard_url(url):
    """Block private / local addresses so the server can't be used to probe internal networks.
    Set ALLOW_PRIVATE=1 when you run it at home against a portal on your LAN."""
    if os.environ.get("ALLOW_PRIVATE") == "1":
        return
    p = urlparse(url if "://" in url else "http://" + url)
    if p.scheme not in ("http", "https") or not p.hostname:
        abort(400, "Invalid URL")
    try:
        infos = socket.getaddrinfo(p.hostname, p.port or 80, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        abort(400, f"Cannot resolve host {p.hostname}")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0].split("%")[0])
        if not ip.is_global or ip.is_multicast:
            abort(400, "Private / local addresses are not allowed")


def b64u(raw):
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def unb64u(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def m3u_response(text, name):
    h = {"Content-Type": "audio/x-mpegurl; charset=utf-8", "Cache-Control": "no-store"}
    if request.args.get("download") == "1":
        h["Content-Disposition"] = f'attachment; filename="{name}.m3u"'
    return Response(text, headers=h)


def epg_arg(default=None):
    e = (request.args.get("epg") or default or "").strip()
    return e if re.match(r"^https?://", e, re.I) else None


@app.errorhandler(Exception)
def on_error(e):
    if isinstance(e, HTTPException):
        code, msg = e.code, e.description
    elif isinstance(e, PortalError):
        code, msg = 502, str(e)
    elif isinstance(e, requests.exceptions.Timeout):
        code, msg = 504, "Server timed out"
    elif isinstance(e, requests.exceptions.ConnectionError):
        code, msg = 502, "Cannot connect to the server - check the URL"
    elif isinstance(e, requests.exceptions.HTTPError):
        code, msg = 502, f"Server answered HTTP {e.response.status_code if e.response is not None else '?'}"
    elif isinstance(e, ValueError):
        code, msg = 400, "Bad request data"
    else:
        log.exception("Unhandled error")
        code, msg = 500, "Internal error"
    return jsonify(error=msg), code


# ---------------------------------------------------------------- Xtream
def x_base(url):
    url = url.strip()
    if not url.startswith("http"):
        url = "http://" + url
    p = urlparse(url)
    return f"{p.scheme}://{p.netloc}"


def x_api(base, u, p, action):
    def go():
        guard_url(base)
        r = requests.get(base + "/player_api.php",
                         params={"username": u, "password": p, "action": action},
                         headers={"User-Agent": "okhttp/4.9.0"}, timeout=TIMEOUT)
        r.raise_for_status()
        try:
            return r.json()
        except ValueError:
            abort(502, "Provider sent an invalid reply - check URL, username and password")
    return cached(("x", base, u, p, action), go)


KINDS = {"live": ("get_live_categories", "get_live_streams"),
         "vod": ("get_vod_categories", "get_vod_streams")}


def _kind():
    k = request.args.get("kind", "live")
    if k not in KINDS:
        abort(400, "kind must be live or vod")
    return k


def x_args():
    a = request.args
    for k in ("url", "username", "password"):
        if not a.get(k):
            abort(400, f"Missing {k}")
    return x_base(a["url"]), a["username"], a["password"], _kind()


@app.get("/api/xtream/categories")
def xtream_categories():
    check_key()
    base, u, p, kind = x_args()
    inc = kw_list(request.args.get("keywords"), DEFAULT_KW)
    exc = kw_list(request.args.get("exclude"))
    cats = x_api(base, u, p, KINDS[kind][0])
    if not isinstance(cats, list):
        abort(401, "Login failed or no categories returned")
    return jsonify([{"id": str(c.get("category_id")), "name": c.get("category_name") or "",
                     "matched": matches(c.get("category_name"), inc, exc)} for c in cats])


def x_playlist(base, u, p, kind):
    a = request.args
    inc = kw_list(a.get("keywords"), DEFAULT_KW)
    exc = kw_list(a.get("exclude"))
    fmt = a.get("fmt", "ts")
    if fmt not in ("ts", "m3u8"):
        abort(400, "fmt must be ts or m3u8")
    cats = x_api(base, u, p, KINDS[kind][0])
    if not isinstance(cats, list):
        abort(401, "Login failed - check URL, username and password")
    chosen = set(c for c in a.get("cats", "").split(",") if c)
    names = {}
    for c in cats:
        cid = str(c.get("category_id"))
        if (cid in chosen) if chosen else matches(c.get("category_name"), inc, exc):
            names[cid] = c.get("category_name") or "Other"
    streams = x_api(base, u, p, KINDS[kind][1])
    if not isinstance(streams, list):
        abort(502, "Provider returned no stream list")
    qu, qp = quote(u, safe=""), quote(p, safe="")
    items = []
    for s in streams:
        cid = str(s.get("category_id"))
        if cid not in names or "stream_id" not in s:
            continue
        sid = s["stream_id"]
        if kind == "live":
            url = f"{base}/live/{qu}/{qp}/{sid}.{fmt}"
        else:
            url = f"{base}/movie/{qu}/{qp}/{sid}.{s.get('container_extension') or 'mp4'}"
        items.append({"name": s.get("name") or "Unknown", "logo": s.get("stream_icon"),
                      "group": names[cid], "tvg_id": s.get("epg_channel_id"),
                      "chno": s.get("num") if kind == "live" else "", "url": url})
    if a.get("sort", "1") == "1":
        items.sort(key=lambda i: (i["group"].lower(), i["name"].lower()))
    epg = (epg_arg() or f"{base}/xmltv.php?username={qu}&password={qp}") if kind == "live" else None
    return m3u_response(build_m3u(items, epg), f"xtream_{kind}")


@app.get("/xtream.m3u")
def xtream_m3u():
    check_key()
    base, u, p, kind = x_args()
    return x_playlist(base, u, p, kind)


# Clean links:  https://<app>/<username>/<password>.m3u   (XTREAM_URL env must be set)
@app.get("/<user>/<pw>.m3u")
def short_m3u(user, pw):
    check_key()
    if not XTREAM_URL:
        abort(404, "Set the XTREAM_URL env var, or use /host:port/user/pass.m3u")
    return x_playlist(x_base(XTREAM_URL), user, pw, _kind())


# Clean links:  https://<app>/<host:port>/<username>/<password>.m3u
@app.get("/<host>/<user>/<pw>.m3u")
def full_m3u(host, user, pw):
    check_key()
    scheme = "https" if request.args.get("https") == "1" else "http"
    return x_playlist(x_base(f"{scheme}://{host}"), user, pw, _kind())


# ---------------------------------------------------------------- Stalker / MAC portal
CFG_KEYS = ("url", "mac", "d1", "d2", "sn")


def encode_cfg(cfg):
    return b64u(zlib.compress(json.dumps(cfg, separators=(",", ":")).encode(), 9))


def decode_cfg(s):
    raw = unb64u(s)
    try:
        raw = zlib.decompress(raw)
    except zlib.error:          # old links: plain base64 JSON
        pass
    return json.loads(raw.decode())


def p_cfg():
    a = request.args
    if a.get("cfg"):
        try:
            raw = decode_cfg(a["cfg"])
            if not isinstance(raw, dict):
                raise ValueError
        except Exception:
            abort(400, "Invalid cfg")
    else:
        raw = {k: a.get(k, "") for k in CFG_KEYS}
    cfg = {k: str(raw.get(k) or "").strip() for k in CFG_KEYS}
    if not cfg["url"] or not cfg["mac"]:
        abort(400, "Portal URL and MAC required")
    try:
        cfg["mac"] = norm_mac(cfg["mac"])
    except PortalError as e:
        abort(400, str(e))
    guard_url(cfg["url"])
    return cfg


_pool, _pool_lock = {}, threading.Lock()


def get_portal(cfg):
    """One Portal object (= one token) per login, shared by every request."""
    key = hashlib.sha256(json.dumps(cfg, sort_keys=True).encode()).hexdigest()
    with _pool_lock:
        ent = _pool.get(key)
        if ent:
            ent[0] = time.time()
            return ent[1]
        try:
            portal = Portal(cfg["url"], cfg["mac"], cfg["d1"], cfg["d2"], cfg["sn"], timeout=TIMEOUT)
        except PortalError as e:
            abort(400, str(e))
        if len(_pool) >= 30:
            _pool.pop(min(_pool, key=lambda k: _pool[k][0]), None)
        _pool[key] = [time.time(), portal]
        return portal


@app.get("/api/portal/test")
def portal_test():
    check_key()
    portal = get_portal(p_cfg())
    portal.login()
    items = portal.items()
    info = {"ok": True, "endpoint": portal.ep, "channels": len(items),
            "categories": len(portal.categories())}
    if portal.profile_error:
        info["profile_warning"] = portal.profile_error
    info.update(portal.account_info())
    try:                                      # prove that link creation works too
        link = portal.link(items[0]["cmd"])
        info["sample_channel"] = items[0]["name"]
        info["sample_link_host"] = urlparse(link).netloc
    except PortalError as e:
        info["sample_error"] = str(e)
    return jsonify(info)


@app.get("/api/portal/categories")
def portal_categories():
    check_key()
    inc = kw_list(request.args.get("keywords"), DEFAULT_KW)
    exc = kw_list(request.args.get("exclude"))
    cats = get_portal(p_cfg()).categories()
    for c in cats:
        c["matched"] = matches(c["name"], inc, exc)
    return jsonify(cats)


@app.get("/portal.m3u")
def portal_m3u():
    check_key()
    cfg = p_cfg()
    a = request.args
    inc = kw_list(a.get("keywords"), DEFAULT_KW)
    exc = kw_list(a.get("exclude"))
    chosen = set(c for c in a.get("cats", "").split(",") if c)
    portal = get_portal(cfg)
    token = encode_cfg(cfg)
    origin = request.host_url.rstrip("/")
    tail = ("&proxy=1" if a.get("proxy") == "1" else "") + \
           (f"&key={quote(ACCESS_KEY, safe='')}" if ACCESS_KEY else "")
    items = []
    for c in portal.items():
        if (c["genre_id"] in chosen) if chosen else matches(c["group"], inc, exc):
            c["url"] = f"{origin}/portal/play?cfg={token}&cmd={quote(c['cmd'], safe='')}{tail}"
            items.append(c)
    if a.get("sort") == "1":
        items.sort(key=lambda i: (i["group"].lower(), i["name"].lower()))
    return m3u_response(build_m3u(items, epg_arg(DEFAULT_EPG)), "portal")


@app.get("/portal/play")
def portal_play():
    check_key()
    cfg = p_cfg()
    cmd = request.args.get("cmd", "")
    if not cmd:
        abort(400, "cmd missing")
    portal = get_portal(cfg)
    try:
        url = portal.link(cmd)
    except PortalError:                       # fresh handshake, then one more try
        portal.login()
        url = portal.link(cmd)
    if request.args.get("proxy") == "1":
        if request.method == "HEAD":
            return Response(status=200, content_type="video/mp2t")
        return proxy_response(url)
    return redirect(url, 302)


# ---------------------------------------------------------------- stream proxy (proxy=1)
# Many portals bind the stream token to the IP that called create_link. When this server
# (not your phone) resolves the link, a plain redirect then fails - so in proxy mode the
# server also fetches the stream and relays it. HLS playlists are rewritten so segments
# go through the proxy as well (signed links, so it is not an open proxy).
UP_HEADERS = {"User-Agent": STB_UA, "Accept": "*/*"}


def sign(url):
    return hmac.new(SECRET, url.encode(), hashlib.sha256).hexdigest()[:32]


def seg_url(url, origin):
    return f"{origin}/portal/seg?u={b64u(url.encode())}&s={sign(url)}"


def open_upstream(url, hops=5):
    for _ in range(hops):
        guard_url(url)
        r = requests.get(url, headers=UP_HEADERS, stream=True, timeout=(10, 30), allow_redirects=False)
        if r.status_code in (301, 302, 303, 307, 308) and r.headers.get("Location"):
            url = urljoin(url, r.headers["Location"])
            r.close()
            continue
        return r, url
    abort(502, "Too many redirects from the stream server")


def rewrite_hls(text, base_url, origin):
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            out.append(line)
        elif s.startswith("#"):
            out.append(re.sub(r'URI="([^"]+)"',
                              lambda m: 'URI="%s"' % seg_url(urljoin(base_url, m.group(1)), origin), line))
        else:
            out.append(seg_url(urljoin(base_url, s), origin))
    return "\n".join(out) + "\n"


def proxy_response(url):
    r, final = open_upstream(url)
    if r.status_code >= 400:
        code = r.status_code
        r.close()
        abort(502, f"Stream server answered HTTP {code}")
    ctype = r.headers.get("Content-Type", "")
    if "mpegurl" in ctype.lower() or urlparse(final).path.lower().endswith(".m3u8"):
        try:
            body = b"".join(r.iter_content(65536))[:5_000_000]
        finally:
            r.close()
        text = rewrite_hls(body.decode("utf-8", "ignore"), final, request.host_url.rstrip("/"))
        return Response(text, content_type="application/vnd.apple.mpegurl",
                        headers={"Cache-Control": "no-store"})

    def gen():
        try:
            for chunk in r.iter_content(64 * 1024):
                if chunk:
                    yield chunk
        finally:
            r.close()
    return Response(gen(), content_type=ctype or "video/mp2t", headers={"Cache-Control": "no-store"})


@app.get("/portal/seg")
def portal_seg():
    try:
        url = unb64u(request.args.get("u", "")).decode()
    except Exception:
        abort(400, "Bad segment link")
    if not hmac.compare_digest(sign(url), request.args.get("s", "")):
        abort(403, "Bad signature")
    return proxy_response(url)


# ---------------------------------------------------------------- UI
@app.get("/")
def index():
    return Response(INDEX_FILE.read_text(encoding="utf-8"), mimetype="text/html")


@app.get("/api/config")
def config():
    return jsonify(key_required=bool(ACCESS_KEY), xtream_url=bool(XTREAM_URL))


@app.get("/healthz")
def health():
    return "ok"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)),
            debug=os.environ.get("FLASK_DEBUG") == "1")
