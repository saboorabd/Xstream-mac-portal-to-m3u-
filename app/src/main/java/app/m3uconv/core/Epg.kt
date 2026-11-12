package com.m3upro

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class Prog(val start: Long, val stop: Long, val title: String, val desc: String, val img: String = "")

fun normName(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

class EpgData(
    val byId: Map<String, List<Prog>>,
    val byName: Map<String, String>,
    val logos: Map<String, String> = emptyMap()
) {
    private fun key(c: Channel): String? {
        val id = c.tvgId.lowercase()
        if (id.isNotEmpty() && byId.containsKey(id)) return id
        val n = normName(c.name)
        val viaName = byName[n]
        if (viaName != null && byId.containsKey(viaName)) return viaName
        if (byId.containsKey(n)) return n
        return null
    }

    fun forChannel(c: Channel): List<Prog> {
        val k = key(c) ?: return emptyList()
        return byId[k] ?: emptyList()
    }

    fun logoFor(c: Channel): String {
        val id = c.tvgId.lowercase()
        val a = logos[id]
        if (a != null) return a
        val k = byName[normName(c.name)]
        if (k != null) return logos[k] ?: ""
        return ""
    }
}

object Epg {
    private const val H = 3_600_000L

    private fun ts(s: String?): Long {
        if (s == null) return 0
        val t = s.trim()
        return try {
            if (t.contains(' ')) SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(t)!!.time
            else SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(t.take(14))!!.time
        } catch (e: Exception) { 0 }
    }

    fun parse(ins: InputStream, now: Long): EpgData {
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        val byId = HashMap<String, MutableList<Prog>>()
        val byName = HashMap<String, String>()
        val logos = HashMap<String, String>()
        val lo = now - 3 * H
        val hi = now + 30 * H
        var cur: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> cur = p.getAttributeValue(null, "id")?.lowercase()
                    "display-name" -> { val t = p.nextText(); cur?.let { byName.putIfAbsent(normName(t), it) } }
                    "icon" -> {
                        val id = cur
                        val src = p.getAttributeValue(null, "src")
                        if (id != null && src != null) logos.putIfAbsent(id, src)
                    }
                    "programme" -> {
                        val ch = p.getAttributeValue(null, "channel")?.lowercase()
                        val s = ts(p.getAttributeValue(null, "start"))
                        val e = ts(p.getAttributeValue(null, "stop"))
                        var title = ""; var desc = ""; var img = ""
                        while (true) {
                            ev = p.next()
                            if (ev == XmlPullParser.END_TAG && p.name == "programme") break
                            if (ev == XmlPullParser.END_DOCUMENT) break
                            if (ev == XmlPullParser.START_TAG) {
                                when (p.name) {
                                    "title" -> if (title.isEmpty()) title = p.nextText()
                                    "desc" -> if (desc.isEmpty()) desc = p.nextText()
                                    "icon" -> if (img.isEmpty()) img = p.getAttributeValue(null, "src") ?: ""
                                }
                            }
                        }
                        if (ch != null && e > lo && s < hi && e > s)
                            byId.getOrPut(ch) { ArrayList() }.add(Prog(s, e, title, desc, img))
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "channel") cur = null
            ev = p.next()
        }
        return EpgData(byId.mapValues { (_, v) -> v.sortedBy { it.start } }, byName, logos)
    }

    suspend fun load(url: String, cacheDir: File, force: Boolean): EpgData = withContext(Dispatchers.IO) {
        val f = File(cacheDir, "epg_${url.hashCode()}.bin")
        if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * H) {
            val tmp = File(cacheDir, f.name + ".tmp")
            Net.client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            }
            f.delete(); tmp.renameTo(f)
        }
        val raw = BufferedInputStream(f.inputStream())
        raw.mark(2)
        val b1 = raw.read(); val b2 = raw.read()
        raw.reset()
        val ins: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
        ins.use { parse(it, System.currentTimeMillis()) }
    }
}
