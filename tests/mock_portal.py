"""Fake Stalker portal + fake Xtream server used by the tests (no real network needed)."""
import json
import secrets
import threading

from flask import Flask, Response, jsonify, request
from werkzeug.serving import make_server

VALID_MAC = "00:1A:79:00:00:01"
GENRES = [{"id": "*", "title": "All"}, {"id": "1", "title": "Tamil"},
          {"id": "2", "title": "Sports"}, {"id": "3", "title": "Adult XXX"}]
CHANNELS = [
    {"id": "1", "name": "Sun TV", "cmd": "ffrt http://localhost/ch/1", "tv_genre_id": "1",
     "logo": "1.png", "xmltv_id": "SunTV", "number": "1"},
    {"id": "2", "name": "Star Sports, HD", "cmd": "ffmpeg http://localhost/ch/2", "tv_genre_id": "2",
     "logo": "http://cdn.example/x.png", "xmltv_id": "", "number": "2"},
    {"id": "3", "name": "Adult One", "cmd": "ffrt http://localhost/ch/3", "tv_genre_id": "3", "logo": ""},
    {"id": "4", "name": "Mystery", "cmd": "ffrt http://localhost/ch/4", "tv_genre_id": "99", "logo": ""},
    {"id": "5", "name": "HLS News", "cmd": "ffrt http://localhost/ch/5", "tv_genre_id": "1", "logo": ""},
]


class State:
    def __init__(self):
        self.reset()

    def reset(self):
        self.tokens = set()
        self.handshakes = 0
        self.log = []
        self.use_ordered_list = False    # get_all_channels returns nothing -> paginated list
        self.port = 0


S = State()
app = Flask("mock")


def js(obj):
    return Response(json.dumps({"js": obj}), content_type="text/javascript; charset=utf-8")


def authed():
    auth = request.headers.get("Authorization", "")
    return auth.startswith("Bearer ") and auth[7:] in S.tokens


@app.route("/stalker_portal/server/load.php")
def load():
    t, a = request.args.get("type"), request.args.get("action")
    S.log.append({"type": t, "action": a, "ua": request.headers.get("User-Agent"),
                  "referer": request.headers.get("Referer"), "mac": request.cookies.get("mac"),
                  "args": request.args.to_dict()})
    if request.cookies.get("mac") != VALID_MAC:
        return Response("Authorization failed.", content_type="text/html")
    if (t, a) == ("stb", "handshake"):
        tok = secrets.token_hex(8)
        S.tokens.add(tok)
        S.handshakes += 1
        # junk around the JSON on purpose: real portals sometimes do this
        return Response("\n" + json.dumps({"js": {"token": tok, "random": "r4nd"}}) + "\n",
                        content_type="text/html")
    if not authed():
        return Response("Authorization failed.", content_type="text/html")
    if (t, a) == ("stb", "get_profile"):
        return js({"id": 1, "name": "x"})
    if (t, a) == ("account_info", "get_main_info"):
        return js({"mac": VALID_MAC, "phone": "2027-01-31"})
    if (t, a) == ("itv", "get_genres"):
        return js(GENRES)
    if (t, a) == ("itv", "get_all_channels"):
        return js({"total_items": 0 if S.use_ordered_list else len(CHANNELS),
                   "data": [] if S.use_ordered_list else CHANNELS})
    if (t, a) == ("itv", "get_ordered_list"):
        p = int(request.args.get("p", 1))
        rows = CHANNELS[(p - 1) * 2: p * 2]
        return js({"total_items": len(CHANNELS), "max_page_items": 2, "data": rows})
    if (t, a) == ("itv", "create_link"):
        cmd = request.args.get("cmd", "")
        cid = cmd.rstrip("/").split("/")[-1]
        if cid == "5":
            return js({"cmd": f"ffmpeg http://127.0.0.1:{S.port}/hls/5.m3u8?t=1"})
        return js({"cmd": f"ffmpeg http://127.0.0.1:{S.port}/stream/{cid}.ts?token=abc"})
    return js([])


@app.route("/expire")
def expire():
    S.tokens.clear()
    return "ok"


@app.route("/stream/<cid>.ts")
def stream(cid):
    def gen():
        for i in range(3):
            yield f"TS{cid}-{i};".encode()
    return Response(gen(), content_type="video/mp2t")


@app.route("/hls/5.m3u8")
def hls():
    base = f"http://127.0.0.1:{S.port}"
    txt = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n"
           '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n'
           "#EXTINF:4,\nseg1.ts\n#EXTINF:4,\n" + base + "/hls/seg2.ts\n")
    return Response(txt, content_type="application/vnd.apple.mpegurl")


@app.route("/hls/<name>")
def hls_file(name):
    return Response(b"DATA:" + name.encode(), content_type="application/octet-stream")


# ---- fake Xtream Codes
@app.route("/player_api.php")
def xtream():
    if (request.args.get("username"), request.args.get("password")) != ("user", "pass"):
        return jsonify({"user_info": {"auth": 0}})
    act = request.args.get("action")
    if act == "get_live_categories":
        return jsonify([{"category_id": "10", "category_name": "Tamil Movies"},
                        {"category_id": "11", "category_name": "Adult"}])
    if act == "get_live_streams":
        return jsonify([{"stream_id": 100, "name": "Chn A", "category_id": "10", "num": 1,
                         "stream_icon": "http://x/a.png", "epg_channel_id": "a.in"},
                        {"stream_id": 101, "name": "Chn B", "category_id": "11", "num": 2}])
    return jsonify([])


class Server:
    def start(self):
        self.srv = make_server("127.0.0.1", 0, app, threaded=True)
        S.port = self.srv.server_port
        self.t = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.t.start()
        return S.port

    def stop(self):
        self.srv.shutdown()
