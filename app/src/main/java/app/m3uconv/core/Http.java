package app.m3uconv.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal GET helper on top of HttpURLConnection. */
public final class Http {
    private Http() {
    }

    public static final class Resp {
        public int status;
        public String body = "";
        public final Map<String, List<String>> headers = new HashMap<>();

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return null;
        }

        public List<String> headerAll(String name) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) out.addAll(e.getValue());
            }
            return out;
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    public static Resp get(String url, Map<String, String> headers, int timeoutMs, boolean follow) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(follow);
        c.setRequestMethod("GET");
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            Resp r = new Resp();
            r.status = code;
            if (in != null) {
                try {
                    r.body = new String(readAll(in), StandardCharsets.UTF_8);
                } finally {
                    in.close();
                }
            }
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) r.headers.put(e.getKey(), e.getValue());
            }
            return r;
        } finally {
            c.disconnect();
        }
    }
}
