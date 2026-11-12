package com.m3upro

object M3u {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""; var group = ""; var logo = ""; var id = ""
        var drm = ""; var key = ""; var ua = ""; var ref = ""
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXTINF", true) -> {
                    val a = attr.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = l.substringAfterLast(",", "").trim()
                    group = a["group-title"] ?: ""
                    logo = a["tvg-logo"] ?: ""
                    id = a["tvg-id"] ?: ""
                }
                l.startsWith("#EXTGRP:", true) -> group = l.substringAfter(":").trim()
                l.startsWith("#KODIPROP:", true) -> {
                    val kv = l.substringAfter(":")
                    val k = kv.substringBefore("=").lowercase()
                    val v = kv.substringAfter("=", "").trim()
                    if (k.endsWith("license_type")) drm = v
                    else if (k.endsWith("license_key")) key = v
                }
                l.startsWith("#EXTVLCOPT:", true) -> {
                    val kv = l.substringAfter(":")
                    val k = kv.substringBefore("=").lowercase()
                    val v = kv.substringAfter("=", "").trim()
                    if (k == "http-user-agent") ua = v
                    else if (k == "http-referrer" || k == "http-referer") ref = v
                }
                l.startsWith("#") -> {}
                else -> {
                    var url = l
                    if (url.contains("|")) {
                        val parts = url.split("|", limit = 2)
                        url = parts[0]
                        for (h in parts[1].split("&")) {
                            val kv = h.split("=", limit = 2)
                            if (kv.size == 2) {
                                val hk = kv[0].lowercase()
                                if (hk == "user-agent") ua = kv[1]
                                else if (hk == "referer" || hk == "referrer") ref = kv[1]
                            }
                        }
                    }
                    out.add(Channel(name = name.ifEmpty { url }, url = url, group = group, logo = logo,
                        tvgId = id, drm = drm, key = key, ua = ua, ref = ref))
                    name = ""; group = ""; logo = ""; id = ""
                    drm = ""; key = ""; ua = ""; ref = ""
                }
            }
        }
        return out
    }

    fun epgUrl(text: String): String {
        val head = text.lineSequence().firstOrNull { it.startsWith("#EXTM3U") } ?: return ""
        return Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""").find(head)
            ?.groupValues?.get(1)?.split(",")?.firstOrNull()?.trim() ?: ""
    }

    fun export(list: List<Channel>, epg: String = ""): String {
        val sb = StringBuilder("#EXTM3U")
        if (epg.isNotEmpty()) sb.append(" url-tvg=\"$epg\"")
        sb.append("\n")
        for (c in list) {
            if (c.drm.isNotEmpty()) sb.append("#KODIPROP:inputstream.adaptive.license_type=${c.drm}\n")
            if (c.key.isNotEmpty()) sb.append("#KODIPROP:inputstream.adaptive.license_key=${c.key}\n")
            if (c.ua.isNotEmpty()) sb.append("#EXTVLCOPT:http-user-agent=${c.ua}\n")
            if (c.ref.isNotEmpty()) sb.append("#EXTVLCOPT:http-referrer=${c.ref}\n")
            sb.append("#EXTINF:-1")
            if (c.tvgId.isNotEmpty()) sb.append(" tvg-id=\"${c.tvgId}\"")
            if (c.logo.isNotEmpty()) sb.append(" tvg-logo=\"${c.logo}\"")
            if (c.group.isNotEmpty()) sb.append(" group-title=\"${c.group}\"")
            sb.append(",").append(c.name).append("\n").append(c.url).append("\n")
        }
        return sb.toString()
    }
}
