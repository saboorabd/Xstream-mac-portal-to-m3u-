package app.m3uconv.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Xtream Codes support. Stream links point straight at the provider, so no resolving is needed. */
public final class Xtream {
    private Xtream() {
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public final List<Object> cats = new ArrayList<>();
        public String expires = "";
        public String epg = "";
    }

    public static String base(String url) {
        url = url == null ? "" : url.trim();
        if (url.isEmpty()) throw new PortalException("Server URL is missing");
        if (!url.toLowerCase(Locale.ROOT).startsWith("http")) url = "http://" + url;
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getAuthority();
        } catch (MalformedURLException e) {
            throw new PortalException("Server URL is not valid");
        }
    }

    private static Object api(String base, String user, String pass, String action) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "okhttp/4.9.0");
        h.put("Accept-Encoding", "identity");
        Http.Resp r;
        try {
            r = Http.get(base + "/player_api.php?username=" + Util.enc(user) + "&password=" + Util.enc(pass)
                    + (action.isEmpty() ? "" : "&action=" + Util.enc(action)), h, 45000, true);
        } catch (IOException e) {
            throw new PortalException("Cannot connect to the server - check the URL", e);
        }
        if (r.status >= 400) throw new PortalException("Server answered HTTP " + r.status);
        Object data = MiniJson.parseLenient(r.body);
        if (data == null) {
            throw new PortalException("Provider sent an invalid reply - check URL, username and password");
        }
        return data;
    }

    public static Result load(String base, String user, String pass, String kind, String fmt) {
        boolean live = !"vod".equals(kind);
        Result res = new Result();
        List<Object> cats = MiniJson.list(api(base, user, pass, live ? "get_live_categories" : "get_vod_categories"));
        if (cats == null) throw new PortalException("Login failed - check username and password");
        Map<String, String> names = new LinkedHashMap<>();
        for (Object o : cats) {
            Map<String, Object> m = MiniJson.map(o);
            if (m != null) names.put(MiniJson.str(m.get("category_id")), MiniJson.str(m.get("category_name")));
        }
        List<Object> streams = MiniJson.list(api(base, user, pass, live ? "get_live_streams" : "get_vod_streams"));
        if (streams == null) throw new PortalException("Provider returned no stream list");
        Map<String, Integer> counts = new HashMap<>();
        String qu = Util.quote(user), qp = Util.quote(pass);
        for (Object o : streams) {
            Map<String, Object> s = MiniJson.map(o);
            if (s == null) continue;
            String sid = MiniJson.str(s.get("stream_id"));
            if (sid.isEmpty()) continue;
            String cid = MiniJson.str(s.get("category_id"));
            Item it = new Item();
            it.name = MiniJson.str(s.get("name")).trim();
            if (it.name.isEmpty()) it.name = "Unknown";
            it.logo = MiniJson.str(s.get("stream_icon"));
            it.groupId = names.containsKey(cid) ? cid : "other";
            it.group = names.containsKey(cid) && !names.get(cid).isEmpty() ? names.get(cid) : "Other";
            it.tvgId = MiniJson.str(s.get("epg_channel_id"));
            if (live) {
                it.chno = MiniJson.str(s.get("num"));
                it.url = base + "/live/" + qu + "/" + qp + "/" + sid + "." + fmt;
            } else {
                String ext = MiniJson.str(s.get("container_extension"));
                it.url = base + "/movie/" + qu + "/" + qp + "/" + sid + "." + (ext.isEmpty() ? "mp4" : ext);
            }
            counts.merge(it.groupId, 1, Integer::sum);
            res.items.add(it);
        }
        if (res.items.isEmpty()) throw new PortalException("The provider returned 0 channels");
        for (Map.Entry<String, String> e : names.entrySet()) {
            Integer n = counts.get(e.getKey());
            if (n == null) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", e.getKey());
            c.put("name", e.getValue().isEmpty() ? "Other" : e.getValue());
            c.put("count", n);
            res.cats.add(c);
        }
        Integer other = counts.get("other");
        if (other != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", "other");
            c.put("name", "Other");
            c.put("count", other);
            res.cats.add(c);
        }
        if (live) res.epg = base + "/xmltv.php?username=" + qu + "&password=" + qp;
        try {                                          // account expiry (optional)
            Map<String, Object> info = MiniJson.map(MiniJson.map(api(base, user, pass, "")).get("user_info"));
            long exp = Long.parseLong(MiniJson.str(info.get("exp_date")));
            if (exp > 0) res.expires = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(exp * 1000L));
        } catch (RuntimeException ignored) {
        }
        return res;
    }
}
