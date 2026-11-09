import os, re, json, time, base64, hashlib
from urllib.parse import urlparse, quote
import requests
from flask import Flask, request, jsonify, Response, redirect, abort
from werkzeug.middleware.proxy_fix import ProxyFix

app = Flask(__name__)
app.wsgi_app = ProxyFix(app.wsgi_app, x_proto=1, x_host=1)

ACCESS_KEY = os.environ.get("ACCESS_KEY", "")
XTREAM_URL = os.environ.get("XTREAM_URL", "")   # optional: provider host for short links
TIMEOUT = 40
DEFAULT_KW = ""  # empty = no filter, all categories
STB_UA = ("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) "
          "MAG200 stbapp ver: 2 rev: 250 Safari/533.3")

# ---------------------------------------------------------------- helpers
_cache = {}
def cached(key, fn, ttl=300):
    v = _cache.get(key)
    if v and time.time() - v[0] < ttl:
        return v[1]
    d = fn()
    _cache[key] = (time.time(), d)
    return d

def check_key():
    if ACCESS_KEY and request.values.get("key") != ACCESS_KEY:
        abort(401, "Invalid access key")

def kw_list(s, default=""):
    return [k.strip().lower() for k in (s or default).split(",") if k.strip()]

def matches(name, inc, exc):
    n = (name or "").lower()
    return (not inc or any(k in n for k in inc)) and not any(k in n for k in exc)

def esc(s):
    return str(s or "").replace('"', "'").replace("\n", " ").replace("\r", " ")

def build_m3u(items, epg=None):
    out = ['#EXTM3U' + (f' x-tvg-url="{esc(epg)}"' if epg else "")]
    for i in items:
        out.append(f'#EXTINF:-1 tvg-id="{esc(i.get("tvg_id"))}" tvg-name="{esc(i["name"])}" '
                   f'tvg-logo="{esc(i.get("logo"))}" group-title="{esc(i.get("group"))}",{esc(i["name"])}')
        out.append(i["url"])
    return "\n".join(out) + "\n"

def m3u_response(text, name):
    h = {"Content-Type": "audio/x-mpegurl; charset=utf-8"}
    if request.args.get("download") == "1":
        h["Content-Disposition"] = f'attachment; filename="{name}.m3u"'
    return Response(text, headers=h)

@app.errorhandler(Exception)
def on_error(e):
    code = getattr(e, "code", 500)
    msg = getattr(e, "description", None) or str(e)
    return jsonify(error=msg), (code if isinstance(code, int) else 500)

# ---------------------------------------------------------------- Xtream
def x_base(url):
    url = url.strip()
    if not url.startswith("http"):
        url = "http://" + url
    p = urlparse(url)
    return f"{p.scheme}://{p.netloc}"

def x_api(base, u, p, action):
    def go():
        r = requests.get(base + "/player_api.php",
                         params={"username": u, "password": p, "action": action},
                         headers={"User-Agent": "okhttp/4.9.0"}, timeout=TIMEOUT)
        r.raise_for_status()
        return r.json()
    return cached(("x", base, u, p, action), go)

KINDS = {"live": ("get_live_categories", "get_live_streams"),
         "vod": ("get_vod_categories", "get_vod_streams")}

def x_args():
    a = request.args
    for k in ("url", "username", "password"):
        if not a.get(k):
            abort(400, f"Missing {k}")
    kind = a.get("kind", "live")
    if kind not in KINDS:
        abort(400, "kind must be live or vod")
    return x_base(a["url"]), a["username"], a["password"], kind

@app.get("/api/xtream/categories")
def xtream_categories():
    check_key()
    base, u, p, kind = x_args()
    inc = kw_list(request.args.get("keywords"), DEFAULT_KW)
    exc = kw_list(request.args.get("exclude"))
    cats = x_api(base, u, p, KINDS[kind][0])
    if not isinstance(cats, list):
        abort(401, "Login failed or no categories returned")
    return jsonify([{"id": str(c["category_id"]), "name": c["category_name"],
                     "matched": matches(c["category_name"], inc, exc)} for c in cats])

def x_playlist(base, u, p, kind):
    a = request.args
    inc = kw_list(a.get("keywords"), DEFAULT_KW)
    exc = kw_list(a.get("exclude"))
    fmt = a.get("fmt", "ts")
    cats = x_api(base, u, p, KINDS[kind][0])
    if not isinstance(cats, list):
        abort(401, "Login failed - check URL, username and password")
    chosen = set(c for c in a.get("cats", "").split(",") if c)
    names = {}
    for c in cats:
        cid = str(c["category_id"])
        if (cid in chosen) if chosen else matches(c["category_name"], inc, exc):
            names[cid] = c["category_name"]
    items = []
    for s in x_api(base, u, p, KINDS[kind][1]):
        cid = str(s.get("category_id"))
        if cid not in names:
            continue
        sid = s["stream_id"]
        if kind == "live":
            url = f"{base}/live/{u}/{p}/{sid}.{fmt}"
        else:
            url = f"{base}/movie/{u}/{p}/{sid}.{s.get('container_extension') or 'mp4'}"
        items.append({"name": s.get("name"), "logo": s.get("stream_icon"),
                      "group": names[cid], "tvg_id": s.get("epg_channel_id"), "url": url})
    if a.get("sort", "1") == "1":
        items.sort(key=lambda i: (i["group"].lower(), (i["name"] or "").lower()))
    epg = f"{base}/xmltv.php?username={u}&password={p}" if kind == "live" else None
    return m3u_response(build_m3u(items, epg), f"xtream_{kind}")

def _kind():
    k = request.args.get("kind", "live")
    if k not in KINDS:
        abort(400, "kind must be live or vod")
    return k

@app.get("/xtream.m3u")
def xtream_m3u():
    check_key()
    base, u, p, kind = x_args()
    return x_playlist(base, u, p, kind)

# Clean links:  https://<app>/<username>/<password>.m3u   (XTREAM_URL env set irukkanum)
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
class Portal:
    def __init__(self, url, mac, d1, d2, sn):
        url = url.strip()
        if not url.startswith("http"):
            url = "http://" + url
        p = urlparse(url)
        host = f"{p.scheme}://{p.netloc}"
        eps = []
        if "stalker_portal" in p.path:
            eps.append(host + "/stalker_portal/server/load.php")
        eps += [host + "/portal.php", host + "/server/load.php",
                host + "/stalker_portal/server/load.php"]
        self.eps = list(dict.fromkeys(eps))
        self.mac, self.d1, self.d2, self.sn = mac.strip(), d1 or "", d2 or "", sn or ""
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": STB_UA, "Accept": "*/*",
                               "X-User-Agent": "Model: MAG250; Link: WiFi"})
        self.s.cookies.update({"mac": self.mac, "stb_lang": "en", "timezone": "Asia/Kolkata"})
        self.token = None
        self.ep = None

    def _get(self, params):
        h = {"Authorization": "Bearer " + self.token} if self.token else {}
        r = self.s.get(self.ep, params={**params, "JsHttpRequest": "1-xml"},
                       headers=h, timeout=TIMEOUT)
        r.raise_for_status()
        return json.loads(r.text.strip()).get("js")

    def login(self):
        last = None
        for ep in self.eps:
            self.ep, self.token = ep, None
            try:
                js = self._get({"type": "stb", "action": "handshake", "token": ""})
                if isinstance(js, dict) and js.get("token"):
                    self.token = js["token"]
                    break
            except Exception as e:
                last = e
        if not self.token:
            raise RuntimeError(f"Portal handshake failed ({last}). Check the portal URL and MAC address.")
        self._get({
            "type": "stb", "action": "get_profile", "hd": "1", "num_banks": "2",
            "sn": self.sn, "stb_type": "MAG250", "client_type": "STB",
            "image_version": "218", "video_out": "hdmi",
            "device_id": self.d1, "device_id2": self.d2, "signature": "",
            "auth_second_step": "1", "hw_version": "1.7-BD-00", "not_valid_token": "0",
            "metrics": json.dumps({"mac": self.mac, "model": "MAG250", "type": "STB",
                                   "uid": "", "random": ""}),
            "hw_version_2": hashlib.sha1(self.mac.encode()).hexdigest(),
            "timestamp": str(int(time.time())), "api_signature": "262", "prehash": ""})
        return self

    def genres(self):
        return self._get({"type": "itv", "action": "get_genres"}) or []

    def channels(self):
        js = self._get({"type": "itv", "action": "get_all_channels"}) or {}
        return js.get("data", []) if isinstance(js, dict) else []

    def link(self, cmd):
        js = self._get({"type": "itv", "action": "create_link", "cmd": cmd, "series": "",
                        "forced_storage": "", "disable_ad": "0", "download": "0",
                        "force_ch_link_check": "0"})
        out = (js or {}).get("cmd", "")
        out = re.sub(r"^(ffmpeg|ffrt\d*|auto)\s+", "", out.strip())
        return out.split(" ")[0]

def p_cfg():
    a = request.args
    if a.get("cfg"):
        return json.loads(base64.urlsafe_b64decode(a["cfg"]).decode())
    cfg = {"url": a.get("url", ""), "mac": a.get("mac", ""), "d1": a.get("d1", ""),
           "d2": a.get("d2", ""), "sn": a.get("sn", "")}
    if not cfg["url"] or not cfg["mac"]:
        abort(400, "Portal URL and MAC required")
    return cfg

def get_portal(cfg, fresh=False):
    key = ("p", json.dumps(cfg, sort_keys=True))
    if fresh:
        _cache.pop(key, None)
    return cached(key, lambda: Portal(cfg["url"], cfg["mac"], cfg["d1"], cfg["d2"], cfg["sn"]).login(), ttl=600)

@app.get("/api/portal/categories")
def portal_categories():
    check_key()
    cfg = p_cfg()
    inc = kw_list(request.args.get("keywords"), DEFAULT_KW)
    exc = kw_list(request.args.get("exclude"))
    gs = get_portal(cfg).genres()
    return jsonify([{"id": str(g["id"]), "name": g["title"],
                     "matched": matches(g["title"], inc, exc)} for g in gs if g.get("id") != "*"])

@app.get("/portal.m3u")
def portal_m3u():
    check_key()
    cfg = p_cfg()
    a = request.args
    inc = kw_list(a.get("keywords"), DEFAULT_KW)
    exc = kw_list(a.get("exclude"))
    portal = get_portal(cfg)
    genres = {str(g["id"]): g["title"] for g in portal.genres()}
    chosen = set(c for c in a.get("cats", "").split(",") if c)
    allowed = {gid: t for gid, t in genres.items()
               if ((gid in chosen) if chosen else matches(t, inc, exc))}
    b64 = base64.urlsafe_b64encode(json.dumps(cfg).encode()).decode()
    origin = request.host_url.rstrip("/")
    items = []
    for c in portal.channels():
        gid = str(c.get("tv_genre_id"))
        if gid not in allowed:
            continue
        play = f"{origin}/portal/play?cfg={b64}&cmd={quote(c['cmd'], safe='')}"
        if ACCESS_KEY:
            play += f"&key={quote(ACCESS_KEY)}"
        logo = c.get("logo") or ""
        items.append({"name": c.get("name"), "logo": logo, "group": allowed[gid],
                      "tvg_id": c.get("xmltv_id"), "url": play})
    if a.get("sort") == "1":
        items.sort(key=lambda i: (i["group"].lower(), (i["name"] or "").lower()))
    return m3u_response(build_m3u(items), "portal")

@app.get("/portal/play")
def portal_play():
    check_key()
    cfg = p_cfg()
    cmd = request.args["cmd"]
    try:
        url = get_portal(cfg).link(cmd)
    except Exception:
        url = get_portal(cfg, fresh=True).link(cmd)
    if not url:
        abort(502, "Portal returned no link")
    return redirect(url, 302)

# ---------------------------------------------------------------- UI
HTML = r"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Xtream &amp; MAC to M3U</title>
<style>
:root{--bg:#fff;--fg:#1a1a1a;--card:#f4f4f6;--bd:#d0d0d6;--ac:#6d28d9}
@media(prefers-color-scheme:dark){:root{--bg:#111;--fg:#eee;--card:#1c1c20;--bd:#333;--ac:#a78bfa}}
body{margin:0;padding:14px;background:var(--bg);color:var(--fg);font:15px system-ui,sans-serif;max-width:640px;margin:auto}
h1{font-size:20px}.tabs{display:flex;gap:8px;margin-bottom:10px}
.tabs button{flex:1}button,input,select{font:inherit;padding:9px;border-radius:8px;border:1px solid var(--bd);background:var(--card);color:var(--fg);width:100%;box-sizing:border-box;margin:4px 0}
button{cursor:pointer}button.on,button.pri{background:var(--ac);color:#fff;border-color:var(--ac)}
.row{display:flex;gap:8px}.row>*{flex:1}label.c{display:flex;gap:8px;align-items:center;padding:4px 0}
label.c input{width:auto;margin:0}#cats{max-height:260px;overflow:auto;border:1px solid var(--bd);border-radius:8px;padding:6px 10px;margin:6px 0}
#msg{font-size:13px;opacity:.85;word-break:break-all}.hide{display:none}
</style></head><body>
<h1>🎬 Xtream &amp; MAC to M3U</h1>
<div class="tabs"><button id="tx" class="on" onclick="mode('xtream')">Xtream Code</button>
<button id="tp" onclick="mode('portal')">MAC Portal</button></div>
<div id="key" class="hide"><input id="k" placeholder="Access key"></div>
<div id="xtream">
<input id="url" placeholder="Server URL (http://host:port)">
<div class="row"><input id="username" placeholder="Username"><input id="password" placeholder="Password"></div>
<div class="row"><select id="kind"><option value="live">Live TV</option><option value="vod">Movies (VOD)</option></select>
<select id="fmt"><option value="ts">.ts</option><option value="m3u8">.m3u8</option></select></div></div>
<div id="portal" class="hide">
<input id="purl" placeholder="Portal URL (http://host/c/)"><input id="mac" placeholder="MAC address 00:1A:79:xx:xx:xx">
<div class="row"><input id="d1" placeholder="Device ID 1"><input id="d2" placeholder="Device ID 2"></div>
<input id="sn" placeholder="Serial number"></div>
<input id="keywords" placeholder="Include keywords (optional), e.g. tamil,sports">
<input id="exclude" placeholder="Exclude keywords (optional), e.g. adult,xxx">
<label class="c"><input type="checkbox" id="sort" checked> Sort A-Z</label>
<button class="pri" onclick="load()">1. Load categories</button>
<div id="cats" class="hide"></div>
<button class="pri" onclick="gen()">2. Create M3U link</button>
<input id="out" readonly placeholder="Your M3U link appears here" onclick="this.select()">
<div class="row"><button onclick="cp()">Copy</button><button onclick="dl()">Download .m3u</button></div>
<div id="msg"></div>
<script>
let M='xtream';const $=i=>document.getElementById(i);
if(location.search.includes('askkey')||false)$('key').classList.remove('hide');
let CFG={};fetch('/api/config').then(r=>r.json()).then(c=>{CFG=c;if(c.key_required)$('key').classList.remove('hide')});
function mode(m){M=m;$('xtream').classList.toggle('hide',m!='xtream');$('portal').classList.toggle('hide',m!='portal');
$('tx').className=m=='xtream'?'on':'';$('tp').className=m=='portal'?'on':'';$('cats').classList.add('hide')}
function qs(){const q=new URLSearchParams();
if(M=='xtream'){q.set('url',$('url').value);q.set('username',$('username').value);q.set('password',$('password').value);q.set('kind',$('kind').value);q.set('fmt',$('fmt').value)}
else{q.set('url',$('purl').value);q.set('mac',$('mac').value);q.set('d1',$('d1').value);q.set('d2',$('d2').value);q.set('sn',$('sn').value)}
q.set('keywords',$('keywords').value);q.set('exclude',$('exclude').value);if($('sort').checked)q.set('sort','1');
if($('k').value)q.set('key',$('k').value);return q}
async function load(){$('msg').textContent='Loading…';
try{const r=await fetch('/api/'+M+'/categories?'+qs());const d=await r.json();if(!r.ok)throw d.error;
$('cats').innerHTML=d.map(c=>`<label class="c"><input type="checkbox" value="${c.id}" data-m="${c.matched?1:0}" ${c.matched?'checked':''}> ${c.name.replace(/</g,'&lt;')}</label>`).join('');
$('cats').classList.remove('hide');$('msg').textContent=d.filter(c=>c.matched).length+' / '+d.length+' categories matched'}
catch(e){$('msg').textContent='❌ '+e}}
function link(download){
const boxes=[...document.querySelectorAll('#cats input')];const shown=!$('cats').classList.contains('hide');
const ids=boxes.filter(b=>b.checked).map(b=>b.value).join(',');const def=boxes.filter(b=>b.dataset.m=='1').map(b=>b.value).join(',');
if(M=='portal'){const q=qs();if(shown)q.set('cats',ids);if(download)q.set('download','1');return location.origin+'/portal.m3u?'+q}
const u=$('username').value,p=$('password').value;let h=$('url').value.trim();const https=h.startsWith('https');
h=h.replace(/^https?:\/\//,'').replace(/\/.*$/,'');const q=new URLSearchParams();
if($('kind').value!='live')q.set('kind',$('kind').value);if($('fmt').value!='ts')q.set('fmt',$('fmt').value);
if($('keywords').value)q.set('keywords',$('keywords').value);if($('exclude').value)q.set('exclude',$('exclude').value);
if(shown&&ids!=def)q.set('cats',ids);if(!$('sort').checked)q.set('sort','0');if(https)q.set('https','1');
if($('k').value)q.set('key',$('k').value);if(download)q.set('download','1');
const path=(CFG.xtream_url?'':encodeURIComponent(h)+'/')+encodeURIComponent(u)+'/'+encodeURIComponent(p)+'.m3u';
const s=q.toString();return location.origin+'/'+path+(s?'?'+s:'')}
function gen(){$('out').value=link();$('msg').textContent='Link ready ✅ Paste it into your IPTV player'}
function cp(){gen();navigator.clipboard.writeText($('out').value);$('msg').textContent='Copied ✅'}
function dl(){location.href=link(true)}
</script></body></html>"""

@app.get("/")
def index():
    return Response(HTML, mimetype="text/html")

@app.get("/api/config")
def config():
    return jsonify(key_required=bool(ACCESS_KEY), xtream_url=bool(XTREAM_URL))

@app.get("/healthz")
def health():
    return "ok"

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)), debug=True)
