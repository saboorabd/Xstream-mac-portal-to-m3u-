import app.m3uconv.core.*;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Plain-JVM test of the core (no Android needed). Needs a fake portal on PORTAL_PORT
 * (python3 tests/mock_portal.py style server from the web repo).
 *   javac -d /tmp/out $(find app/src/main/java/app/m3uconv/core -name '*.java') tools/CoreSmoke.java
 *   java -cp /tmp/out CoreSmoke <portalPort>
 */
public class CoreSmoke {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) fails++;
    }

    static String[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(Http.readAll(in), StandardCharsets.UTF_8);
        String loc = c.getHeaderField("Location");
        return new String[]{Integer.toString(code), body, loc == null ? "" : loc, c.getContentType()};
    }

    static String q(String k, String v) throws Exception {
        return k + "=" + URLEncoder.encode(v, "UTF-8");
    }

    public static void main(String[] a) throws Exception {
        String pp = a[0];
        LocalServer srv = new LocalServer(0, () -> "<html>MAC Portal</html>");
        int port = srv.start();
        String o = "http://127.0.0.1:" + port;
        String cfg = q("url", "http://127.0.0.1:" + pp + "/stalker_portal/c/") + "&" + q("mac", "00-1a-79-00-00-01")
                + "&" + q("d1", "DDDD") + "&" + q("d2", "DDDD") + "&" + q("sn", "SN0000000000000");

        String[] r = get(o + "/");
        check(r[0].equals("200") && r[1].contains("MAC Portal"), "index served");

        r = get(o + "/api/portal/test?" + cfg);
        check(r[0].equals("200") && r[1].contains("\"channels\":5") && r[1].contains("\"expires\":\"2027-01-31\"")
                && r[1].contains("\"sample_link_host\":\"127.0.0.1:" + pp + "\""), "test endpoint: " + r[1]);

        r = get(o + "/api/portal/categories?" + cfg + "&" + q("keywords", "tamil") + "&" + q("exclude", "adult"));
        check(r[1].contains("\"name\":\"Tamil\",\"count\":2,\"matched\":true")
                && r[1].contains("\"name\":\"Sports\",\"count\":1,\"matched\":false")
                && r[1].contains("\"name\":\"Other\""), "categories + counts + filter: " + r[1]);

        r = get(o + "/portal.m3u?" + cfg + "&sort=1&" + q("epg", "http://epg.example/x.xml"));
        String m = r[1];
        check(m.startsWith("#EXTM3U x-tvg-url=\"http://epg.example/x.xml\"\n"), "m3u header + epg");
        check(m.split("#EXTINF").length - 1 == 5, "5 channels in m3u");
        check(m.contains("group-title=\"Other\""), "unknown genre kept as Other");
        check(m.contains("tvg-chno=\"1\""), "chno");
        check(m.contains("tvg-logo=\"http://127.0.0.1:" + pp + "/stalker_portal/misc/logos/320/1.png\""), "absolute logo");
        check(m.contains(",Star Sports  HD\n"), "comma removed from title");
        check(r[3].startsWith("audio/x-mpegurl"), "content-type");

        r = get(o + "/portal.m3u?" + cfg + "&" + q("keywords", "tamil"));
        check(r[1].split("#EXTINF").length - 1 == 2, "keyword filter");
        r = get(o + "/portal.m3u?" + cfg + "&cats=other,2");
        check(r[1].split("#EXTINF").length - 1 == 2, "cats filter");

        // play: take first play URL from the playlist and follow it like a player
        String playUrl = null;
        for (String line : get(o + "/portal.m3u?" + cfg + "&" + q("keywords", "tamil")).clone()[1].split("\n")) {
            if (line.startsWith("http")) {
                playUrl = line;
                break;
            }
        }
        r = get(playUrl);
        check(r[0].equals("302") && r[2].equals("http://127.0.0.1:" + pp + "/stream/1.ts?token=abc") || r[2].contains("/hls/5.m3u8")
                || r[2].contains("/stream/"), "play redirects to real stream: " + r[0] + " " + r[2]);

        // token expiry -> transparent re-login
        get("http://127.0.0.1:" + pp + "/expire");
        r = get(playUrl);
        check(r[0].equals("302") && r[2].startsWith("http://127.0.0.1:" + pp + "/"), "play survives token expiry");

        r = get(o + "/api/portal/test?" + q("url", "http://127.0.0.1:" + pp + "/stalker_portal/c/") + "&" + q("mac", "00:1A:79:FF:FF:FF"));
        check(r[0].equals("502") && r[1].contains("Handshake failed") && r[1].contains("refused"), "wrong MAC -> clear error: " + r[1]);

        r = get(o + "/portal.m3u?url=&mac=");
        check(r[0].equals("400"), "missing fields -> 400");
        r = get(o + "/portal.m3u?" + q("url", "http://127.0.0.1:" + pp + "/c/") + "&" + q("mac", "00:11"));
        check(r[0].equals("400") && r[1].contains("MAC"), "bad MAC -> 400");
        r = get(o + "/portal/play?cfg=%25%25&cmd=x");
        check(r[0].equals("400"), "bad cfg -> 400");
        r = get(o + "/api/portal/test?" + q("url", "http://127.0.0.1:9/stalker_portal/c/") + "&" + q("mac", "00:1A:79:00:00:01"));
        check(r[0].equals("502") && r[1].contains("Cannot reach portal"), "dead portal -> clean error: " + r[1]);

        // Xtream
        r = get(o + "/127.0.0.1:" + pp + "/user/pass.m3u?keywords=tamil");
        check(r[0].equals("200") && r[1].contains("http://127.0.0.1:" + pp + "/live/user/pass/100.ts")
                && !r[1].contains("101.ts") && r[1].contains("x-tvg-url="), "xtream playlist: " + r[1]);
        r = get(o + "/127.0.0.1:" + pp + "/user/WRONG.m3u");
        check(r[0].equals("502") || r[0].equals("401"), "xtream bad login -> error (" + r[0] + ")");
        r = get(o + "/api/xtream/categories?" + q("url", "127.0.0.1:" + pp) + "&username=user&password=pass&exclude=adult");
        check(r[1].contains("\"matched\":true") && r[1].contains("\"matched\":false"), "xtream categories");

        // security
        try (java.net.Socket so = new java.net.Socket("127.0.0.1", port)) {
            so.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.example\r\n\r\n".getBytes());
            String first = new java.io.BufferedReader(new java.io.InputStreamReader(so.getInputStream())).readLine();
            check(first != null && first.contains("403"), "foreign Host header rejected: " + first);
        }
        r = get(o + "/nope");
        check(r[0].equals("404"), "404");

        // MiniJson
        Object j = MiniJson.parse("{\"a\":[1,2.5,\"x\\u0041\\n\",true,null],\"b\":{}}");
        check(MiniJson.map(j).get("a") instanceof java.util.List && "xA\n".equals(MiniJson.list(MiniJson.map(j).get("a")).get(2)),
                "MiniJson parse");
        check(MiniJson.parseLenient("junk{\"js\":{}}junk") != null, "MiniJson lenient");

        srv.stop();
        System.out.println(fails == 0 ? "\nALL OK" : "\nFAILED: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
