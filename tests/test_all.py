"""Run:  python -m unittest discover -s tests -v"""
import os
import re
import sys
import unittest
from unittest import mock
from urllib.parse import parse_qs, quote, urlparse

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))
os.environ["ALLOW_PRIVATE"] = "1"          # mock servers live on 127.0.0.1

import app as appmod                       # noqa: E402
import mock_portal as mp                   # noqa: E402
from stalker import Portal, PortalError, clean_cmd, norm_mac   # noqa: E402

SERVER = mp.Server()
PORT = 0


def setUpModule():
    global PORT
    PORT = SERVER.start()


def tearDownModule():
    SERVER.stop()


def cfg_qs(**extra):
    q = {"url": f"http://127.0.0.1:{PORT}/stalker_portal/c/", "mac": mp.VALID_MAC,
         "d1": "D" * 64, "d2": "D" * 64, "sn": "SN0000000000000"}
    q.update(extra)
    return "&".join(f"{k}={quote(str(v), safe='')}" for k, v in q.items())


class Helpers(unittest.TestCase):
    def test_mac_normalised(self):
        self.assertEqual(norm_mac("00-1a-79-82-5f-01"), "00:1A:79:82:5F:01")
        self.assertEqual(norm_mac("001A79825F01"), "00:1A:79:82:5F:01")
        with self.assertRaises(PortalError):
            norm_mac("00:1A:79")

    def test_clean_cmd(self):
        self.assertEqual(clean_cmd("ffmpeg http://a/b.ts"), "http://a/b.ts")
        self.assertEqual(clean_cmd("ffrt3 http://a/b"), "http://a/b")
        self.assertEqual(clean_cmd("auto http://a/b "), "http://a/b")
        self.assertEqual(clean_cmd("http://a/b"), "http://a/b")

    def test_endpoint_candidates(self):
        p = Portal("watch.example.cc/stalker_portal/c/", "00:1A:79:00:00:01")
        self.assertEqual(p.eps[0], "http://watch.example.cc/stalker_portal/server/load.php")
        p = Portal("http://h.example:80/c/", "00:1A:79:00:00:01")
        self.assertEqual(p.eps[0], "http://h.example:80/server/load.php")
        self.assertIn("http://h.example:80/stalker_portal/server/load.php", p.eps)
        p = Portal("http://h.example/portal.php", "00:1A:79:00:00:01")
        self.assertEqual(p.eps[0], "http://h.example/portal.php")
        p = Portal("http://h.example/stalker_portal/c/index.html", "00:1A:79:00:00:01")
        self.assertEqual(p.base, "http://h.example/stalker_portal")


class PortalClient(unittest.TestCase):
    def setUp(self):
        mp.S.reset()
        mp.S.port = PORT

    def new(self):
        return Portal(f"http://127.0.0.1:{PORT}/stalker_portal/c/", mp.VALID_MAC,
                      "D" * 64, "D" * 64, "SN123")

    def test_full_flow_matches_stb(self):
        p = self.new().login()
        self.assertTrue(p.token)
        first = [l for l in mp.S.log if l["action"] == "handshake"][0]
        self.assertEqual(first["mac"], mp.VALID_MAC)                       # mac cookie
        self.assertIn("MAG250", first["ua"])                               # STB user agent
        self.assertTrue(first["referer"].endswith("/stalker_portal/c/"))  # referer
        prof = [l for l in mp.S.log if l["action"] == "get_profile"][0]["args"]
        self.assertEqual(prof["sn"], "SN123")
        self.assertEqual(prof["device_id"], "D" * 64)
        self.assertEqual(prof["device_id2"], "D" * 64)
        items = p.items()
        self.assertEqual(len(items), 5)
        self.assertEqual(items[0]["logo"], f"http://127.0.0.1:{PORT}/stalker_portal/misc/logos/320/1.png")
        self.assertEqual(items[1]["logo"], "http://cdn.example/x.png")
        self.assertEqual(items[3]["group"], "Other")                       # unknown genre kept
        self.assertEqual(p.link(items[0]["cmd"]), f"http://127.0.0.1:{PORT}/stream/1.ts?token=abc")
        self.assertEqual(p.account_info(), {"expires": "2027-01-31"})
        names = [c["name"] for c in p.categories()]
        self.assertEqual(names, ["Tamil", "Sports", "Adult XXX", "Other"])

    def test_ordered_list_fallback(self):
        mp.S.use_ordered_list = True
        self.assertEqual(len(self.new().login().items()), 5)

    def test_auto_relogin_after_token_expiry(self):
        p = self.new().login()
        p.items()
        before = mp.S.handshakes
        mp.S.tokens.clear()                                                # server forgets token
        self.assertIn("/stream/1.ts", p.link("ffrt http://localhost/ch/1"))
        self.assertEqual(mp.S.handshakes, before + 1)

    def test_wrong_mac_gives_clear_error(self):
        p = Portal(f"http://127.0.0.1:{PORT}/stalker_portal/c/", "00:1A:79:FF:FF:FF")
        with self.assertRaises(PortalError) as cm:
            p.login()
        self.assertIn("Handshake failed", str(cm.exception))

    def test_localhost_link_rejected(self):
        p = self.new().login()
        with mock.patch.object(p, "call", return_value={"cmd": "ffrt http://localhost/ch/9"}):
            with self.assertRaises(PortalError):
                p.link("ffrt http://localhost/ch/9")


class WebApp(unittest.TestCase):
    def setUp(self):
        mp.S.reset()
        mp.S.port = PORT
        appmod._pool.clear()
        self.c = appmod.app.test_client()

    def test_ui_served(self):
        r = self.c.get("/")
        self.assertEqual(r.status_code, 200)
        self.assertIn(b"MAC Portal", r.data)

    def test_categories_with_counts(self):
        r = self.c.get("/api/portal/categories?" + cfg_qs(keywords="tamil", exclude="adult"))
        self.assertEqual(r.status_code, 200, r.data)
        d = {c["name"]: c for c in r.get_json()}
        self.assertTrue(d["Tamil"]["matched"])
        self.assertEqual(d["Tamil"]["count"], 2)
        self.assertFalse(d["Sports"]["matched"])
        self.assertFalse(d["Adult XXX"]["matched"])

    def test_test_endpoint(self):
        d = self.c.get("/api/portal/test?" + cfg_qs()).get_json()
        self.assertTrue(d["ok"])
        self.assertEqual(d["channels"], 5)
        self.assertEqual(d["expires"], "2027-01-31")
        self.assertEqual(d["sample_link_host"], f"127.0.0.1:{PORT}")

    def test_m3u_content(self):
        r = self.c.get("/portal.m3u?" + cfg_qs(sort="1", epg="http://epg.example/x.xml"))
        self.assertEqual(r.status_code, 200, r.data)
        text = r.get_data(as_text=True)
        self.assertTrue(text.startswith('#EXTM3U x-tvg-url="http://epg.example/x.xml"'))
        self.assertEqual(text.count("#EXTINF"), 5)
        self.assertIn('group-title="Other"', text)
        self.assertIn('tvg-chno="1"', text)
        self.assertIn('tvg-logo="http://127.0.0.1:%d/stalker_portal/misc/logos/320/1.png"' % PORT, text)
        self.assertIn(",Star Sports  HD\n", text)                            # comma removed from title
        self.assertIn("/portal/play?cfg=", text)

    def test_filter_by_keywords_and_cats(self):
        text = self.c.get("/portal.m3u?" + cfg_qs(keywords="tamil")).get_data(as_text=True)
        self.assertEqual(text.count("#EXTINF"), 2)
        text = self.c.get("/portal.m3u?" + cfg_qs(cats="other,2")).get_data(as_text=True)
        self.assertEqual(text.count("#EXTINF"), 2)

    def _first_play_url(self, extra=""):
        text = self.c.get("/portal.m3u?" + cfg_qs(keywords="sun" if False else "tamil") + extra).get_data(as_text=True)
        urls = [l for l in text.splitlines() if l.startswith("http")]
        return urls

    def test_play_redirect(self):
        url = self._first_play_url()[0]
        r = self.c.get(url.split("://", 1)[1].split("/", 1)[1].join(["/", ""]))
        self.assertEqual(r.status_code, 302)
        self.assertEqual(r.headers["Location"], f"http://127.0.0.1:{PORT}/stream/1.ts?token=abc")

    def test_play_survives_token_expiry(self):
        path = "/" + self._first_play_url()[0].split("/", 3)[3]
        self.assertEqual(self.c.get(path).status_code, 302)
        mp.S.tokens.clear()
        self.assertEqual(self.c.get(path).status_code, 302)

    def test_play_proxy_streams_bytes(self):
        path = "/" + self._first_play_url("&proxy=1")[0].split("/", 3)[3]
        self.assertIn("proxy=1", path)
        r = self.c.get(path)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.data, b"TS1-0;TS1-1;TS1-2;")
        self.assertEqual(r.headers["Content-Type"], "video/mp2t")

    def test_proxy_rewrites_hls_and_signs_segments(self):
        urls = self._first_play_url("&proxy=1")
        path = "/" + urls[1].split("/", 3)[3]                               # HLS News (cmd /ch/5)
        r = self.c.get(path)
        self.assertEqual(r.status_code, 200, r.data)
        txt = r.get_data(as_text=True)
        self.assertNotIn("127.0.0.1:%d/hls" % PORT, txt)                    # nothing leaks direct
        links = re.findall(r"http://localhost/portal/seg\?[^\s\"]+", txt)
        self.assertEqual(len(links), 3)                                      # key + seg1 + seg2
        got = [self.c.get("/" + l.split("/", 3)[3]).data for l in links]
        self.assertEqual(sorted(got), [b"DATA:key.bin", b"DATA:seg1.ts", b"DATA:seg2.ts"])
        bad = links[1].rsplit("s=", 1)[0] + "s=" + "0" * 32
        self.assertEqual(self.c.get("/" + bad.split("/", 3)[3]).status_code, 403)

    def test_old_plain_cfg_links_still_work(self):
        import base64, json
        cfg = {"url": f"http://127.0.0.1:{PORT}/stalker_portal/c/", "mac": mp.VALID_MAC,
               "d1": "D", "d2": "D", "sn": "S"}
        old = base64.urlsafe_b64encode(json.dumps(cfg).encode()).decode()
        r = self.c.get("/portal/play?cfg=" + old + "&cmd=" + quote("ffrt http://localhost/ch/1", safe=""))
        self.assertEqual(r.status_code, 302)

    def test_access_key(self):
        with mock.patch.object(appmod, "ACCESS_KEY", "sekret"):
            self.assertEqual(self.c.get("/portal.m3u?" + cfg_qs()).status_code, 401)
            self.assertEqual(self.c.get("/portal.m3u?" + cfg_qs(key="nope")).status_code, 401)
            r = self.c.get("/portal.m3u?" + cfg_qs(key="sekret"))
            self.assertEqual(r.status_code, 200)
            self.assertIn("key=sekret", r.get_data(as_text=True))          # play links carry the key

    def test_ssrf_guard(self):
        with mock.patch.dict(os.environ, {"ALLOW_PRIVATE": "0"}):
            r = self.c.get("/api/portal/categories?" + cfg_qs())
            self.assertEqual(r.status_code, 400)
            self.assertIn("Private", r.get_json()["error"])
            r = self.c.get("/portal/seg?u=" + "aHR0cDovLzEyNy4wLjAuMS8" + "&s=bad")
            self.assertEqual(r.status_code, 403)

    def test_bad_input_errors_are_json(self):
        r = self.c.get("/portal.m3u?url=&mac=")
        self.assertEqual(r.status_code, 400)
        self.assertIn("error", r.get_json())
        r = self.c.get("/portal.m3u?" + cfg_qs(mac="00:11"))
        self.assertEqual(r.status_code, 400)
        r = self.c.get("/portal/play?cfg=%%%%&cmd=x")
        self.assertEqual(r.status_code, 400)
        r = self.c.get("/api/portal/test?" + cfg_qs(mac="00:1A:79:FF:FF:FF"))
        self.assertEqual(r.status_code, 502)
        self.assertIn("Handshake failed", r.get_json()["error"])

    def test_dead_portal_gives_clean_error(self):
        q = cfg_qs(url="http://127.0.0.1:9/stalker_portal/c/")
        r = self.c.get("/api/portal/test?" + q)
        self.assertEqual(r.status_code, 502)
        self.assertIn("Cannot reach portal", r.get_json()["error"])


class XtreamStillWorks(unittest.TestCase):
    def setUp(self):
        appmod._cache.clear()
        self.c = appmod.app.test_client()

    def test_host_user_pass_link(self):
        r = self.c.get(f"/127.0.0.1:{PORT}/user/pass.m3u?keywords=tamil")
        self.assertEqual(r.status_code, 200, r.data)
        t = r.get_data(as_text=True)
        self.assertIn(f"http://127.0.0.1:{PORT}/live/user/pass/100.ts", t)
        self.assertNotIn("101.ts", t)
        self.assertIn("x-tvg-url=", t)

    def test_bad_login(self):
        r = self.c.get(f"/127.0.0.1:{PORT}/user/WRONG.m3u")
        self.assertEqual(r.status_code, 401)

    def test_fmt_validated(self):
        r = self.c.get(f"/127.0.0.1:{PORT}/user/pass.m3u?fmt=../../x")
        self.assertEqual(r.status_code, 400)

    def test_categories(self):
        r = self.c.get(f"/api/xtream/categories?url=127.0.0.1:{PORT}&username=user&password=pass&exclude=adult")
        self.assertEqual([c["matched"] for c in r.get_json()], [True, False])


if __name__ == "__main__":
    unittest.main()
