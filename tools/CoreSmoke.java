import app.m3uconv.core.*;

import java.util.List;
import java.util.Map;

/**
 * Plain-JVM test of the whole engine (no Android needed). Needs the fake portal: python3 tools/mock_portal.py
 *   javac -d /tmp/out $(find app/src/main/java/app/m3uconv/core -name '*.java') tools/CoreSmoke.java
 *   java -cp /tmp/out CoreSmoke <fake portal port>
 */
public class CoreSmoke {
    static int fails = 0;
    static Engine e = new Engine();

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) fails++;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> call(String m, String args) {
        return (Map<String, Object>) MiniJson.parse(e.call(m, args));
    }

    static String portal(String pp, String mac) {
        return "{\"type\":\"portal\",\"name\":\"Test Portal\",\"url\":\"http://127.0.0.1:" + pp
                + "/stalker_portal/c/\",\"mac\":\"" + mac + "\",\"d1\":\"DDDD\",\"d2\":\"DDDD\",\"sn\":\"SN1\"}";
    }

    static Map<String, Object> finish(String job) throws Exception {
        Map<String, Object> st = null;
        for (int i = 0; i < 100; i++) {
            st = call("status", "{\"job\":\"" + job + "\"}");
            if (Boolean.TRUE.equals(st.get("finished"))) break;
            Thread.sleep(100);
        }
        return st;
    }

    public static void main(String[] a) throws Exception {
        String pp = a[0];

        // ---- connect
        Map<String, Object> c = call("connect", "{\"source\":" + portal(pp, "00-1a-79-00-00-01") + "}");
        String key = String.valueOf(c.get("key"));
        check(c.get("error") == null && ((Number) c.get("channels")).intValue() == 5 && "2027-01-31".equals(c.get("expires")),
                "connect: " + MiniJson.write(c).substring(0, Math.min(160, MiniJson.write(c).length())));
        check(MiniJson.write(c.get("categories")).contains("{\"id\":\"1\",\"name\":\"Tamil\",\"count\":2}")
                && MiniJson.write(c.get("categories")).contains("\"name\":\"Other\""), "categories with counts + Other");

        // ---- filters
        Map<String, Object> l = call("list", "{\"key\":\"" + key + "\",\"filter\":{},\"offset\":0,\"limit\":60}");
        check(((Number) l.get("total")).intValue() == 5, "list all = 5");
        l = call("list", "{\"key\":\"" + key + "\",\"filter\":{\"cats\":[\"1\"]}}");
        check(((Number) l.get("total")).intValue() == 2, "category filter");
        l = call("list", "{\"key\":\"" + key + "\",\"filter\":{\"q\":\"sun, news\"}}");
        check(((Number) l.get("total")).intValue() == 2, "name search (comma = any)");
        l = call("list", "{\"key\":\"" + key + "\",\"filter\":{\"exclude\":\"adult\"}}");
        check(((Number) l.get("total")).intValue() == 4, "exclude words");
        l = call("list", "{\"key\":\"" + key + "\",\"filter\":{},\"offset\":3,\"limit\":10}");
        check(((List<Object>) l.get("rows")).size() == 2, "paging");

        // ---- resolve a single channel
        Map<String, Object> r = call("resolve", "{\"key\":\"" + key + "\",\"id\":0}");
        check(String.valueOf(r.get("url")).equals("http://127.0.0.1:" + pp + "/stream/1.ts?token=abc"), "resolve one channel: " + r.get("url"));

        // ---- export (real links)
        Map<String, Object> x = call("export", "{\"key\":\"" + key + "\",\"name\":\"My Portal!\",\"filter\":{\"exclude\":\"adult\"},"
                + "\"opts\":{\"threads\":4,\"sort\":true}}");
        String job = String.valueOf(x.get("job"));
        Map<String, Object> st = finish(job);
        check(((Number) st.get("ok")).intValue() == 4 && ((Number) st.get("failed")).intValue() == 0 && Boolean.TRUE.equals(st.get("hasFile")),
                "export finished: " + MiniJson.write(st));
        String m3u = e.jobResult(job);
        check(m3u != null && m3u.split("#EXTINF").length - 1 == 4 && !m3u.contains("localhost") && !m3u.contains("127.0.0.1:8765")
                && m3u.contains("http://127.0.0.1:" + pp + "/stream/1.ts?token=abc") && m3u.contains("/hls/5.m3u8"), "m3u has real links only");
        check(m3u.indexOf("group-title=\"Other\"") < m3u.indexOf("group-title=\"Tamil\"")
                && m3u.indexOf("group-title=\"Sports\"") < m3u.indexOf("group-title=\"Tamil\""), "sorted by group");
        check(m3u.contains("tvg-chno=\"1\"") && m3u.contains("misc/logos/320/1.png") && m3u.contains(",Star Sports  HD\n"), "m3u tags");
        check(e.jobFileName(job).matches("My_Portal_\\d{8}_\\d{4}\\.m3u"), "file name: " + e.jobFileName(job));

        // ---- expired token mid-way -> auto relogin during export
        new java.net.URL("http://127.0.0.1:" + pp + "/expire").openStream().close();
        x = call("export", "{\"key\":\"" + key + "\",\"filter\":{\"cats\":[\"1\"]}}");
        st = finish(String.valueOf(x.get("job")));
        check(((Number) st.get("ok")).intValue() == 2, "export survives expired token: " + MiniJson.write(st));

        // ---- errors
        Map<String, Object> bad = call("connect", "{\"source\":" + portal(pp, "00:1A:79:FF:FF:FF") + "}");
        check(String.valueOf(bad.get("error")).contains("Handshake failed") && String.valueOf(bad.get("error")).contains("refused"), "wrong MAC: " + bad.get("error"));
        bad = call("connect", "{\"source\":{\"type\":\"portal\",\"url\":\"http://127.0.0.1:9/c/\",\"mac\":\"00:1A:79:00:00:01\"}}");
        check(String.valueOf(bad.get("error")).contains("Cannot reach portal"), "dead portal: " + bad.get("error"));
        bad = call("connect", "{\"source\":{\"type\":\"portal\",\"url\":\"x.com\",\"mac\":\"00:11\"}}");
        check(String.valueOf(bad.get("error")).contains("MAC"), "bad MAC: " + bad.get("error"));
        bad = call("list", "{\"key\":\"nope\"}");
        check("nosession".equals(bad.get("code")), "unknown session -> nosession");
        bad = call("export", "{\"key\":\"" + key + "\",\"filter\":{\"q\":\"zzzz\"}}");
        check(String.valueOf(bad.get("error")).contains("No channels"), "empty selection");
        bad = call("status", "{\"job\":\"nope\"}");
        check("nojob".equals(bad.get("code")), "unknown job");
        check(e.call("bogus", "{}").contains("Unknown method"), "unknown method");
        check(e.call("list", "not json").contains("error"), "garbage args handled");

        // ---- cancel + retry
        // (portal that accepts nothing: all links fail -> stops early with a clear message)
        Map<String, Object> c2 = call("connect", "{\"source\":" + portal(pp, "00:1A:79:00:00:01").replace("\"sn\":\"SN1\"", "\"sn\":\"SN2\"") + "}");
        String key2 = String.valueOf(c2.get("key"));
        x = call("export", "{\"key\":\"" + key2 + "\",\"filter\":{}}");
        String j2 = String.valueOf(x.get("job"));
        call("cancel", "{\"job\":\"" + j2 + "\"}");
        st = finish(j2);
        check(Boolean.TRUE.equals(st.get("finished")), "cancel -> finishes: " + MiniJson.write(st));
        call("retry", "{\"job\":\"" + j2 + "\"}");
        st = finish(j2);
        check(((Number) st.get("ok")).intValue() == 5 && e.jobResult(j2).split("#EXTINF").length - 1 == 5, "retry completes the rest: " + MiniJson.write(st));

        // ---- selection helpers: ids per view, per category, hand-picked export
        Map<String, Object> l0 = call("list", "{\"key\":\"" + key + "\",\"filter\":{},\"offset\":0,\"limit\":2}");
        check(MiniJson.list(l0.get("ids")).size() == 5 && MiniJson.list(l0.get("rows")).size() == 2, "list returns all ids of the view");
        check(MiniJson.list(MiniJson.map(c.get("catIds")).get("1")).size() == 2, "connect returns channel ids per category");
        check(MiniJson.list(c.get("sids")).size() == 5 && !MiniJson.str(MiniJson.list(c.get("sids")).get(0)).isEmpty(), "stable ids per channel: " + MiniJson.write(c.get("sids")));
        Map<String, Object> xp = call("export", "{\"key\":\"" + key2 + "\",\"ids\":[0,2],\"filter\":{}}");
        Map<String, Object> sp = finish(String.valueOf(xp.get("job")));
        check(((Number) sp.get("total")).intValue() == 2 && ((Number) sp.get("ok")).intValue() == 2, "export only the hand-picked ids: " + MiniJson.write(sp));
        Map<String, Object> xe = call("export", "{\"key\":\"" + key2 + "\",\"ids\":[0,1,2,3,4],\"filter\":{\"exclude\":\"xxx\"}}");
        check(((Number) xe.get("total")).intValue() == 4, "exclude words still apply to picked ids");
        finish(String.valueOf(xe.get("job")));
        Map<String, Object> xn = call("export", "{\"key\":\"" + key2 + "\",\"ids\":[],\"filter\":{}}");
        check(String.valueOf(xn.get("error")).contains("No channels"), "empty pick -> clear error");

        // ---- disk cache: opening the same source again is instant and does not touch the portal
        java.io.File cd = java.nio.file.Files.createTempDirectory("m3ucache").toFile();
        Engine orig = e;
        Engine e1 = new Engine(); e1.setCacheDir(cd);
        e = e1;
        Map<String, Object> f1 = call("connect", "{\"source\":" + portal(pp, "00:1A:79:00:00:01") + "}");
        check(Boolean.FALSE.equals(f1.get("cached")) && cd.list().length == 1, "first connect loads from portal and writes cache");
        Engine e3 = new Engine(); e3.setCacheDir(cd);
        e = e3;
        long t0 = System.currentTimeMillis();
        Map<String, Object> f3 = call("connect", "{\"source\":" + portal(pp, "00:1A:79:00:00:01") + "}");
        check(Boolean.TRUE.equals(f3.get("cached")) && ((Number) f3.get("channels")).intValue() == 5 && System.currentTimeMillis() - t0 < 300, "second start comes from cache, instantly");
        Map<String, Object> xcc = call("export", "{\"key\":\"" + f3.get("key") + "\",\"filter\":{}}");
        Map<String, Object> sc = finish(String.valueOf(xcc.get("job")));
        check(((Number) sc.get("ok")).intValue() == 5, "export from a cached list logs in by itself: " + MiniJson.write(sc));
        Map<String, Object> f4 = call("connect", "{\"force\":true,\"source\":" + portal(pp, "00:1A:79:00:00:01") + "}");
        check(Boolean.FALSE.equals(f4.get("cached")), "force = fresh from portal");
        e = orig;
        check(Http.get("http://127.0.0.1:" + pp + "/_gz", new java.util.HashMap<>(), 3000, true).body.trim().compareTo("0") > 0, "portal replies were gzip-compressed and decoded");

        // ---- category files + hosting (fake GitHub gist API)
        if (a.length > 1) {
            String gp = a[1], api = "http://127.0.0.1:" + gp;
            st = call("status", "{\"job\":\"" + j2 + "\"}");
            List<Object> fl = MiniJson.list(st.get("files"));
            check(fl != null && fl.size() == 5 && String.valueOf(MiniJson.map(fl.get(0)).get("name")).endsWith("_Full_Channels.m3u"), "files = combined + 4 categories: " + MiniJson.write(fl));
            Map<String, Object> h0 = call("host", "{\"job\":\"" + j2 + "\",\"token\":\"nope\",\"api\":\"" + api + "\"}");
            check(String.valueOf(h0.get("error")).contains("token"), "bad token -> clear error: " + h0.get("error"));
            Map<String, Object> h1 = call("host", "{\"job\":\"" + j2 + "\",\"token\":\"good-token\",\"api\":\"" + api + "\"}");
            String all = String.valueOf(h1.get("all"));
            check(h1.get("error") == null && all.contains("/raw/playlist_Full_Channels.m3u") && !all.contains("aaaaaaaa"), "create secret gist, stable raw link: " + all);
            String body = Http.get(all, new java.util.HashMap<>(), 5000, true).body;
            check(body.equals(e.jobFile(j2, null)) && body.split("#EXTINF").length - 1 == 5, "hosted link serves the real m3u");
            String gid = String.valueOf(h1.get("gistId"));
            // refresh without category split -> same gist, same link, stale category files removed
            Map<String, Object> ex2 = call("export", "{\"key\":\"" + key2 + "\",\"filter\":{\"cats\":[\"1\"]},\"opts\":{\"split\":false}}");
            String j3 = String.valueOf(ex2.get("job"));
            Map<String, Object> st3 = finish(j3);
            List<Object> pf = new java.util.ArrayList<>();
            for (Object o : fl) pf.add(MiniJson.map(o).get("name"));
            Map<String, Object> arg = new java.util.LinkedHashMap<>();
            arg.put("job", j3); arg.put("token", "good-token"); arg.put("api", api); arg.put("gistId", gid); arg.put("prevFiles", pf);
            Map<String, Object> h2 = call("host", MiniJson.write(arg));
            check(h2.get("error") == null && gid.equals(String.valueOf(h2.get("gistId"))) && all.equals(String.valueOf(h2.get("all")))
                    && MiniJson.list(h2.get("files")).size() == 1 && Boolean.FALSE.equals(h2.get("relinked")), "PATCH update keeps the same link (chunked reply ok): " + MiniJson.write(h2));
            String body2 = Http.get(all, new java.util.HashMap<>(), 5000, true).body;
            check(body2.equals(e.jobFile(j3, null)) && !body2.equals(body), "same link now serves the refreshed playlist");
            arg.put("gistId", "gone999");
            Map<String, Object> h3 = call("host", MiniJson.write(arg));
            check(h3.get("error") == null && Boolean.TRUE.equals(h3.get("relinked")) && !"gone999".equals(h3.get("gistId")), "deleted gist -> new gist, relinked=true");
        }

        // ---- Xtream (direct links)
        String xs = "{\"type\":\"xtream\",\"name\":\"X\",\"url\":\"127.0.0.1:" + pp + "\",\"username\":\"user\",\"password\":\"pass\"}";
        Map<String, Object> xc = call("connect", "{\"source\":" + xs + "}");
        check(xc.get("error") == null && ((Number) xc.get("channels")).intValue() == 2, "xtream connect: " + MiniJson.write(xc));
        String xkey = String.valueOf(xc.get("key"));
        x = call("export", "{\"key\":\"" + xkey + "\",\"filter\":{\"cats\":[\"10\"]}}");
        st = finish(String.valueOf(x.get("job")));
        String xm = e.jobResult(String.valueOf(x.get("job")));
        check(xm != null && xm.contains("http://127.0.0.1:" + pp + "/live/user/pass/100.ts") && !xm.contains("101.ts")
                && xm.contains("x-tvg-url=\"http://127.0.0.1:" + pp + "/xmltv.php"), "xtream export: " + xm);
        bad = call("connect", "{\"source\":" + xs.replace("\"password\":\"pass\"", "\"password\":\"WRONG\"") + "}");
        check(bad.get("error") != null, "xtream wrong password -> error: " + bad.get("error"));

        System.out.println(fails == 0 ? "\nALL OK" : "\nFAILED: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
