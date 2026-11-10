package app.m3uconv.core;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class Util {
    private Util() {
    }

    public static List<String> kwList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String k : s.split(",")) {
            k = k.trim().toLowerCase();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    public static boolean matches(String name, List<String> inc, List<String> exc) {
        String n = name == null ? "" : name.toLowerCase();
        boolean ok = inc.isEmpty();
        for (String k : inc) if (n.contains(k)) ok = true;
        if (!ok) return false;
        for (String k : exc) if (n.contains(k)) return false;
        return true;
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Like Python's quote(s, safe=""): spaces become %20, not '+'. */
    public static String quote(String s) {
        return enc(s).replace("+", "%20");
    }

    public static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    /** Decode a URL path segment ('+' stays '+'). */
    public static String decPath(String s) {
        return dec(s.replace("+", "%2B"));
    }

    public static String hex(String algo, String s) {
        try {
            byte[] d = MessageDigest.getInstance(algo).digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String esc(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }
}
