package app.m3uconv.core;

import java.util.List;

public final class M3u {
    private M3u() {
    }

    public static final class Entry {
        public String name = "", logo = "", group = "", tvgId = "", chno = "", url = "";
    }

    public static String build(List<Entry> items, String epg) {
        StringBuilder sb = new StringBuilder("#EXTM3U");
        if (epg != null && !epg.isEmpty()) sb.append(" x-tvg-url=\"").append(Util.esc(epg)).append('"');
        sb.append('\n');
        for (Entry e : items) {
            sb.append("#EXTINF:-1 ");
            if (!e.chno.isEmpty()) sb.append("tvg-chno=\"").append(Util.esc(e.chno)).append("\" ");
            sb.append("tvg-id=\"").append(Util.esc(e.tvgId)).append("\" tvg-name=\"").append(Util.esc(e.name))
                    .append("\" tvg-logo=\"").append(Util.esc(e.logo)).append("\" group-title=\"")
                    .append(Util.esc(e.group)).append("\",").append(Util.esc(e.name).replace(",", " ")).append('\n');
            sb.append(e.url).append('\n');
        }
        return sb.toString();
    }
}
