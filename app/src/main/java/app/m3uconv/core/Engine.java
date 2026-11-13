package app.m3uconv.core;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The whole app logic behind one call: engine.call("method", "{json args}") -> "{json result}".
 * No server, no sockets - the Android UI talks to this directly. Pure Java, so it is tested on a normal JVM.
 */
public final class Engine {
    static final class EngineError extends RuntimeException {
        final String code;

        EngineError(String code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private static final class Session {
        String type;
        Stalker portal;
        List<Item> items;
        List<Object> cats;
        String expires = "";
        String epg = "";
        long ts = System.currentTimeMillis();
        long loadedAt = System.currentTimeMillis();
        boolean fromCache;
        Map<String, Object> catIds;
    }

    private static final class Row {
        final Item item;
        volatile String url;

        Row(Item i) {
            item = i;
            url = i.url;
        }
    }

    private static final class Job {
        final Session s;
        final List<Row> rows = new ArrayList<>();
        final int threads, retries;
        final boolean sort;
        final String epg, fileName;
        final AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger(), ok = new AtomicInteger();
        volatile int runTotal;
        volatile boolean finished, cancelled;
        volatile String error, m3u;
        volatile Map<String, String> files;          // file name -> playlist text (combined first, then one per category)
        volatile Map<String, Integer> counts;
        String base = "playlist";
        boolean split = true;
        volatile long startMs, endMs;

        Job(Session s, int threads, int retries, boolean sort, String epg, String fileName) {
            this.s = s;
            this.threads = threads;
            this.retries = retries;
            this.sort = sort;
            this.epg = epg;
            this.fileName = fileName;
        }
    }

    private volatile File cacheDir;
    private volatile String phase = "";
    static final long MEM_TTL = 30 * 60 * 1000L, DISK_TTL = 6 * 60 * 60 * 1000L;

    /** Where the channel list of each source is remembered, so opening a source again is instant. */
    public void setCacheDir(File d) {
        cacheDir = d;
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- entry point
    public String call(String method, String argsJson) {
        try {
            Map<String, Object> a = MiniJson.map(MiniJson.parse(argsJson == null || argsJson.trim().isEmpty() ? "{}" : argsJson));
            if (a == null) a = new LinkedHashMap<>();
            Object r;
            switch (method) {
                case "connect": r = connect(a); break;
                case "list": r = list(a); break;
                case "export": r = export(a); break;
                case "status": r = status(a); break;
                case "cancel": r = cancel(a); break;
                case "retry": r = retry(a); break;
                case "resolve": r = resolve(a); break;
                case "host": r = host(a); break;
                case "phase": r = phaseInfo(); break;
                default: throw new IllegalArgumentException("Unknown method " + method);
            }
            return MiniJson.write(r);
        } catch (EngineError e) {
            return err(e.getMessage(), e.code);
        } catch (PortalException e) {
            return err(e.getMessage(), "portal");
        } catch (IllegalArgumentException e) {
            return err(String.valueOf(e.getMessage()), "bad");
        } catch (RuntimeException e) {
            return err("Internal error: " + e.getClass().getSimpleName() + " " + e.getMessage(), "internal");
        }
    }

    private static String err(String msg, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        m.put("code", code);
        return MiniJson.write(m);
    }

    /** Playlist text of a finished job (used by the Android layer to save the file). */
    public String jobResult(String id) {
        Job j = jobs.get(id);
        return j == null ? null : j.m3u;
    }

    /** One generated file of a finished job (null name = the combined playlist). */
    public String jobFile(String id, String name) {
        Job j = jobs.get(id);
        if (j == null) return null;
        if (name == null || name.isEmpty() || j.files == null) return j.m3u;
        return j.files.get(name);
    }

    public String jobFileName(String id) {
        Job j = jobs.get(id);
        return j == null ? "playlist.m3u" : j.fileName;
    }

    // ---------------------------------------------------------------- connect
    private static String s(Map<String, Object> m, String k) {
        return MiniJson.str(m.get(k)).trim();
    }

    private static int intArg(Map<String, Object> m, String k, int def) {
        try {
            return Integer.parseInt(MiniJson.str(m.get(k)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Object connect(Map<String, Object> a) {
        Map<String, Object> src = MiniJson.map(a.get("source"));
        if (src == null) throw new IllegalArgumentException("source missing");
        String type = s(src, "type");
        boolean force = Boolean.TRUE.equals(a.get("force"));
        Map<String, Object> canon = new TreeMap<>(src);
        canon.remove("name");
        canon.remove("epg");
        String key = Util.hex("SHA-256", MiniJson.write(canon));
        Session sess = sessions.get(key);
        if (sess == null || force || System.currentTimeMillis() - sess.ts > MEM_TTL) {
            Session c = force ? null : readCache(key, src);
            if (c != null) {
                sess = c;
            } else {
                phase = "Connecting…";
                try {
                    sess = type.equals("xtream") ? connectXtream(src) : connectPortal(src);
                } finally {
                    phase = "";
                }
                writeCache(key, sess);
            }
            if (sessions.size() > 8) sessions.clear();
            sessions.put(key, sess);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("type", sess.type);
        r.put("channels", sess.items.size());
        r.put("categories", sess.cats);
        r.put("expires", sess.expires);
        r.put("loadedAt", sess.loadedAt);
        r.put("cached", sess.fromCache);
        r.put("catIds", catIds(sess));
        List<Object> sids = new ArrayList<>(sess.items.size());
        for (Item it : sess.items) sids.add(it.sid.isEmpty() ? it.name : it.sid);
        r.put("sids", sids);
        return r;
    }

    private Object phaseInfo() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("phase", phase);
        return r;
    }

    private static Map<String, Object> catIds(Session sess) {
        if (sess.catIds == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Item it : sess.items) {
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) m.get(it.groupId);
                if (l == null) m.put(it.groupId, l = new ArrayList<>());
                l.add(it.uid);
            }
            sess.catIds = m;
        }
        return sess.catIds;
    }

    // ---------------------------------------------------------------- disk cache
    private File cacheFile(String key) {
        File d = cacheDir;
        return d == null ? null : new File(d, "src_" + key.substring(0, 24) + ".json.gz");
    }

    private void writeCache(String key, Session sess) {
        File f = cacheFile(key);
        if (f == null) return;
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", sess.loadedAt);
            m.put("type", sess.type);
            m.put("cats", sess.cats);
            m.put("expires", sess.expires);
            m.put("epg", sess.epg);
            List<Object> rows = new ArrayList<>(sess.items.size());
            for (Item it : sess.items) {
                List<Object> r = new ArrayList<>(8);
                r.add(it.name);
                r.add(it.group);
                r.add(it.groupId);
                r.add(it.logo);
                r.add(it.tvgId);
                r.add(it.chno);
                r.add(it.cmd);
                r.add(it.url);
                r.add(it.sid);
                rows.add(r);
            }
            m.put("items", rows);
            File tmp = new File(f.getPath() + ".tmp");
            try (java.io.OutputStream os = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(tmp))) {
                os.write(MiniJson.write(m).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {                     // the cache is only a speed-up
        }
    }

    private Session readCache(String key, Map<String, Object> src) {
        File f = cacheFile(key);
        if (f == null || !f.isFile()) return null;
        try {
            Map<String, Object> m;
            try (java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(f))) {
                m = MiniJson.map(MiniJson.parse(new String(Http.readAll(in), java.nio.charset.StandardCharsets.UTF_8)));
            }
            if (m == null) return null;
            long ts = Long.parseLong(MiniJson.str(m.get("ts")));
            if (System.currentTimeMillis() - ts > DISK_TTL) return null;
            Session sess = new Session();
            sess.type = MiniJson.str(m.get("type"));
            sess.loadedAt = ts;
            sess.fromCache = true;
            sess.expires = MiniJson.str(m.get("expires"));
            sess.epg = MiniJson.str(m.get("epg"));
            sess.cats = MiniJson.list(m.get("cats"));
            sess.items = new ArrayList<>();
            for (Object o : MiniJson.list(m.get("items"))) {
                List<Object> r = MiniJson.list(o);
                Item it = new Item();
                it.name = MiniJson.str(r.get(0));
                it.group = MiniJson.str(r.get(1));
                it.groupId = MiniJson.str(r.get(2));
                it.logo = MiniJson.str(r.get(3));
                it.tvgId = MiniJson.str(r.get(4));
                it.chno = MiniJson.str(r.get(5));
                it.cmd = MiniJson.str(r.get(6));
                it.url = MiniJson.str(r.get(7));
                it.sid = r.size() > 8 ? MiniJson.str(r.get(8)) : "";
                sess.items.add(it);
            }
            if (sess.items.isEmpty() || sess.cats == null) return null;
            number(sess.items);
            if (sess.type.equals("portal")) {             // not logged in yet: it happens by itself on the first real request
                sess.portal = new Stalker(s(src, "url"), s(src, "mac"), s(src, "d1"), s(src, "d2"), s(src, "sn"), 90000);
            }
            return sess;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    private Session connectPortal(Map<String, Object> src) {
        Stalker p = new Stalker(s(src, "url"), s(src, "mac"), s(src, "d1"), s(src, "d2"), s(src, "sn"), 90000);
        Stalker.Loaded ld = p.loadAll(x -> phase = x);
        Session sess = new Session();
        sess.type = "portal";
        sess.portal = p;
        sess.items = new ArrayList<>(ld.items.size());
        for (Stalker.Channel c : ld.items) {
            Item it = new Item();
            it.name = c.name;
            it.group = c.group;
            it.groupId = c.genreId;
            it.logo = c.logo;
            it.tvgId = c.tvgId;
            it.chno = c.chno;
            it.cmd = c.cmd;
            it.sid = c.id;
            sess.items.add(it);
        }
        sess.cats = new ArrayList<>();
        for (Stalker.Category c : ld.cats) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id);
            m.put("name", c.name);
            m.put("count", c.count);
            sess.cats.add(m);
        }
        sess.expires = ld.expires;
        sess.epg = "";
        number(sess.items);
        return sess;
    }

    private Session connectXtream(Map<String, Object> src) {
        String base = Xtream.base(s(src, "url"));
        String user = s(src, "username"), pass = s(src, "password");
        if (user.isEmpty() || pass.isEmpty()) throw new IllegalArgumentException("Username and password are required");
        String kind = s(src, "kind").equals("vod") ? "vod" : "live";
        String fmt = s(src, "fmt").equals("m3u8") ? "m3u8" : "ts";
        phase = "Loading channels…";
        Xtream.Result r = Xtream.load(base, user, pass, kind, fmt);
        Session sess = new Session();
        sess.type = "xtream";
        sess.items = r.items;
        sess.cats = r.cats;
        sess.expires = r.expires;
        sess.epg = r.epg;
        number(sess.items);
        return sess;
    }

    private static void number(List<Item> items) {
        for (int i = 0; i < items.size(); i++) items.get(i).uid = i;
    }

    private Session session(Map<String, Object> a) {
        Session sess = sessions.get(s(a, "key"));
        if (sess == null) throw new EngineError("nosession", "Session expired - reconnecting");
        sess.ts = System.currentTimeMillis();
        return sess;
    }

    // ---------------------------------------------------------------- browse / filter
    private static boolean containsAny(String text, List<String> words) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    private List<Item> select(Session sess, Map<String, Object> filter) {
        Set<String> cats = new HashSet<>();
        List<String> q = new ArrayList<>(), exc = new ArrayList<>();
        if (filter != null) {
            List<Object> cl = MiniJson.list(filter.get("cats"));
            if (cl != null) for (Object o : cl) cats.add(MiniJson.str(o));
            q = Util.kwList(MiniJson.str(filter.get("q")));
            exc = Util.kwList(MiniJson.str(filter.get("exclude")));
        }
        List<Item> out = new ArrayList<>();
        for (Item it : sess.items) {
            if (!cats.isEmpty() && !cats.contains(it.groupId)) continue;
            if (!q.isEmpty() && !containsAny(it.name, q)) continue;
            if (!exc.isEmpty() && (containsAny(it.name, exc) || containsAny(it.group, exc))) continue;
            out.add(it);
        }
        return out;
    }

    private Object list(Map<String, Object> a) {
        Session sess = session(a);
        List<Item> sel = select(sess, MiniJson.map(a.get("filter")));
        int off = Math.max(0, intArg(a, "offset", 0));
        int lim = Math.min(200, Math.max(1, intArg(a, "limit", 60)));
        List<Object> rows = new ArrayList<>();
        for (int i = off; i < sel.size() && i < off + lim; i++) {
            Item it = sel.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.uid);
            m.put("name", it.name);
            m.put("group", it.group);
            m.put("logo", it.logo);
            rows.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", sel.size());
        r.put("rows", rows);
        if (off == 0) {                                  // every id in the current view, for "select all shown"
            List<Object> ids = new ArrayList<>(sel.size());
            for (Item it : sel) ids.add(it.uid);
            r.put("ids", ids);
        }
        return r;
    }

    private Object resolve(Map<String, Object> a) {
        Session sess = session(a);
        int id = intArg(a, "id", -1);
        if (id < 0 || id >= sess.items.size()) throw new IllegalArgumentException("Unknown channel");
        Item it = sess.items.get(id);
        String url = it.url;
        if (url.isEmpty()) {
            try {
                url = sess.portal.link(it.cmd);
            } catch (PortalException e) {
                sess.portal.login();
                url = sess.portal.link(it.cmd);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("url", url);
        r.put("name", it.name);
        return r;
    }

    // ---------------------------------------------------------------- export jobs
    private static String safeName(String n) {
        String x = n == null ? "" : n.replaceAll("[^A-Za-z0-9 _.-]", "").trim().replace(' ', '_');
        return x.isEmpty() ? "playlist" : x;
    }

    private Object export(Map<String, Object> a) {
        Session sess = session(a);
        List<Item> sel;
        List<Object> pick = MiniJson.list(a.get("ids"));
        if (pick != null) {                              // hand-picked channels
            Set<Integer> want = new HashSet<>();
            for (Object o : pick) {
                try {
                    want.add(Integer.parseInt(MiniJson.str(o)));
                } catch (NumberFormatException ignored) {
                }
            }
            Map<String, Object> f = new LinkedHashMap<>();
            Map<String, Object> fin = MiniJson.map(a.get("filter"));
            if (fin != null) f.put("exclude", fin.get("exclude"));        // exclude words still protect the export
            sel = new ArrayList<>();
            for (Item it : select(sess, f)) if (want.contains(it.uid)) sel.add(it);
        } else {
            sel = select(sess, MiniJson.map(a.get("filter")));
        }
        if (sel.isEmpty()) throw new IllegalArgumentException("No channels selected");
        Map<String, Object> o = MiniJson.map(a.get("opts"));
        if (o == null) o = new LinkedHashMap<>();
        int threads = Math.min(16, Math.max(1, intArg(o, "threads", 8)));
        int retries = Math.min(5, Math.max(1, intArg(o, "retries", 3)));
        boolean sort = !Boolean.FALSE.equals(o.get("sort"));
        String epg = s(a, "epg");
        if (!epg.toLowerCase(Locale.ROOT).startsWith("http")) epg = sess.epg;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        final Job job = new Job(sess, threads, retries, sort, epg, safeName(s(a, "name")) + "_" + stamp + ".m3u");
        job.base = safeName(s(a, "name"));
        job.split = !Boolean.FALSE.equals(o.get("split"));
        for (Item it : sel) job.rows.add(new Row(it));
        String id = UUID.randomUUID().toString().substring(0, 8);
        if (jobs.size() >= 6) jobs.clear();
        jobs.put(id, job);
        startRun(job);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("job", id);
        r.put("total", job.rows.size());
        return r;
    }

    private Job job(Map<String, Object> a) {
        Job j = jobs.get(s(a, "job"));
        if (j == null) throw new EngineError("nojob", "Export not found");
        return j;
    }

    private void startRun(final Job job) {
        job.finished = false;
        job.error = null;
        job.startMs = System.currentTimeMillis();
        job.done.set(0);
        job.failed.set(0);
        job.runTotal = 0;
        Thread t = new Thread(() -> runJob(job), "m3u-export");
        t.setDaemon(true);
        t.start();
    }

    private void runJob(final Job job) {
        final List<Row> todo = new ArrayList<>();
        int already = 0;
        for (Row r : job.rows) {
            if (r.url.isEmpty()) todo.add(r);
            else already++;                       // Xtream rows (direct links) or links resolved in an earlier run
        }
        job.ok.set(already);
        job.runTotal = todo.size();
        ExecutorService pool = Executors.newFixedThreadPool(job.threads);
        try {
            if (!todo.isEmpty() && job.s.portal != null) {
                job.s.portal.login();                          // one clean handshake before the parallel part
            }
            for (final Row row : todo) {
                pool.execute(() -> {
                    if (job.cancelled) {
                        job.done.incrementAndGet();
                        return;
                    }
                    String url = "";
                    for (int attempt = 0; attempt < job.retries && url.isEmpty() && !job.cancelled; attempt++) {
                        try {
                            url = job.s.portal.link(row.item.cmd);
                        } catch (PortalException e) {
                            try {
                                Thread.sleep(300L * (attempt + 1));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    if (url.isEmpty()) {
                        job.failed.incrementAndGet();
                        // nothing works at all (expired account / blocked)? stop early instead of waiting for hours
                        if (job.ok.get() == 0 && job.failed.get() >= 20) job.cancelled = true;
                    } else {
                        row.url = url;
                        job.ok.incrementAndGet();
                    }
                    job.done.incrementAndGet();
                });
            }
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.HOURS);
            if (job.ok.get() == 0) {
                job.error = job.cancelled && job.failed.get() < 20 ? "Cancelled"
                        : "Portal gave no playable links - account expired, blocked, or too many devices on this MAC?";
            } else {
                build(job);
            }
        } catch (PortalException e) {
            job.error = e.getMessage();
        } catch (Exception e) {
            job.error = "Export failed: " + e.getClass().getSimpleName();
        } finally {
            pool.shutdownNow();
            job.endMs = System.currentTimeMillis();
            job.finished = true;
        }
    }

    private static void build(Job job) {
        List<M3u.Entry> entries = new ArrayList<>();
        for (Row r : job.rows) {
            if (r.url.isEmpty()) continue;
            M3u.Entry e = new M3u.Entry();
            e.name = r.item.name;
            e.logo = r.item.logo;
            e.group = r.item.group;
            e.tvgId = r.item.tvgId;
            e.chno = r.item.chno;
            e.url = r.url;
            entries.add(e);
        }
        if (job.sort) {
            Collections.sort(entries, (x, y) -> {
                int g = x.group.toLowerCase(Locale.ROOT).compareTo(y.group.toLowerCase(Locale.ROOT));
                return g != 0 ? g : x.name.toLowerCase(Locale.ROOT).compareTo(y.name.toLowerCase(Locale.ROOT));
            });
        }
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        String all = M3u.build(entries, job.epg);
        String allName = job.base + "_Full_Channels.m3u";
        files.put(allName, all);
        counts.put(allName, entries.size());
        if (job.split) {
            Map<String, List<M3u.Entry>> byGroup = new LinkedHashMap<>();
            for (M3u.Entry e : entries) {
                List<M3u.Entry> l = byGroup.get(e.group);
                if (l == null) byGroup.put(e.group, l = new ArrayList<>());
                l.add(e);
            }
            for (Map.Entry<String, List<M3u.Entry>> g : byGroup.entrySet()) {
                String nm = safeName(g.getKey());
                if (nm.equals("playlist")) nm = "Group";
                String fn = job.base + "_" + nm + ".m3u";
                for (int n = 2; files.containsKey(fn); n++) fn = job.base + "_" + nm + "_" + n + ".m3u";
                files.put(fn, M3u.build(g.getValue(), job.epg));
                counts.put(fn, g.getValue().size());
            }
        }
        job.counts = counts;
        job.files = files;
        job.m3u = all;
    }

    private Object status(Map<String, Object> a) {
        Job j = job(a);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", j.runTotal);
        r.put("done", j.done.get());
        r.put("failed", j.failed.get());
        r.put("ok", j.ok.get());
        r.put("rows", j.rows.size());
        r.put("finished", j.finished);
        r.put("cancelled", j.cancelled);
        if (j.error != null) r.put("error", j.error);
        r.put("elapsedMs", (j.finished ? j.endMs : System.currentTimeMillis()) - j.startMs);
        r.put("hasFile", j.m3u != null);
        if (j.files != null && j.m3u != null) {
            List<Object> fl = new ArrayList<>();
            for (Map.Entry<String, String> f : j.files.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.getKey());
                m.put("count", j.counts.get(f.getKey()));
                m.put("size", f.getValue().length());
                fl.add(m);
            }
            r.put("files", fl);
        }
        return r;
    }

    private Object cancel(Map<String, Object> a) {
        job(a).cancelled = true;
        return new LinkedHashMap<String, Object>();
    }

    /** Try the failed / unprocessed channels again. */
    private Object retry(Map<String, Object> a) {
        Job j = job(a);
        if (!j.finished) throw new IllegalArgumentException("Still running");
        j.cancelled = false;
        j.m3u = null;
        j.files = null;
        startRun(j);
        return new LinkedHashMap<String, Object>();
    }

    // ---------------------------------------------------------------- hosting (secret GitHub Gist)
    private static String ghError(Http.Resp r) {
        String msg = "";
        try {
            msg = MiniJson.str(MiniJson.map(MiniJson.parseLenient(r.body)).get("message"));
        } catch (RuntimeException ignored) {
        }
        switch (r.status) {
            case 401:
                return "GitHub rejected the token - create a token that has the 'gist' permission";
            case 403:
            case 429:
                return "GitHub refused (token lacks the 'gist' permission, or rate limit). " + msg;
            case 422:
                return "GitHub rejected the files: " + msg;
            default:
                return "GitHub error HTTP " + r.status + (msg.isEmpty() ? "" : ": " + msg);
        }
    }

    /**
     * Uploads the finished playlist(s) to a SECRET gist and returns permanent links.
     * Pass the previous gistId to update the same gist, so the links stay the same after every refresh.
     */
    private Object host(Map<String, Object> a) {
        Job j = job(a);
        if (j.files == null || !j.finished) throw new IllegalArgumentException("Export first");
        String token = s(a, "token");
        if (token.isEmpty()) throw new IllegalArgumentException("GitHub token is missing - add it in Settings");
        String api = s(a, "api");
        if (api.isEmpty()) api = "https://api.github.com";
        String gistId = s(a, "gistId");
        Map<String, Object> files = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : j.files.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("content", f.getValue());
            files.put(f.getKey(), c);
        }
        List<Object> prev = MiniJson.list(a.get("prevFiles"));
        if (prev != null) for (Object o : prev) if (!files.containsKey(MiniJson.str(o))) files.put(MiniJson.str(o), null);   // drop stale category files
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/vnd.github+json");
        h.put("User-Agent", "M3U-Converter");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        h.put("Content-Type", "application/json");
        Http.Resp r = null;
        boolean relinked = false;
        try {
            if (!gistId.isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("description", "M3U Converter - " + j.base);
                body.put("files", files);
                r = Http.request("PATCH", api + "/gists/" + Util.enc(gistId), h, MiniJson.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8), 120000);
                if (r.status == 404 || r.status == 405) {          // gist was deleted -> make a new one
                    r = null;
                    relinked = true;
                }
            }
            if (r == null) {
                Map<String, Object> mk = new LinkedHashMap<>();
                for (Map.Entry<String, Object> f : files.entrySet()) if (f.getValue() != null) mk.put(f.getKey(), f.getValue());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("description", "M3U Converter - " + j.base);
                body.put("public", false);
                body.put("files", mk);
                r = Http.request("POST", api + "/gists", h, MiniJson.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8), 120000);
            }
        } catch (java.io.IOException e) {
            throw new EngineError("github", "Cannot reach GitHub - check your internet (" + e.getMessage() + ")");
        }
        if (r.status != 200 && r.status != 201) throw new EngineError("github", ghError(r));
        Map<String, Object> g = MiniJson.map(MiniJson.parseLenient(r.body));
        Map<String, Object> gf = g == null ? null : MiniJson.map(g.get("files"));
        if (gf == null) throw new EngineError("github", "GitHub sent an unexpected reply");
        List<Object> out = new ArrayList<>();
        String allUrl = "";
        for (String name : j.files.keySet()) {
            Map<String, Object> f = MiniJson.map(gf.get(name));
            if (f == null) continue;
            String raw = MiniJson.str(f.get("raw_url")).replaceFirst("/raw/[0-9a-fA-F]{40}/", "/raw/");   // no commit id = always the latest
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("count", j.counts.get(name));
            m.put("url", raw);
            out.add(m);
            if (allUrl.isEmpty()) allUrl = raw;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("gistId", MiniJson.str(g.get("id")));
        res.put("page", MiniJson.str(g.get("html_url")));
        res.put("all", allUrl);
        res.put("files", out);
        res.put("relinked", relinked);
        return res;
    }
}
