package com.m3upro

import android.content.Context
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/** Builds a playable MediaSource for a channel: HLS / DASH / TS, custom headers, ClearKey / Widevine / PlayReady. */
@OptIn(UnstableApi::class)
object Drm {
    private fun b64(hex: String): String {
        val h = hex.trim().replace("-", "")
        val bytes = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Accepts "kid:key,kid2:key2" (hex) or a ready JSON license. Returns null if unparsable. */
    fun clearKeyJson(k: String): String? {
        val t = k.trim()
        if (t.startsWith("{")) return t
        return try {
            val keys = t.split(",").filter { it.contains(":") }.joinToString(",") { pair ->
                val parts = pair.trim().split(":", limit = 2)
                "{\"kty\":\"oct\",\"k\":\"${b64(parts[1])}\",\"kid\":\"${b64(parts[0])}\"}"
            }
            if (keys.isEmpty()) null else "{\"keys\":[$keys],\"type\":\"temporary\"}"
        } catch (e: Exception) { null }
    }

    fun source(ctx: Context, ch: Channel, url: String, mime: String? = null): MediaSource {
        val headers = HashMap<String, String>()
        if (ch.ref.isNotBlank()) headers["Referer"] = ch.ref
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.ua.ifBlank { "VLC/3.0.18 LibVLC/3.0.18" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
        val factory = DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, http))
        val item = MediaItem.Builder().setUri(url)
        if (mime != null) item.setMimeType(mime)
        val type = ch.drm.lowercase()
        val key = ch.key.trim()
        if (type.contains("clearkey")) {
            if (key.startsWith("http")) {
                item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).setLicenseUri(key).build())
            } else {
                val json = clearKeyJson(key)
                if (json != null) {
                    val mgr = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(LocalMediaDrmCallback(json.toByteArray()))
                    factory.setDrmSessionManagerProvider { mgr }
                    item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
                }
            }
        } else if (type.contains("widevine") && key.isNotBlank()) {
            item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(key.substringBefore("|")).setMultiSession(true).build())
        } else if (type.contains("playready") && key.isNotBlank()) {
            item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.PLAYREADY_UUID)
                .setLicenseUri(key.substringBefore("|")).build())
        }
        return factory.createMediaSource(item.build())
    }
}
