package com.m3upro

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainVm(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val playlists = MutableStateFlow(store.load())
    val busy = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(-1f)
    val message = MutableStateFlow<String?>(null)
    val deepCheck = MutableStateFlow(store.deepCheck)
    val epg = MutableStateFlow<Map<String, EpgData>>(emptyMap())
    val epgBusy = MutableStateFlow(false)
    val recents = MutableStateFlow(store.loadRecents())

    init { importBuiltin(); autoCheck(); playlists.value.forEach { if (hasGuide(it)) loadEpg(it.id) }; bootstrap() }

    private fun hasGuide(p: Playlist) = p.epgUrl.isNotBlank() || p.source.startsWith("portal:")

    /** Imports app/src/main/assets/playlist.m3u (if you bundled one) the first time the app runs. */
    private fun importBuiltin() {
        viewModelScope.launch {
            if (store.builtinDone) return@launch
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().assets.open("playlist.m3u").bufferedReader().use { it.readText() }
                }
                val ch = withContext(Dispatchers.Default) { M3u.parse(text) }
                if (ch.isNotEmpty()) add("Built-in playlist", "builtin", ch, M3u.epgUrl(text))
                store.builtinDone = true
            } catch (e: Exception) { /* no bundled playlist */ }
        }
    }

    fun mergeAll() {
        val rest = playlists.value.filter { it.source != "merged" }
        val all = rest.flatMap { it.channels }.distinctBy { it.url }
        if (all.isEmpty()) { message.value = "Nothing to merge"; return }
        playlists.value = rest + Playlist(UUID.randomUUID().toString(), "All channels (merged)", "merged",
            System.currentTimeMillis(), 1, all)
        persist()
        message.value = "Merged ${all.size} channels"
    }

    fun setDeep(v: Boolean) { store.deepCheck = v; deepCheck.value = v }
    fun portalCfg() = store.loadPortal()

    private fun persist() {
        val snap = playlists.value
        viewModelScope.launch(Dispatchers.IO) { store.save(snap) }
    }

    fun update(id: String, f: (Playlist) -> Playlist) {
        playlists.value = playlists.value.map { if (it.id == id) f(it) else it }
        persist()
    }

    private fun add(name: String, source: String, ch: List<Channel>, epgUrl: String = "") {
        val id = UUID.randomUUID().toString()
        playlists.value = playlists.value +
            Playlist(id, name, source, System.currentTimeMillis(), 1, ch, epgUrl)
        persist()
        if (epgUrl.isNotBlank() || source.startsWith("portal:")) loadEpg(id)
    }

    fun delete(id: String) {
        if (id == BUILTIN) store.builtinRemoved = true
        playlists.value = playlists.value.filter { it.id != id }; persist()
    }

    // ---- Built-in playlist: bundled in the APK (assets/default.m3u) and refreshed from the GitHub repo
    private fun bootstrap() {
        viewModelScope.launch {
            if (store.builtinRemoved) return@launch
            if (playlists.value.none { it.id == BUILTIN }) {
                val local = withContext(Dispatchers.IO) {
                    try {
                        getApplication<Application>().assets.open("default.m3u").bufferedReader().use { M3u.parse(it.readText()) }
                    } catch (e: Exception) { emptyList<Channel>() }
                }
                if (local.isNotEmpty()) upsertBuiltin(local, "")
            }
            try {
                val text = Net.get(BUILTIN_URL)
                val ch = withContext(Dispatchers.Default) { M3u.parse(text) }
                if (ch.isNotEmpty()) upsertBuiltin(ch, M3u.epgUrl(text))
            } catch (e: Exception) { /* offline or repo playlist missing: keep what we have */ }
        }
    }

    private fun upsertBuiltin(ch: List<Channel>, epgUrl: String) {
        val old = playlists.value.firstOrNull { it.id == BUILTIN }
        if (old == null) {
            playlists.value = playlists.value +
                Playlist(BUILTIN, "Tamil IPTV (built-in)", "builtin", System.currentTimeMillis(), 1, ch, epgUrl)
            persist()
            if (epgUrl.isNotBlank()) loadEpg(BUILTIN)
        } else {
            val favs = old.channels.filter { it.fav }.map { it.url }.toSet()
            update(BUILTIN) {
                it.copy(channels = ch.map { c -> c.copy(fav = c.url in favs) }, lastChecked = System.currentTimeMillis(),
                    state = 1, epgUrl = epgUrl.ifBlank { it.epgUrl })
            }
        }
    }

    companion object {
        const val BUILTIN = "builtin"
        const val BUILTIN_URL = "https://raw.githubusercontent.com/saboorabd/Tamil-iptv-player/main/public/playlist.m3u"
    }

    fun addFromUrl(name: String, url: String) {
        viewModelScope.launch {
            busy.value = "Loading playlist…"
            try {
                val (ch, e) = withContext(Dispatchers.Default) {
                    val t = Net.get(url); M3u.parse(t) to M3u.epgUrl(t)
                }
                if (ch.isEmpty()) error("No channels found")
                add(name.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "Playlist" } }, url, ch, e)
            } catch (e: Exception) { message.value = "Failed: ${e.message}" }
            finally { busy.value = null }
        }
    }

    fun addFromUri(uri: Uri) {
        viewModelScope.launch {
            busy.value = "Reading file…"
            try {
                val (ch, e) = withContext(Dispatchers.IO) {
                    val t = getApplication<Application>().contentResolver.openInputStream(uri)!!
                        .bufferedReader().use { it.readText() }
                    M3u.parse(t) to M3u.epgUrl(t)
                }
                if (ch.isEmpty()) error("No channels found")
                add("Local playlist", "file", ch, e)
            } catch (e: Exception) { message.value = "Failed: ${e.message}" }
            finally { busy.value = null }
        }
    }

    fun portalLogin(cfg: PortalCfg, onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = "Logging in to portal…"
            try {
                store.savePortal(cfg)
                val ch = Stalker.login(cfg)
                if (ch.isEmpty()) error("No channels returned")
                val host = cfg.url.substringAfter("://").substringBefore("/")
                add("Portal: $host", "portal:${cfg.url}", ch)
                onDone()
            } catch (e: Exception) { message.value = "Login failed: ${e.message}" }
            finally { busy.value = null }
        }
    }

    fun xtreamLogin(server: String, user: String, pass: String) {
        viewModelScope.launch {
            busy.value = "Logging in to Xtream…"
            try {
                val (ch, epgUrl) = Xtream.login(server, user, pass)
                if (ch.isEmpty()) error("No channels returned")
                add("Xtream: " + server.substringAfter("://").substringBefore("/"), "xtream:$server", ch, epgUrl)
            } catch (e: Exception) { message.value = "Xtream failed: ${e.message}" }
            finally { busy.value = null }
        }
    }

    // ---- Guide (EPG)
    fun loadEpg(id: String, force: Boolean = false) {
        viewModelScope.launch {
            val p = playlists.value.firstOrNull { it.id == id } ?: return@launch
            epgBusy.value = true
            try {
                val d = when {
                    p.source.startsWith("portal:") -> Stalker.epg(store.loadPortal())
                    p.epgUrl.isNotBlank() -> Epg.load(p.epgUrl, getApplication<Application>().cacheDir, force)
                    else -> null
                }
                if (d != null) epg.value = epg.value + (id to d)
            } catch (e: Exception) { message.value = "Guide: ${e.message}" }
            finally { epgBusy.value = false }
        }
    }

    fun setEpgUrl(id: String, url: String) { update(id) { it.copy(epgUrl = url.trim()) }; loadEpg(id, true) }

    fun progs(c: Channel): List<Prog> {
        for (d in epg.value.values) { val l = d.forChannel(c); if (l.isNotEmpty()) return l }
        return emptyList()
    }

    // ---- Library tools
    fun merge(ids: List<String>, name: String, dedupe: Boolean) {
        val src = playlists.value.filter { it.id in ids }
        var ch = src.flatMap { it.channels }
        if (dedupe) ch = ch.distinctBy { it.url }
        if (ch.isEmpty()) return
        add(name.ifBlank { "Merged" }, "merged", ch, src.map { it.epgUrl }.firstOrNull { it.isNotBlank() } ?: "")
        message.value = "Merged ${ch.size} channels"
    }

    fun copyTo(targetId: String, chs: List<Channel>) {
        update(targetId) { p -> p.copy(channels = (p.channels + chs).distinctBy { it.url }) }
        message.value = "Copied ${chs.size} channel(s)"
    }

    fun played(c: Channel) {
        val l = (listOf(c) + recents.value.filter { it.url != c.url }).take(20)
        recents.value = l
        viewModelScope.launch(Dispatchers.IO) { store.saveRecents(l) }
    }

    /** Channel logo: playlist logo first, otherwise the icon from the guide (XMLTV). */
    fun logo(c: Channel): String {
        if (c.logo.startsWith("http")) return c.logo
        for (d in epg.value.values) { val l = d.logoFor(c); if (l.isNotEmpty()) return l }
        return ""
    }

    // ---- Playback / favorites
    suspend fun resolve(url: String): String =
        if (url.startsWith("portal://")) Stalker.resolve(url.removePrefix("portal://"), store.loadPortal()) else url

    fun toggleFav(url: String) {
        playlists.value = playlists.value.map { p ->
            p.copy(channels = p.channels.map { if (it.url == url) it.copy(fav = !it.fav) else it })
        }
        persist()
    }

    // ---- Checks
    fun autoCheck() {
        viewModelScope.launch {
            for (p in playlists.value) {
                if (!p.source.startsWith("http")) continue
                try {
                    val text = Net.get(p.source)
                    val fresh = withContext(Dispatchers.Default) { M3u.parse(text) }
                    if (fresh.isNotEmpty()) {
                        val favs = p.channels.filter { it.fav }.map { it.url }.toSet()
                        val st = p.channels.associate { it.url to it.status }
                        update(p.id) {
                            it.copy(channels = fresh.map { c -> c.copy(fav = c.url in favs, status = st[c.url] ?: 0) },
                                state = 1, lastChecked = System.currentTimeMillis())
                        }
                    } else update(p.id) { it.copy(state = 2, lastChecked = System.currentTimeMillis()) }
                } catch (e: Exception) {
                    update(p.id) { it.copy(state = 2, lastChecked = System.currentTimeMillis()) }
                }
            }
            if (store.deepCheck) playlists.value.forEach { runCheck(it.id) }
        }
    }

    fun checkChannels(id: String) { viewModelScope.launch { runCheck(id) } }

    private suspend fun runCheck(id: String) {
        val p = playlists.value.firstOrNull { it.id == id } ?: return
        busy.value = "Checking ${p.name}"; progress.value = 0f
        val res = Checker.checkAll(p.channels) { d, t -> progress.value = d / t.toFloat() }
        update(id) { it.copy(channels = res, lastChecked = System.currentTimeMillis()) }
        busy.value = null; progress.value = -1f
        message.value = "Working: ${res.count { it.status == 1 }} • Dead: ${res.count { it.status == 2 }}"
    }

    fun export(uri: Uri, p: Playlist) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!
                    .bufferedWriter().use { it.write(M3u.export(p.channels, p.epgUrl)) }
                message.value = "Exported ${p.channels.size} channels"
            } catch (e: Exception) { message.value = "Export failed" }
        }
    }
}
