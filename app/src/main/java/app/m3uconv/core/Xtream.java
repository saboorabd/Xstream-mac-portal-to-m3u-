package app.m3uconv.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Xtream Codes support: category list + M3U playlist (stream links point straight at the provider). */
public final class Xtream {
    private Xtream() {
    }

    private static final Map<String, Object[]> CACHE = new HashMap<>();   // key -> {timestamp, data}

    public static String base(String url) {
        url = url == null ? "" : url.trim();
        if (!url.toLowerCase().startsWith("http")) url = "http://" + url;
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getAuthority();
        } catch (MalformedURLException e) {
            throw new PortalException("Server URL is not valid");
        }
    }

    public static Object api(String base, String user, String pass, String action) {
        String key = base + "|" + user + "|" + pass + "|" + action;
        synchronized (CACHE) {
            Object[] hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 5 * 60 * 1000L) return hit[1];
        }
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "okhttp/4.9.0");
        Http.Resp r;
        try {
            r = Http.get(base + "/player_api.php?username=" + Util.enc(user) + "&password=" + Util.enc(pass)
                    + "&action=" + Util.enc(action), h, 40000, true);
        } catch (IOException e) {
            throw new PortalException("Cannot connect to the server - check the URL", e);
        }
        if (r.status >= 400) throw new PortalException("Server answered HTTP " + r.status);
        Object data = MiniJson.parseLenient(r.body);
        if (data == null) {
            throw new PortalException("Provider sent an invalid reply - check URL, username and password");
        }
        synchronized (CACHE) {
            if (CACHE.size() > 50) CACHE.clear();
            CACHE.put(key, new Object[]{System.currentTimeMillis(), data});
        }
        return data;
    }

    public static final class Cat {
        public String id, name;
    }

    public static List<Cat> categories(String base, String user, String pass, String kind) {
        List<Object> l = MiniJson.list(api(base, user, pass, kind.equals("vod") ? "get_vod_categories" : "get_live_categories"));
        if (l == null) throw new PortalException("Login failed or no categories returned");
        List<Cat> out = new ArrayList<>();
        for (Object o : l) {
            Map<String, Object> m = MiniJson.map(o);
            if (m == null) continue;
            Cat c = new Cat();
            c.id = MiniJson.str(m.get("category_id"));
            c.name = MiniJson.str(m.get("category_name"));
            out.add(c);
        }
        return out;
    }

    public static List<M3u.Entry> entries(String base, String user, String pass, String kind, String fmt,
                                          List<String> inc, List<String> exc, java.util.Set<String> chosen) {
        List<Cat> cats = categories(base, user, pass, kind);
        Map<String, String> names = new LinkedHashMap<>();
        for (Cat c : cats) {
            boolean take = chosen.isEmpty() ? Util.matches(c.name, inc, exc) : chosen.contains(c.id);
            if (take) names.put(c.id, c.name.isEmpty() ? "Other" : c.name);
        }
        boolean live = !kind.equals("vod");
        List<Object> streams = MiniJson.list(api(base, user, pass, live ? "get_live_streams" : "get_vod_streams"));
        if (streams == null) throw new PortalException("Provider returned no stream list");
        List<M3u.Entry> out = new ArrayList<>();
        for (Object o : streams) {
            Map<String, Object> s = MiniJson.map(o);
            if (s == null) continue;
            String cid = MiniJson.str(s.get("category_id"));
            String sid = MiniJson.str(s.get("stream_id"));
            if (!names.containsKey(cid) || sid.isEmpty()) continue;
            M3u.Entry e = new M3u.Entry();
            e.name = MiniJson.str(s.get("name"));
            if (e.name.isEmpty()) e.name = "Unknown";
            e.logo = MiniJson.str(s.get("stream_icon"));
            e.group = names.get(cid);
            e.tvgId = MiniJson.str(s.get("epg_channel_id"));
            if (live) {
                e.chno = MiniJson.str(s.get("num"));
                e.url = base + "/live/" + Util.quote(user) + "/" + Util.quote(pass) + "/" + sid + "." + fmt;
            } else {
                String ext = MiniJson.str(s.get("container_extension"));
                e.url = base + "/movie/" + Util.quote(user) + "/" + Util.quote(pass) + "/" + sid + "."
                        + (ext.isEmpty() ? "mp4" : ext);
            }
            out.add(e);
        }
        return out;
    }
}
