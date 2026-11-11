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
