package app.m3uconv.core;

/** One channel of a source. Portal channels have a 'cmd' (resolved on export); Xtream channels have a direct 'url'. */
public final class Item {
    public int uid;
    public String name = "", group = "", groupId = "", logo = "", tvgId = "", chno = "", cmd = "", url = "";
    /** Stable id from the provider (survives list refreshes; used to remember hand-picked channels). */
    public String sid = "";
}
