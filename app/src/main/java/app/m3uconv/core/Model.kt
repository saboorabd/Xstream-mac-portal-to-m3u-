package com.m3upro

data class Channel(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val tvgId: String = "",
    val status: Int = 0, // 0 unknown, 1 working, 2 dead
    val fav: Boolean = false,
    val drm: String = "",   // clearkey | widevine | playready
    val key: String = "",   // license key / license server url
    val ua: String = "",    // custom User-Agent
    val ref: String = ""    // custom Referer
)

data class Playlist(
    val id: String,
    val name: String,
    val source: String,
    val lastChecked: Long,
    val state: Int, // 0 unknown, 1 ok, 2 dead
    val channels: List<Channel>,
    val epgUrl: String = ""
)

data class PortalCfg(
    val url: String = "",
    val mac: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val serial: String = ""
)
