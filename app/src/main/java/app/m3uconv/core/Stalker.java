package app.m3uconv.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Stalker / MAC portal client. Same flow as a MAG250 box (and as stalker.py in the web version):
 * handshake -> get_profile -> get_all_channels -> create_link, with auto re-login.
 */
public final class Stalker {
    static final String UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) "
            + "MAG250 stbapp ver: 2 rev: 250 Safari/533.3";
    static final long MAX_TOKEN_AGE = 45 * 60 * 1000L;
    static final long CACHE_TTL = 10 * 60 * 1000L;
    static final int FAST_MS = 12000;      // handshake, profile, genres, account: these answer quickly or not at all

    public static final class Channel {
        public String id = "", name = "", cmd = "", logo = "", genreId = "", group = "", tvgId = "", chno = "";
    }

    public static final class Category {
        public String id, name;
        public int count;
    }

    public final String host, base, logoBase, mac, d1, d2, sn;
    private final List<String> eps;
    private final int timeoutMs;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final Object chLock = new Object();
    private final Object genLock = new Object();

    private volatile String token;
    private volatile long loginTs;
    private String ep = "";
    private String random = "";
    public volatile String endpoint = "";
    public volatile String profileError;

    private List<Map<String, Object>> chCache;
    private long chTs;
    private Map<String, String> genreCache;
    private long genreTs;

    public Stalker(String url, String macIn, String d1In, String d2In, String snIn, int timeoutMs) {
        url = url == null ? "" : url.trim();
        if (url.isEmpty()) throw new PortalException("Portal URL is missing");
        if (!url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) url = "http://" + url;
        URL u;
        try {
            u = new URL(url);
        } catch (MalformedURLException e) {
            throw new PortalException("Portal URL is not valid");
        }
        if (u.getHost() == null || u.getHost().isEmpty()) throw new PortalException("Portal URL is not valid");
        host = u.getProtocol() + "://" + u.getAuthority();
        String path = u.getPath() == null ? "" : u.getPath();
        String direct = null;
        if (path.toLowerCase().endsWith(".php")) {              // user pasted the full api endpoint
            direct = host + path;
            path = path.substring(0, path.lastIndexOf('/'));
        }
        path = path.replaceFirst("/c(?:/.*)?$", "");             // strip the /c/ web-UI part
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        base = host + path;

        Set<String> set = new LinkedHashSet<>();
        if (direct != null) set.add(direct);
        set.add(base + "/server/load.php");
        set.add(base + "/portal.php");
        set.add(host + "/stalker_portal/server/load.php");
        set.add(host + "/server/load.php");
        set.add(host + "/portal.php");
        eps = new ArrayList<>(set);
        logoBase = (path.contains("stalker_portal") ? base : host + "/stalker_portal") + "/misc/logos/320/";

        mac = normMac(macIn);
        d1 = (d1In == null || d1In.trim().isEmpty()) ? Util.hex("SHA-256", mac).toUpperCase() : d1In.trim();
        d2 = (d2In == null || d2In.trim().isEmpty()) ? d1 : d2In.trim();
        sn = (snIn == null || snIn.trim().isEmpty()) ? Util.hex("MD5", mac).substring(0, 13).toUpperCase() : snIn.trim();
        this.timeoutMs = timeoutMs;

        cookies.put("mac", mac);
        cookies.put("stb_lang", "en_US");
        cookies.put("timezone", "Asia/Kolkata");
    }

    public static String normMac(String m) {
        StringBuilder h = new StringBuilder();
        for (char c : (m == null ? "" : m).toCharArray()) {
            if (Character.digit(c, 16) >= 0) h.append(Character.toUpperCase(c));
        }
        if (h.length() != 12) throw new PortalException("MAC address format is wrong. Example: 00:1A:79:12:34:56");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) out.append(':');
            out.append(h, i, i + 2);
        }
        return out.toString();
    }

    public static String cleanCmd(String cmd) {
        String c = cmd == null ? "" : cmd.trim();
        c = c.replaceFirst("(?i)^(?:ffmpeg|ffrt\\d*|auto)\\s+", "");
        String[] parts = c.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[0];
    }

    // ---------------------------------------------------------------- low level
    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        synchronized (cookies) {
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(Util.enc(e.getKey()).replace("%3A", ":").replace("%2F", "/")).append('=')
                    .append(Util.enc(e.getValue()).replace("%3A", ":").replace("%2F", "/"));
        }
        return sb.toString();
    }

    private Object raw(Map<String, String> params) {
        return raw(ep, params, timeoutMs);
    }

    private Object raw(String endpoint, Map<String, String> params, int tmo) {
        Map<String, String> q = new LinkedHashMap<>(params);
        q.put("JsHttpRequest", "1-xml");
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "*/*");
        h.put("Accept-Encoding", "gzip");
        h.put("X-User-Agent", "Model: MAG250; Link: WiFi");
        h.put("Referer", base + "/c/");
        h.put("Cookie", cookieHeader());
        String t = token;
        if (t != null) h.put("Authorization", "Bearer " + t);
        Http.Resp r;
        try {
            r = Http.get(endpoint + "?" + query(q), h, tmo, true);
        } catch (IOException e) {
            throw new PortalException("Cannot reach portal (" + e.getClass().getSimpleName() + ")", e);
        }
        for (String sc : r.headerAll("Set-Cookie")) {
            String kv = sc.split(";", 2)[0];
            int eq = kv.indexOf('=');
            if (eq > 0) synchronized (cookies) {
                cookies.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        String text = r.body == null ? "" : r.body.trim();
        String head = text.length() > 200 ? text.substring(0, 200) : text;
        if (r.status == 401 || r.status == 403 || head.toLowerCase().contains("authorization failed")) {
            throw new PortalException.Auth("Portal refused the request ("
                    + (r.status >= 400 ? "HTTP " + r.status : "authorization failed")
                    + "): MAC not allowed or the portal rejected the session");
        }
        if (r.status >= 400) throw new PortalException("Portal answered HTTP " + r.status);
        Object data = MiniJson.parseLenient(text);
        if (data == null) {
            throw new PortalException("Portal sent a non-JSON reply (wrong URL?): "
                    + (text.length() > 60 ? text.substring(0, 60) : text).replace("\n", " "));
        }
        Map<String, Object> m = MiniJson.map(data);
        return m != null ? m.get("js") : data;
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private Map<String, String> profileParams() {
        Map<String, String> m = p("type", "stb", "action", "get_profile", "hd", "1",
                "ver", "ImageDescription: 0.2.18-r14-pub-250; PORTAL version: 5.5.0; API Version: JS API version: 343;",
                "num_banks", "2", "sn", sn, "stb_type", "MAG250", "client_type", "STB",
                "image_version", "218", "video_out", "hdmi",
                "device_id", d1, "device_id2", d2, "signature", "",
                "auth_second_step", "1", "hw_version", "1.7-BD-00", "not_valid_token", "0");
        m.put("metrics", "{\"mac\": \"" + mac + "\", \"model\": \"MAG250\", \"type\": \"STB\", \"uid\": \"\", \"random\": \""
                + random + "\"}");
        m.put("hw_version_2", Util.hex("SHA-1", mac));
        m.put("timestamp", Long.toString(System.currentTimeMillis() / 1000));
        m.put("api_signature", "263");
        m.put("prehash", "");
        return m;
    }

    // ---------------------------------------------------------------- session
    public void login() {
        synchronized (lock) {
            token = null;
            PortalException last = null;
            // Try every known endpoint at the same time; the first one (in priority order) that answers wins.
            ExecutorService ex = Executors.newFixedThreadPool(eps.size());
            try {
                List<Future<String[]>> fs = new ArrayList<>();
                for (final String e : eps) {
                    fs.add(ex.submit(new Callable<String[]>() {
                        @Override
                        public String[] call() {
                            Object js = raw(e, p("type", "stb", "action", "handshake", "token", ""), FAST_MS);
                            Map<String, Object> m = MiniJson.map(js);
                            if (m == null || MiniJson.str(m.get("token")).isEmpty()) {
                                throw new PortalException("handshake returned no token");
                            }
                            return new String[]{MiniJson.str(m.get("token")), MiniJson.str(m.get("random"))};
                        }
                    }));
                }
                for (int i = 0; i < fs.size() && token == null; i++) {
                    try {
                        String[] t = fs.get(i).get(FAST_MS + 4000, TimeUnit.MILLISECONDS);
                        ep = eps.get(i);
                        token = t[0];
                        random = t[1];
                    } catch (ExecutionException ee) {
                        Throwable c = ee.getCause();
                        if (c instanceof PortalException && !(last instanceof PortalException.Auth)) last = (PortalException) c;
                    } catch (TimeoutException te) {
                        if (last == null) last = new PortalException("portal did not answer in time");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                ex.shutdownNow();
            }
            if (token == null) {
                throw new PortalException("Handshake failed - check portal URL and MAC ("
                        + (last == null ? "?" : last.getMessage()) + ")");
            }
            endpoint = ep;
            try {
                raw(ep, profileParams(), FAST_MS);
                profileError = null;
            } catch (PortalException ex2) {   // some portals still work without a clean profile
                profileError = ex2.getMessage();
            }
            loginTs = System.currentTimeMillis();
        }
    }

    private Object call(Map<String, String> params) {
        return call(params, timeoutMs);
    }

    private Object call(Map<String, String> params, int tmo) {
        if (token == null || System.currentTimeMillis() - loginTs > MAX_TOKEN_AGE) {
            synchronized (lock) {
                if (token == null || System.currentTimeMillis() - loginTs > MAX_TOKEN_AGE) login();
            }
        }
        String used = token;
        try {
            return raw(ep, params, tmo);
        } catch (PortalException.Auth e) {
            synchronized (lock) {
                if (used != null && used.equals(token)) login();   // nobody refreshed it yet
            }
            return raw(ep, params, tmo);
        }
    }

    public String accountExpires() {
        try {
            Map<String, Object> m = MiniJson.map(call(p("type", "account_info", "action", "get_main_info"), FAST_MS));
            if (m == null) return "";
            for (String k : new String[]{"end_date", "expire_billing_date", "phone"}) {
                String v = MiniJson.str(m.get(k));
                if (!v.isEmpty()) return v;
            }
        } catch (PortalException ignored) {
        }
        return "";
    }

    // ---------------------------------------------------------------- data
    public Map<String, String> genres() {
        synchronized (genLock) {
            if (genreCache != null && System.currentTimeMillis() - genreTs < CACHE_TTL) return genreCache;
        }
        Object js;
        try {
            js = call(p("type", "itv", "action", "get_genres"), FAST_MS);
        } catch (PortalException e) {
            js = null;
        }
        if (MiniJson.map(js) != null) js = MiniJson.map(js).get("data");
        Map<String, String> out = new LinkedHashMap<>();
        List<Object> l = MiniJson.list(js);
        if (l != null) {
            for (Object o : l) {
                Map<String, Object> g = MiniJson.map(o);
                if (g == null) continue;
                String id = MiniJson.str(g.get("id"));
                if (id.equals("*")) continue;
                String title = MiniJson.str(g.get("title"));
                out.put(id, title.isEmpty() ? "Other" : title);
            }
        }
        synchronized (genLock) {
            genreCache = out;
            genreTs = System.currentTimeMillis();
        }
        return out;
    }

    private static List<Map<String, Object>> withCmd(List<Object> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        for (Object o : rows) {
            Map<String, Object> m = MiniJson.map(o);
            if (m != null && !MiniJson.str(m.get("cmd")).isEmpty()) out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> orderedList() {
        List<Object> all = new ArrayList<>();
        for (int page = 1; page <= 400; page++) {
            Object js = call(p("type", "itv", "action", "get_ordered_list", "genre", "*", "force_ch_link_check", "",
                    "fav", "0", "sortby", "number", "hd", "0", "p", Integer.toString(page)));
            Map<String, Object> m = MiniJson.map(js);
            List<Object> rows = m == null ? null : MiniJson.list(m.get("data"));
            if (rows == null || rows.isEmpty()) break;
            all.addAll(rows);
            long total = 0;
            try {
                total = Long.parseLong(MiniJson.str(m.get("total_items")));
            } catch (NumberFormatException ignored) {
            }
            if (total > 0 && all.size() >= total) break;
        }
        return withCmd(all);
    }

    private List<Map<String, Object>> loadChannels() {
        PortalException first = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Object js = call(p("type", "itv", "action", "get_all_channels"));
            Map<String, Object> m = MiniJson.map(js);
            rows = withCmd(m != null ? MiniJson.list(m.get("data")) : MiniJson.list(js));
        } catch (PortalException e) {
            first = e;
        }
        if (rows.isEmpty()) {                       // portals that disable get_all_channels
            try {
                rows = orderedList();
            } catch (PortalException e) {
                throw first != null ? first : e;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> channels() {
        synchronized (chLock) {
            if (chCache != null && System.currentTimeMillis() - chTs < CACHE_TTL) return chCache;
            List<Map<String, Object>> rows = loadChannels();
            if (rows.isEmpty()) {                   // maybe a stale session - one fresh try
                login();
                rows = loadChannels();
            }
            if (rows.isEmpty()) {
                throw new PortalException("Portal returned 0 channels - account expired or MAC not active?");
            }
            chCache = rows;
            chTs = System.currentTimeMillis();
            return rows;
        }
    }

    private String logoUrl(String logo) {
        logo = logo == null ? "" : logo.trim();
        if (logo.isEmpty()) return "";
        if (logo.startsWith("http")) return logo;
        if (logo.startsWith("//")) return "http:" + logo;
        if (logo.startsWith("/")) return host + logo;
        return logoBase + logo;
    }

    public static final class Loaded {
        public List<Channel> items;
        public List<Category> cats;
        public String expires = "";
    }

    /** Login, then genres + channels + account info all at once. */
    public Loaded loadAll(Consumer<String> phase) {
        if (phase != null) phase.accept("Logging in to the portal…");
        login();
        if (phase != null) phase.accept("Loading channels…");
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            Future<Map<String, String>> g = ex.submit(new Callable<Map<String, String>>() {
                public Map<String, String> call() {
                    return genres();
                }
            });
            Future<String> a = ex.submit(new Callable<String>() {
                public String call() {
                    return accountExpires();
                }
            });
            Future<List<Map<String, Object>>> c = ex.submit(new Callable<List<Map<String, Object>>>() {
                public List<Map<String, Object>> call() {
                    return channels();
                }
            });
            List<Map<String, Object>> rows = await(c);
            Map<String, String> genres = await(g);
            if (phase != null) phase.accept("Building categories…");
            Loaded out = new Loaded();
            out.items = build(rows, genres);
            out.cats = categories(out.items, genres);
            try {
                out.expires = a.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    private static <T> T await(Future<T> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw new PortalException("Loading failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PortalException("Cancelled");
        }
    }

    private List<Channel> build(List<Map<String, Object>> rows, Map<String, String> genres) {
        List<Channel> out = new ArrayList<>(rows.size());
        for (Map<String, Object> c : rows) {
            String gid = MiniJson.str(c.get("tv_genre_id"));
            if (c.get("tv_genre_id") == null) gid = "None";
            boolean known = genres.containsKey(gid);
            Channel ch = new Channel();
            ch.id = MiniJson.str(c.get("id"));
            ch.name = MiniJson.str(c.get("name")).trim();
            if (ch.name.isEmpty()) ch.name = "Unknown";
            ch.cmd = MiniJson.str(c.get("cmd"));
            ch.logo = logoUrl(MiniJson.str(c.get("logo")));
            ch.genreId = known ? gid : "other";
            ch.group = known ? genres.get(gid) : "Other";
            ch.tvgId = MiniJson.str(c.get("xmltv_id"));
            ch.chno = MiniJson.str(c.get("number"));
            out.add(ch);
        }
        return out;
    }

    public List<Channel> items() {
        return build(channels(), genres());
    }

    private static List<Category> categories(List<Channel> items, Map<String, String> genres) {
        Map<String, Integer> counts = new HashMap<>();
        for (Channel c : items) counts.merge(c.genreId, 1, Integer::sum);
        List<Category> out = new ArrayList<>();
        for (Map.Entry<String, String> g : genres.entrySet()) {
            Integer n = counts.get(g.getKey());
            if (n != null && n > 0) {
                Category c = new Category();
                c.id = g.getKey();
                c.name = g.getValue();
                c.count = n;
                out.add(c);
            }
        }
        Integer other = counts.get("other");
        if (other != null && other > 0) {
            Category c = new Category();
            c.id = "other";
            c.name = "Other";
            c.count = other;
            out.add(c);
        }
        return out;
    }

    public List<Category> categories() {
        return categories(items(), genres());
    }

    /** Resolve a channel 'cmd' into the real stream URL (create_link). */
    public String link(String cmd) {
        Object js = call(p("type", "itv", "action", "create_link", "cmd", cmd, "series", "",
                "forced_storage", "undefined", "disable_ad", "0", "download", "0"), 30000);
        Map<String, Object> m = MiniJson.map(js);
        if (m != null && !MiniJson.str(m.get("error")).isEmpty()) {
            throw new PortalException("Portal says: " + MiniJson.str(m.get("error")));
        }
        String url = cleanCmd(m != null ? MiniJson.str(m.get("cmd")) : "");
        if (url.isEmpty()) url = cleanCmd(cmd);
        String h = "";
        try {
            h = url.isEmpty() ? "" : new URL(url).getHost();
        } catch (MalformedURLException ignored) {
        }
        if (url.isEmpty() || h.equalsIgnoreCase("localhost")) {
            throw new PortalException("Portal gave no playable link (channel blocked, account expired or too many "
                    + "devices on this MAC?)");
        }
        return url;
    }
}
