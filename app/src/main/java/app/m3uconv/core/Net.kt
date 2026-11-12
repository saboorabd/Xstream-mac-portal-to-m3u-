package com.m3upro

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()

    private fun req(url: String) = Request.Builder().url(url)
        .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18").build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(req(url)).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            it.body!!.string()
        }
    }

    suspend fun reachable(url: String): Boolean = withContext(Dispatchers.IO) {
        try { client.newCall(req(url)).execute().use { it.code in 200..399 } } catch (e: Exception) { false }
    }

    fun status(url: String): Int {
        if (!url.startsWith("http", true)) return 0
        return try { client.newCall(req(url)).execute().use { if (it.code in 200..399) 1 else 2 } }
        catch (e: Exception) { 2 }
    }
}

object Checker {
    suspend fun checkAll(list: List<Channel>, onProgress: (Int, Int) -> Unit): List<Channel> = coroutineScope {
        val sem = Semaphore(24)
        val done = AtomicInteger(0)
        val total = list.size
        list.map { ch ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val s = Net.status(ch.url)
                    onProgress(done.incrementAndGet(), total)
                    ch.copy(status = s)
                }
            }
        }.awaitAll()
    }
}

/** Standard Stalker/MAC-portal client (same handshake any STB-style IPTV player uses). */
object Stalker {
    private const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    private data class Sess(val ep: String, val token: String, val cfg: PortalCfg)
    @Volatile private var sess: Sess? = null

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun endpoints(u: String): List<String> {
        var b = u.trim().trimEnd('/')
        if (!b.startsWith("http", true)) b = "http://$b"
        if (b.endsWith(".php")) return listOf(b)
        b = b.removeSuffix("/c")
        return listOf("$b/portal.php", "$b/stalker_portal/server/load.php", "$b/server/load.php")
    }

    private fun call(ep: String, params: String, cfg: PortalCfg, token: String?): JSONObject {
        val rb = Request.Builder().url("$ep?$params&JsHttpRequest=1-xml")
            .header("User-Agent", UA)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Cookie", "mac=${cfg.mac}; stb_lang=en; timezone=GMT")
        if (token != null) rb.header("Authorization", "Bearer $token")
        Net.client.newCall(rb.build()).execute().use { r -> return JSONObject(r.body!!.string()) }
    }

    private fun connect(cfg: PortalCfg): Sess {
        var last: Exception? = null
        for (ep in endpoints(cfg.url)) {
            try {
                val token = call(ep, "type=stb&action=handshake&token=", cfg, null)
                    .getJSONObject("js").getString("token")
                call(ep, "type=stb&action=get_profile&hd=1&num_banks=2&stb_type=MAG250" +
                    "&sn=${enc(cfg.serial)}&device_id=${enc(cfg.deviceId)}&device_id2=${enc(cfg.deviceId2)}" +
                    "&auth_second_step=1", cfg, token)
                val s = Sess(ep, token, cfg)
                sess = s
                return s
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("Login failed")
    }

    private fun session(cfg: PortalCfg): Sess = sess ?: connect(cfg)

    suspend fun login(cfg: PortalCfg): List<Channel> = withContext(Dispatchers.IO) {
        val s = connect(cfg)
        val genres = HashMap<String, String>()
        val g = call(s.ep, "type=itv&action=get_genres", cfg, s.token).optJSONArray("js")
        if (g != null) for (i in 0 until g.length()) {
            val o = g.getJSONObject(i); genres[o.optString("id")] = o.optString("title")
        }
        val data = call(s.ep, "type=itv&action=get_all_channels", cfg, s.token)
            .getJSONObject("js").getJSONArray("data")
        (0 until data.length()).map {
            val o = data.getJSONObject(it)
            Channel(o.optString("name"), "portal://" + o.optString("cmd"),
                genres[o.optString("tv_genre_id")] ?: "", o.optString("logo"), o.optString("id"))
        }
    }

    /** Turns a portal channel command into a real playable stream URL (create_link). */
    suspend fun resolve(cmd: String, cfg: PortalCfg): String = withContext(Dispatchers.IO) {
        fun go(s: Sess): String {
            val js = call(s.ep, "type=itv&action=create_link&cmd=${enc(cmd)}&series=&forced_storage=0" +
                "&disable_ad=0&download=0&force_ch_link_check=0", s.cfg, s.token).getJSONObject("js")
            val c = js.optString("cmd")
            if (c.isBlank()) error("Portal returned no link")
            return Regex("https?://\\S+").find(c)?.value ?: c
        }
        try { go(session(cfg)) } catch (e: Exception) { go(connect(cfg)) }
    }

    suspend fun epg(cfg: PortalCfg): EpgData = withContext(Dispatchers.IO) {
        val s = session(cfg)
        val data = call(s.ep, "type=itv&action=get_epg_info&period=24", s.cfg, s.token)
            .optJSONObject("js")?.optJSONObject("data")
        val map = HashMap<String, List<Prog>>()
        if (data != null) for (k in data.keys()) {
            val a = data.optJSONArray(k) ?: continue
            map[k.lowercase()] = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Prog(o.optLong("start_timestamp") * 1000, o.optLong("stop_timestamp") * 1000,
                    o.optString("name"), o.optString("descr"))
            }.sortedBy { it.start }
        }
        EpgData(map, emptyMap())
    }
}

/** Xtream Codes API client (live channels + XMLTV guide). */
object Xtream {
    private fun base(s: String): String {
        var b = s.trim().trimEnd('/')
        if (!b.startsWith("http", true)) b = "http://$b"
        b = b.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("/xmltv.php")
        return b.trimEnd('/')
    }

    suspend fun login(server: String, user: String, pass: String): Pair<List<Channel>, String> =
        withContext(Dispatchers.IO) {
            val b = base(server)
            val u = URLEncoder.encode(user, "UTF-8")
            val p = URLEncoder.encode(pass, "UTF-8")
            val api = "$b/player_api.php?username=$u&password=$p"
            val info = JSONObject(Net.get(api))
            val ui = info.optJSONObject("user_info") ?: error("Invalid server response")
            if (ui.optString("auth") != "1") error("Login rejected (check username/password)")
            val cats = HashMap<String, String>()
            val ca = JSONArray(Net.get("$api&action=get_live_categories"))
            for (i in 0 until ca.length()) {
                val o = ca.getJSONObject(i)
                cats[o.optString("category_id")] = o.optString("category_name")
            }
            val sa = JSONArray(Net.get("$api&action=get_live_streams"))
            val list = (0 until sa.length()).map {
                val o = sa.getJSONObject(it)
                Channel(
                    name = o.optString("name"),
                    url = "$b/live/$user/$pass/${o.optString("stream_id")}.m3u8",
                    group = cats[o.optString("category_id")] ?: "",
                    logo = o.optString("stream_icon"),
                    tvgId = o.optString("epg_channel_id")
                )
            }
            list to "$b/xmltv.php?username=$u&password=$p"
        }
}

data class Sniffed(val mime: String?, val error: String?)

/** Peeks at the first bytes of a stream so the player knows if it is HLS, DASH or MPEG-TS (or not a stream at all). */
object Sniff {
    suspend fun detect(url: String, ua: String, ref: String): Sniffed = withContext(Dispatchers.IO) {
        if (!url.startsWith("http", true)) return@withContext Sniffed(null, null)
        try {
            val rb = Request.Builder().url(url).header("User-Agent", ua.ifBlank { "VLC/3.0.18 LibVLC/3.0.18" })
            if (ref.isNotBlank()) rb.header("Referer", ref)
            Net.client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext Sniffed(null, "Server replied HTTP ${r.code} (link expired, blocked or wrong login?)")
                val ct = (r.header("Content-Type") ?: "").lowercase()
                val buf = ByteArray(2048)
                var n = 0
                val ins = r.body!!.byteStream()
                while (n < buf.size) {
                    val k = ins.read(buf, n, buf.size - n)
                    if (k <= 0) break
                    n += k
                }
                val head = String(buf, 0, n, Charsets.ISO_8859_1).trimStart()
                when {
                    head.startsWith("#EXTM3U") || ct.contains("mpegurl") -> Sniffed("application/x-mpegURL", null)
                    head.contains("<MPD") || ct.contains("dash+xml") -> Sniffed("application/dash+xml", null)
                    n > 188 && buf[0] == 0x47.toByte() && buf[188] == 0x47.toByte() -> Sniffed("video/mp2t", null)
                    head.startsWith("<!DOCTYPE html", true) || head.startsWith("<html", true) ->
                        Sniffed(null, "Server returned a web page, not a video stream")
                    else -> Sniffed(null, null)
                }
            }
        } catch (e: Exception) { Sniffed(null, null) }
    }
}

/** Hides usernames / passwords / tokens so diagnostics can be shared safely. */
fun maskUrl(u: String): String =
    u.replace(Regex("(?i)/live/[^/]+/[^/]+/"), "/live/***/***/")
        .replace(Regex("(?i)(password|pass|token|username|user|mac|key)=([^&]+)"), "\$1=***")
        .take(150)
