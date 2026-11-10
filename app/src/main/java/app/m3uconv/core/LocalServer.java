package app.m3uconv.core;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server on 127.0.0.1 - same endpoints as the Flask web version.
 * Because the phone itself talks to the portal, there are no blocked data-center IPs and no IP-bound tokens.
 */
public final class LocalServer {
    public interface IndexProvider {
        String get() throws IOException;
    }

    static final class HttpError extends RuntimeException {
        final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static final class Out {
        int code = 200;
        String ctype = "text/plain; charset=utf-8";
        byte[] body = new byte[0];
        final Map<String, String> headers = new LinkedHashMap<>();
    }

    private final int preferredPort;
    private final IndexProvider index;
    private final Map<String, Stalker> pool = new ConcurrentHashMap<>();
    private ServerSocket ss;
    private ExecutorService exec;
    private volatile int port;

    public LocalServer(int preferredPort, IndexProvider index) {
        this.preferredPort = preferredPort;
        this.index = index;
    }

    public int port() {
        return port;
    }

    public synchronized int start() throws IOException {
        if (ss != null) return port;
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        try {
            ss = new ServerSocket(preferredPort, 50, lo);
        } catch (IOException busy) {
            ss = new ServerSocket(0, 50, lo);
        }
        port = ss.getLocalPort();
        exec = Executors.newCachedThreadPool();
        final ServerSocket sock = ss;
        Thread t = new Thread(() -> {
            while (!sock.isClosed()) {
                try {
                    final Socket s = sock.accept();
                    exec.execute(() -> handle(s));
                } catch (IOException e) {
                    if (sock.isClosed()) break;
                }
            }
        }, "m3u-accept");
        t.setDaemon(true);
        t.start();
        return port;
    }

    public synchronized void stop() {
        try {
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
        if (exec != null) exec.shutdownNow();
        ss = null;
    }

    // ---------------------------------------------------------------- http plumbing
    private void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, state = 0;
            while (head.size() < 32768 && (b = in.read()) >= 0) {       // read up to the blank line
                head.write(b);
                if (b == '\r' && (state == 0 || state == 2)) state++;
                else if (b == '\n' && (state == 1 || state == 3)) {
                    state++;
                    if (state == 4) break;
                } else state = 0;
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
            Out out;
            boolean headOnly = false;
            try {
                if (lines.length == 0 || lines[0].isEmpty()) throw new HttpError(400, "Bad request");
                String[] rl = lines[0].split(" ");
                if (rl.length < 2) throw new HttpError(400, "Bad request");
                String method = rl[0].toUpperCase(Locale.ROOT);
                headOnly = method.equals("HEAD");
                if (!method.equals("GET") && !headOnly) throw new HttpError(405, "Only GET is supported");
                String hostHeader = "";
                for (int i = 1; i < lines.length; i++) {
                    if (lines[i].toLowerCase(Locale.ROOT).startsWith("host:")) hostHeader = lines[i].substring(5).trim();
                }
                String hh = hostHeader.toLowerCase(Locale.ROOT);
                if (!(hh.equals("127.0.0.1:" + port) || hh.equals("localhost:" + port)
                        || hh.equals("127.0.0.1") || hh.equals("localhost"))) {
                    throw new HttpError(403, "Forbidden host");        // blocks DNS-rebinding tricks
                }
                out = route(rl[1]);
            } catch (HttpError e) {
                out = json(e.code, "{\"error\":" + MiniJson.quote(String.valueOf(e.getMessage())) + "}");
            } catch (PortalException e) {
                out = json(502, "{\"error\":" + MiniJson.quote(String.valueOf(e.getMessage())) + "}");
            } catch (RuntimeException e) {
                out = json(500, "{\"error\":" + MiniJson.quote("Internal error: " + e.getClass().getSimpleName()) + "}");
            }
            write(s.getOutputStream(), out, headOnly);
        } catch (IOException ignored) {
            // client went away
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 502: return "Bad Gateway";
            default: return code >= 500 ? "Server Error" : "OK";
        }
    }

    private static void write(OutputStream raw, Out o, boolean headOnly) throws IOException {
        OutputStream os = new BufferedOutputStream(raw, 65536);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(o.code).append(' ').append(reason(o.code)).append("\r\n");
        sb.append("Content-Type: ").append(o.ctype).append("\r\n");
        sb.append("Content-Length: ").append(o.body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\nConnection: close\r\n");
        for (Map.Entry<String, String> e : o.headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        os.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) os.write(o.body);
        os.flush();
    }

    private static Out text(int code, String ctype, String body) {
        Out o = new Out();
        o.code = code;
        o.ctype = ctype;
        o.body = body.getBytes(StandardCharsets.UTF_8);
        return o;
    }

    private static Out json(int code, String body) {
        return text(code, "application/json; charset=utf-8", body);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            m.put(Util.dec(k), Util.dec(v));
        }
        return m;
    }

    // ---------------------------------------------------------------- routing
    private Out route(String target) throws IOException {
        int qi = target.indexOf('?');
        String path = qi < 0 ? target : target.substring(0, qi);
        Map<String, String> q = parseQuery(qi < 0 ? "" : target.substring(qi + 1));
        switch (path) {
            case "/":
                return text(200, "text/html; charset=utf-8", index.get());
            case "/healthz":
                return text(200, "text/plain; charset=utf-8", "ok");
            case "/api/config":
                return json(200, "{\"key_required\":false,\"xtream_url\":false}");
            case "/api/portal/test":
                return portalTest(q);
            case "/api/portal/categories":
                return portalCategories(q);
            case "/portal.m3u":
                return portalM3u(q);
            case "/portal/play":
                return portalPlay(q);
            case "/api/xtream/categories":
                return xtreamCategories(q);
            case "/xtream.m3u": {
                String[] c = xCreds(q);
                return xtreamPlaylist(Xtream.base(c[0]), c[1], c[2], q);
            }
            default:
                break;
        }
        if (path.endsWith(".m3u")) {                    // /<host:port>/<user>/<pass>.m3u
            String[] seg = path.substring(1, path.length() - 4).split("/", -1);
            if (seg.length == 3) {
                String scheme = "1".equals(q.get("https")) ? "https://" : "http://";
                return xtreamPlaylist(Xtream.base(scheme + Util.decPath(seg[0])), Util.decPath(seg[1]),
                        Util.decPath(seg[2]), q);
            }
        }
        throw new HttpError(404, "Not found");
    }

    private Out m3uOut(String body, String name, Map<String, String> q) {
        Out o = text(200, "audio/x-mpegurl; charset=utf-8", body);
        if ("1".equals(q.get("download"))) o.headers.put("Content-Disposition", "attachment; filename=\"" + name + ".m3u\"");
        return o;
    }

    // ---------------------------------------------------------------- MAC portal
    private static final String[] CFG_KEYS = {"url", "mac", "d1", "d2", "sn"};

    private Map<String, String> cfg(Map<String, String> q) {
        Map<String, String> c = new LinkedHashMap<>();
        if (q.get("cfg") != null && !q.get("cfg").isEmpty()) {
            Map<String, Object> m;
            try {
                m = MiniJson.map(MiniJson.parse(new String(Base64.getUrlDecoder().decode(q.get("cfg")),
                        StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                throw new HttpError(400, "Invalid cfg");
            }
            if (m == null) throw new HttpError(400, "Invalid cfg");
            for (String k : CFG_KEYS) c.put(k, MiniJson.str(m.get(k)).trim());
        } else {
            for (String k : CFG_KEYS) c.put(k, q.get(k) == null ? "" : q.get(k).trim());
        }
        if (c.get("url").isEmpty() || c.get("mac").isEmpty()) throw new HttpError(400, "Portal URL and MAC required");
        try {
            c.put("mac", Stalker.normMac(c.get("mac")));
        } catch (PortalException e) {
            throw new HttpError(400, e.getMessage());
        }
        return c;
    }

    private Stalker portal(Map<String, String> c) {
        StringBuilder key = new StringBuilder();
        for (String k : CFG_KEYS) key.append(c.get(k)).append('\n');
        String id = Util.hex("SHA-256", key.toString());
        Stalker s = pool.get(id);
        if (s == null) {
            try {
                s = new Stalker(c.get("url"), c.get("mac"), c.get("d1"), c.get("d2"), c.get("sn"), 40000);
            } catch (PortalException e) {
                throw new HttpError(400, e.getMessage());
            }
            if (pool.size() > 20) pool.clear();
            Stalker prev = pool.putIfAbsent(id, s);
            if (prev != null) s = prev;
        }
        return s;
    }

    private Out portalTest(Map<String, String> q) {
        Stalker p = portal(cfg(q));
        p.login();
        List<Stalker.Channel> items = p.items();
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"endpoint\":").append(MiniJson.quote(p.endpoint));
        sb.append(",\"channels\":").append(items.size());
        sb.append(",\"categories\":").append(p.categories().size());
        if (p.profileError != null) sb.append(",\"profile_warning\":").append(MiniJson.quote(p.profileError));
        String exp = p.accountExpires();
        if (!exp.isEmpty()) sb.append(",\"expires\":").append(MiniJson.quote(exp));
        try {
            String link = p.link(items.get(0).cmd);
            String auth = link.replaceFirst("^[a-zA-Z0-9+.-]+://", "");
            int slash = auth.indexOf('/');
            if (slash >= 0) auth = auth.substring(0, slash);
            sb.append(",\"sample_channel\":").append(MiniJson.quote(items.get(0).name));
            sb.append(",\"sample_link_host\":").append(MiniJson.quote(auth));
        } catch (PortalException e) {
            sb.append(",\"sample_error\":").append(MiniJson.quote(e.getMessage()));
        }
        return json(200, sb.append('}').toString());
    }

    private Out portalCategories(Map<String, String> q) {
        List<String> inc = Util.kwList(q.get("keywords")), exc = Util.kwList(q.get("exclude"));
        List<Stalker.Category> cats = portal(cfg(q)).categories();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cats.size(); i++) {
            Stalker.Category c = cats.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(MiniJson.quote(c.id)).append(",\"name\":").append(MiniJson.quote(c.name))
                    .append(",\"count\":").append(c.count).append(",\"matched\":")
                    .append(Util.matches(c.name, inc, exc)).append('}');
        }
        return json(200, sb.append(']').toString());
    }

    private Out portalM3u(Map<String, String> q) {
        Map<String, String> c = cfg(q);
        List<String> inc = Util.kwList(q.get("keywords")), exc = Util.kwList(q.get("exclude"));
        Set<String> chosen = new HashSet<>();
        if (q.get("cats") != null) for (String s : q.get("cats").split(",")) if (!s.isEmpty()) chosen.add(s);
        Stalker p = portal(c);
        StringBuilder cj = new StringBuilder("{");
        for (int i = 0; i < CFG_KEYS.length; i++) {
            if (i > 0) cj.append(',');
            cj.append(MiniJson.quote(CFG_KEYS[i])).append(':').append(MiniJson.quote(c.get(CFG_KEYS[i])));
        }
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cj.append('}').toString().getBytes(StandardCharsets.UTF_8));
        String origin = "http://127.0.0.1:" + port;
        List<M3u.Entry> entries = new ArrayList<>();
        for (Stalker.Channel ch : p.items()) {
            boolean take = chosen.isEmpty() ? Util.matches(ch.group, inc, exc) : chosen.contains(ch.genreId);
            if (!take) continue;
            M3u.Entry e = new M3u.Entry();
            e.name = ch.name;
            e.logo = ch.logo;
            e.group = ch.group;
            e.tvgId = ch.tvgId;
            e.chno = ch.chno;
            e.url = origin + "/portal/play?cfg=" + token + "&cmd=" + Util.quote(ch.cmd);
            entries.add(e);
        }
        if ("1".equals(q.get("sort"))) sortEntries(entries);
        String epg = q.get("epg");
        if (epg != null && !epg.toLowerCase(Locale.ROOT).startsWith("http")) epg = null;
        return m3uOut(M3u.build(entries, epg), "portal", q);
    }

    private static void sortEntries(List<M3u.Entry> l) {
        Collections.sort(l, (a, b) -> {
            int g = a.group.toLowerCase(Locale.ROOT).compareTo(b.group.toLowerCase(Locale.ROOT));
            return g != 0 ? g : a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
        });
    }

    private Out portalPlay(Map<String, String> q) {
        Map<String, String> c = cfg(q);
        String cmd = q.get("cmd");
        if (cmd == null || cmd.isEmpty()) throw new HttpError(400, "cmd missing");
        Stalker p = portal(c);
        String url;
        try {
            url = p.link(cmd);
        } catch (PortalException e) {          // fresh handshake, then one more try
            p.login();
            url = p.link(cmd);
        }
        Out o = new Out();
        o.code = 302;
        o.headers.put("Location", url.replace(" ", "%20"));
        return o;
    }

    // ---------------------------------------------------------------- Xtream
    private static String[] xCreds(Map<String, String> q) {
        for (String k : new String[]{"url", "username", "password"}) {
            if (q.get(k) == null || q.get(k).isEmpty()) throw new HttpError(400, "Missing " + k);
        }
        return new String[]{q.get("url"), q.get("username"), q.get("password")};
    }

    private static String kind(Map<String, String> q) {
        String k = q.get("kind") == null ? "live" : q.get("kind");
        if (!k.equals("live") && !k.equals("vod")) throw new HttpError(400, "kind must be live or vod");
        return k;
    }

    private Out xtreamCategories(Map<String, String> q) {
        String[] c = xCreds(q);
        List<String> inc = Util.kwList(q.get("keywords")), exc = Util.kwList(q.get("exclude"));
        List<Xtream.Cat> cats = Xtream.categories(Xtream.base(c[0]), c[1], c[2], kind(q));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cats.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(MiniJson.quote(cats.get(i).id)).append(",\"name\":")
                    .append(MiniJson.quote(cats.get(i).name)).append(",\"matched\":")
                    .append(Util.matches(cats.get(i).name, inc, exc)).append('}');
        }
        return json(200, sb.append(']').toString());
    }

    private Out xtreamPlaylist(String base, String user, String pass, Map<String, String> q) {
        String kind = kind(q);
        String fmt = q.get("fmt") == null ? "ts" : q.get("fmt");
        if (!fmt.equals("ts") && !fmt.equals("m3u8")) throw new HttpError(400, "fmt must be ts or m3u8");
        Set<String> chosen = new HashSet<>();
        if (q.get("cats") != null) for (String s : q.get("cats").split(",")) if (!s.isEmpty()) chosen.add(s);
        List<M3u.Entry> entries = Xtream.entries(base, user, pass, kind, fmt, Util.kwList(q.get("keywords")),
                Util.kwList(q.get("exclude")), chosen);
        if (!"0".equals(q.get("sort"))) sortEntries(entries);
        String epg = q.get("epg");
        if (epg == null || !epg.toLowerCase(Locale.ROOT).startsWith("http")) {
            epg = kind.equals("live") ? base + "/xmltv.php?username=" + Util.quote(user) + "&password=" + Util.quote(pass) : null;
        }
        return m3uOut(M3u.build(entries, epg), "xtream_" + kind, q);
    }
}
