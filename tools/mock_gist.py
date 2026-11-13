"""Tiny fake of the GitHub gist API for tests: python3 tools/mock_gist.py  (prints its port)."""
import json, sys, re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
GISTS = {}
SHA = "a" * 40
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, obj, chunked=False):
        b = json.dumps(obj).encode() if not isinstance(obj, bytes) else obj
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Connection", "close")
        if chunked:
            self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
            for i in range(0, len(b), 7): self.wfile.write(b"%x\r\n" % len(b[i:i+7]) + b[i:i+7] + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
        else:
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _view(self, gid):
        port = self.server.server_address[1]
        return {"id": gid, "html_url": f"http://127.0.0.1:{port}/me/{gid}", "owner": {"login": "me"},
                "files": {n: {"raw_url": f"http://127.0.0.1:{port}/me/{gid}/raw/{SHA}/{n}"} for n in GISTS[gid]}}
    def _auth(self):
        if self.headers.get("Authorization") != "Bearer good-token": self._send(401, {"message": "Bad credentials"}); return False
        return True
    def _body(self): return json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
    def do_POST(self):
        if not self._auth(): return
        if self.path == "/gists":
            b = self._body(); gid = "g%d" % (len(GISTS) + 1)
            if b.get("public") is not False: return self._send(422, {"message": "must be secret"})
            GISTS[gid] = {n: f["content"] for n, f in b["files"].items()}; return self._send(201, self._view(gid))
        self._send(404, {"message": "Not Found"})
    def do_PATCH(self):
        if not self._auth(): return
        m = re.match(r"/gists/(\w+)$", self.path)
        if not m or m.group(1) not in GISTS: return self._send(404, {"message": "Not Found"})
        b = self._body(); g = GISTS[m.group(1)]
        for n, f in b["files"].items():
            if f is None: g.pop(n, None)
            else: g[n] = f["content"]
        self._send(200, self._view(m.group(1)), chunked=True)
    def do_GET(self):
        m = re.match(r"/me/(\w+)/raw/(?:[0-9a-f]{40}/)?(.+)$", self.path)
        if m and m.group(1) in GISTS and m.group(2) in GISTS[m.group(1)]:
            return self._send(200, GISTS[m.group(1)][m.group(2)].encode())
        self._send(404, {"message": "Not Found"})
if __name__ == "__main__":
    s = ThreadingHTTPServer(("127.0.0.1", 0), H); print(s.server_address[1], flush=True); s.serve_forever()
