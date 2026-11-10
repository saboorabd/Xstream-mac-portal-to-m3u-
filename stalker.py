"""Stalker / MAC portal client.

Shared by the Flask web app (app.py) and the Termux / terminal CLI (cli.py).
Same handshake -> get_profile -> get_all_channels -> create_link flow as a real
MAG250 set-top box, with auto re-login, channel fallback and result caching.
"""
import hashlib
import json
import re
import threading
import time
from collections import Counter
from urllib.parse import urlencode, urlparse

import requests

STB_UA = ("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) "
          "MAG250 stbapp ver: 2 rev: 250 Safari/533.3")
DEFAULT_TIMEOUT = 25
MAX_TOKEN_AGE = 45 * 60      # re-handshake proactively after this many seconds
CACHE_TTL = 10 * 60          # channel / genre list cache
PREFIX_RE = re.compile(r"^(?:ffmpeg|ffrt\d*|auto)\s+", re.I)


class PortalError(Exception):
    """Anything that goes wrong while talking to the portal."""


class AuthError(PortalError):
    """Portal rejected our token (expired / not logged in)."""


def norm_mac(mac):
    """Accept 00:1A:79:.., 00-1a-79-.. or 001A79.. and return AA:BB:CC:DD:EE:FF."""
    h = re.sub(r"[^0-9A-Fa-f]", "", mac or "").upper()
    if len(h) != 12:
        raise PortalError("MAC address format is wrong. Example: 00:1A:79:12:34:56")
    return ":".join(h[i:i + 2] for i in range(0, 12, 2))


def clean_cmd(cmd):
    """'ffmpeg http://x/y.ts' -> 'http://x/y.ts'"""
    cmd = PREFIX_RE.sub("", (cmd or "").strip())
    parts = cmd.split()
    return parts[0] if parts else ""


def _loads(text):
    try:
        return json.loads(text)
    except ValueError:
        pass
    i, j = text.find("{"), text.rfind("}")      # some portals add junk around the JSON
    if 0 <= i < j:
        try:
            return json.loads(text[i:j + 1])
        except ValueError:
            return None
    return None


class Portal:
    def __init__(self, url, mac, d1="", d2="", sn="", timeout=DEFAULT_TIMEOUT):
        url = (url or "").strip()
        if not url:
            raise PortalError("Portal URL is missing")
        if not re.match(r"^https?://", url, re.I):
            url = "http://" + url
        p = urlparse(url)
        if not p.netloc:
            raise PortalError("Portal URL is not valid")
        self.host = f"{p.scheme}://{p.netloc}"
        path = p.path or ""
        direct = None
        if path.lower().endswith(".php"):               # user pasted the full api endpoint
            direct = self.host + path
            path = path.rsplit("/", 1)[0]
        path = re.sub(r"/c(?:/.*)?$", "", path).rstrip("/")   # strip the /c/ web-UI part
        self.base = self.host + path

        eps = [direct] if direct else []
        eps += [self.base + "/server/load.php", self.base + "/portal.php",
                self.host + "/stalker_portal/server/load.php",
                self.host + "/server/load.php", self.host + "/portal.php"]
        self.eps = list(dict.fromkeys(eps))

        self.logo_base = (self.base if "stalker_portal" in path else self.host + "/stalker_portal") \
            + "/misc/logos/320/"

        self.mac = norm_mac(mac)
        self.d1 = (d1 or "").strip() or hashlib.sha256(self.mac.encode()).hexdigest().upper()
        self.d2 = (d2 or "").strip() or self.d1
        self.sn = (sn or "").strip() or hashlib.md5(self.mac.encode()).hexdigest()[:13].upper()
        self.timeout = timeout

        self.s = requests.Session()
        self.s.headers.update({"User-Agent": STB_UA, "Accept": "*/*",
                               "X-User-Agent": "Model: MAG250; Link: WiFi",
                               "Referer": self.base + "/c/"})
        self.s.cookies.update({"mac": self.mac, "stb_lang": "en_US", "timezone": "Asia/Kolkata"})

        self.lock = threading.RLock()
        self._ch_lock = threading.Lock()
        self.token = None
        self.ep = None
        self.random = ""
        self.login_ts = 0.0
        self.profile = None
        self.profile_error = None
        self._ch = None
        self._genres = None

    # ------------------------------------------------------------ low level
    def _raw(self, params):
        q = dict(params)
        q["JsHttpRequest"] = "1-xml"
        headers = {"Authorization": "Bearer " + self.token} if self.token else {}
        try:
            r = self.s.get(self.ep, params=urlencode(q, safe=":/"),
                           headers=headers, timeout=self.timeout)
        except requests.exceptions.RequestException as e:
            raise PortalError(f"Cannot reach portal ({e.__class__.__name__})") from e
        text = (r.text or "").strip()
        if r.status_code in (401, 403) or "authorization failed" in text[:200].lower():
            raise AuthError(f"Portal rejected the session (HTTP {r.status_code})")
        if r.status_code >= 400:
            raise PortalError(f"Portal answered HTTP {r.status_code}")
        data = _loads(text)
        if data is None:
            raise PortalError("Portal sent a non-JSON reply (wrong URL or IP blocked?): "
                              + text[:60].replace("\n", " "))
        return data.get("js") if isinstance(data, dict) else data

    def _profile_params(self):
        return {
            "type": "stb", "action": "get_profile", "hd": "1",
            "ver": "ImageDescription: 0.2.18-r14-pub-250; PORTAL version: 5.5.0; "
                   "API Version: JS API version: 343;",
            "num_banks": "2", "sn": self.sn, "stb_type": "MAG250", "client_type": "STB",
            "image_version": "218", "video_out": "hdmi",
            "device_id": self.d1, "device_id2": self.d2, "signature": "",
            "auth_second_step": "1", "hw_version": "1.7-BD-00", "not_valid_token": "0",
            "metrics": json.dumps({"mac": self.mac, "model": "MAG250", "type": "STB",
                                   "uid": "", "random": self.random}),
            "hw_version_2": hashlib.sha1(self.mac.encode()).hexdigest(),
            "timestamp": str(int(time.time())), "api_signature": "263", "prehash": "",
        }

    # ------------------------------------------------------------ session
    def login(self):
        """Handshake (tries every known endpoint) + get_profile."""
        with self.lock:
            self.token = None
            last = None
            for ep in self.eps:
                self.ep = ep
                try:
                    js = self._raw({"type": "stb", "action": "handshake", "token": ""})
                except PortalError as e:
                    last = e
                    continue
                if isinstance(js, dict) and js.get("token"):
                    self.token = js["token"]
                    self.random = str(js.get("random") or "")
                    break
                last = PortalError("handshake returned no token")
            if not self.token:
                raise PortalError(f"Handshake failed - check portal URL and MAC ({last})")
            try:
                self.profile = self._raw(self._profile_params())
                self.profile_error = None
            except PortalError as e:          # some portals still work without a clean profile
                self.profile_error = str(e)
            self.login_ts = time.time()
            return self

    def call(self, params):
        """API call with automatic (re)login when the token is missing / expired."""
        if not self.token or time.time() - self.login_ts > MAX_TOKEN_AGE:
            with self.lock:
                if not self.token or time.time() - self.login_ts > MAX_TOKEN_AGE:
                    self.login()
        used = self.token
        try:
            return self._raw(params)
        except AuthError:
            with self.lock:
                if self.token == used:        # nobody else refreshed it yet
                    self.login()
            return self._raw(params)

    def account_info(self):
        try:
            js = self.call({"type": "account_info", "action": "get_main_info"})
        except PortalError:
            return {}
        if not isinstance(js, dict):
            return {}
        exp = js.get("end_date") or js.get("expire_billing_date") or js.get("phone") or ""
        return {"expires": str(exp)} if exp else {}

    # ------------------------------------------------------------ data
    def genres(self):
        if self._genres and time.time() - self._genres[0] < CACHE_TTL:
            return self._genres[1]
        try:
            js = self.call({"type": "itv", "action": "get_genres"})
        except PortalError:
            js = []
        if isinstance(js, dict):
            js = js.get("data") or []
        out = {}
        for g in js if isinstance(js, list) else []:
            if isinstance(g, dict) and str(g.get("id")) != "*":
                out[str(g.get("id"))] = str(g.get("title") or "Other")
        self._genres = (time.time(), out)
        return out

    def _ordered_list(self):
        out, page = [], 1
        while page <= 400:
            js = self.call({"type": "itv", "action": "get_ordered_list", "genre": "*",
                            "force_ch_link_check": "", "fav": "0", "sortby": "number",
                            "hd": "0", "p": str(page)})
            rows = js.get("data") if isinstance(js, dict) else None
            if not rows:
                break
            out += rows
            try:
                total = int(js.get("total_items") or 0)
            except (TypeError, ValueError):
                total = 0
            if total and len(out) >= total:
                break
            page += 1
        return out

    def _load_channels(self):
        first_err = None
        rows = []
        try:
            js = self.call({"type": "itv", "action": "get_all_channels"})
            rows = js.get("data") if isinstance(js, dict) else js
        except PortalError as e:
            first_err = e
        rows = [c for c in (rows or []) if isinstance(c, dict) and c.get("cmd")]
        if not rows:                       # portals that disable get_all_channels
            try:
                rows = [c for c in self._ordered_list() if isinstance(c, dict) and c.get("cmd")]
            except PortalError:
                if first_err:
                    raise first_err
                raise
        return rows

    def channels(self, force=False):
        with self._ch_lock:
            if not force and self._ch and time.time() - self._ch[0] < CACHE_TTL:
                return self._ch[1]
            rows = self._load_channels()
            if not rows:                   # maybe a stale session - one fresh try
                self.login()
                rows = self._load_channels()
            if not rows:
                raise PortalError("Portal returned 0 channels - account expired or MAC not active?")
            self._ch = (time.time(), rows)
            return rows

    def logo_url(self, logo):
        logo = (logo or "").strip()
        if not logo:
            return ""
        if logo.startswith("http"):
            return logo
        if logo.startswith("//"):
            return "http:" + logo
        if logo.startswith("/"):
            return self.host + logo
        return self.logo_base + logo

    def items(self):
        """Normalised channel list: name, cmd, logo, group, genre_id, tvg_id, chno."""
        genres = self.genres()
        out = []
        for c in self.channels():
            gid = str(c.get("tv_genre_id"))
            known = gid in genres
            name = str(c.get("name") or "").strip() or "Unknown"
            out.append({
                "id": str(c.get("id") or ""),
                "name": name,
                "cmd": c["cmd"],
                "logo": self.logo_url(c.get("logo")),
                "genre_id": gid if known else "other",
                "group": genres[gid] if known else "Other",
                "tvg_id": str(c.get("xmltv_id") or ""),
                "chno": str(c.get("number") or ""),
            })
        return out

    def categories(self):
        items = self.items()
        counts = Counter(i["genre_id"] for i in items)
        cats = [{"id": gid, "name": t, "count": counts[gid]}
                for gid, t in self.genres().items() if counts.get(gid)]
        if counts.get("other"):
            cats.append({"id": "other", "name": "Other", "count": counts["other"]})
        return cats

    # ------------------------------------------------------------ links
    def link(self, cmd):
        """Resolve a channel 'cmd' into the real stream URL (create_link)."""
        js = self.call({"type": "itv", "action": "create_link", "cmd": cmd, "series": "",
                        "forced_storage": "undefined", "disable_ad": "0", "download": "0"})
        if isinstance(js, dict) and js.get("error"):
            raise PortalError(f"Portal says: {js['error']}")
        url = clean_cmd(js.get("cmd") if isinstance(js, dict) else "") or clean_cmd(cmd)
        if not url or (urlparse(url).hostname or "").lower() == "localhost":
            raise PortalError("Portal gave no playable link (channel blocked, account expired "
                              "or too many devices?)")
        return url
