package app.m3uconv.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
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

    /**
     * Any method incl. PATCH (HttpURLConnection cannot send PATCH). Tiny HTTP/1.1 client over a plain or TLS socket.
     * TLS uses the platform trust store and checks the host name.
     */
    public static Resp request(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        if (!method.equals("PATCH")) return viaUrlConnection(method, url, headers, body, timeoutMs);
        URL u = new URL(url);
        boolean ssl = u.getProtocol().equalsIgnoreCase("https");
        int port = u.getPort() > 0 ? u.getPort() : (ssl ? 443 : 80);
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(u.getHost(), port), timeoutMs);
        plain.setSoTimeout(timeoutMs);
        Socket sock = plain;
        if (ssl) {
            SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, u.getHost(), port, true);
            SSLParameters sp = ss.getSSLParameters();
            sp.setEndpointIdentificationAlgorithm("HTTPS");
            ss.setSSLParameters(sp);
            ss.startHandshake();
            sock = ss;
        }
        try {
            String path = u.getFile().isEmpty() ? "/" : u.getFile();
            StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(u.getHost()).append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\n");
            sb.append("Connection: close\r\nAccept-Encoding: identity\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            if (body != null) sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("\r\n");
            OutputStream os = sock.getOutputStream();
            os.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) os.write(body);
            os.flush();
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            String status = line(in);
            String[] sl = status.split(" ", 3);
            if (sl.length < 2 || !sl[0].startsWith("HTTP/")) throw new IOException("Bad HTTP reply");
            Resp r = new Resp();
            r.status = Integer.parseInt(sl[1].trim());
            boolean chunked = false;
            long len = -1;
            String h;
            while (!(h = line(in)).isEmpty()) {
                int i = h.indexOf(':');
                if (i <= 0) continue;
                String k = h.substring(0, i).trim(), v = h.substring(i + 1).trim();
                List<String> l = r.headers.get(k);
                if (l == null) r.headers.put(k, l = new ArrayList<>());
                l.add(v);
                if (k.equalsIgnoreCase("transfer-encoding") && v.toLowerCase().contains("chunked")) chunked = true;
                if (k.equalsIgnoreCase("content-length")) {
                    try {
                        len = Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (chunked) {
                while (true) {
                    String cl = line(in);
                    int sc = cl.indexOf(';');
                    if (sc >= 0) cl = cl.substring(0, sc);
                    int n = Integer.parseInt(cl.trim(), 16);
                    if (n == 0) break;
                    copy(in, bo, n);
                    line(in);
                }
            } else if (len >= 0) {
                copy(in, bo, len);
            } else {
                bo.write(readAll(in));
            }
            r.body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            return r;
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Resp viaUrlConnection(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod(method);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
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
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c < 0 && sb.length() == 0) throw new IOException("Connection closed");
        return sb.toString();
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, long n) throws IOException {
        byte[] buf = new byte[16384];
        while (n > 0) {
            int k = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (k < 0) throw new IOException("Connection closed early");
            out.write(buf, 0, k);
            n -= k;
        }
    }
}
