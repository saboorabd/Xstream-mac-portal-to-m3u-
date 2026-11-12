package com.m3upro

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.max

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainVm, channels: List<Channel>, start: Int, onClose: () -> Unit) {
    if (channels.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val view = LocalView.current
    val n = channels.size
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var idx by remember { mutableIntStateOf(start.coerceIn(0, n - 1)) }
    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var resize by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var poke by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var tracksDlg by remember { mutableStateOf(false) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var osd by remember { mutableStateOf<String?>(null) }
    var vol by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val tried = remember { mutableStateMapOf<Int, Int>() }
    var info by remember { mutableStateOf("") }
    val favs = remember { mutableStateMapOf<String, Boolean>().apply { channels.forEach { put(it.url, it.fav) } } }
    val guide by vm.epg.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }

    val ch = channels[idx]
    val progs = remember(ch, guide) { vm.progs(ch) }
    val cur = progs.firstOrNull { now >= it.start && now < it.stop }
    val next = progs.firstOrNull { it.start >= (cur?.stop ?: now) }
    val logo = remember(ch, guide) { vm.logo(ch) }

    val exo = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) {
                val a = tried[idx] ?: 0
                if (error.errorCode in 3000..3999 && a < 3) { tried[idx] = a + 1; retry++ }
                else {
                    err = error.errorCodeName.removePrefix("ERROR_CODE_") + (error.cause?.message?.let { " • " + it.take(80) } ?: "")
                    buffering = false
                }
            }
        }
        exo.addListener(l)
        val w = activity?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            exo.removeListener(l); exo.release()
            c?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(idx, retry) {
        err = null; buffering = true
        vm.played(ch)
        try {
            val u = vm.resolve(ch.url)
            val attempt = tried[idx] ?: 0
            if (u.contains("youtube.com", true) || u.contains("youtu.be", true)) {
                err = "YouTube links can't be played here (no direct stream URL)"; buffering = false; return@LaunchedEffect
            }
            val sniff = if (attempt == 0) Sniff.detect(u, ch.ua, ch.ref) else Sniffed(null, null)
            info = "URL: ${maskUrl(u)}\nDetected: ${sniff.mime ?: "unknown"} • attempt $attempt\nDRM: ${ch.drm.ifBlank { "none" }}"
            if (sniff.error != null) { err = sniff.error; buffering = false; return@LaunchedEffect }
            val mime: String? = when (attempt) {
                0 -> sniff.mime
                1 -> "application/x-mpegURL"
                2 -> "video/mp2t"
                else -> "application/dash+xml"
            }
            exo.setMediaSource(Drm.source(ctx, ch, u, mime))
            exo.prepare()
            exo.playWhenReady = true
        } catch (e: Exception) { err = e.message ?: "Could not open stream"; buffering = false }
    }
    LaunchedEffect(controls, panel, poke) { if (controls && !panel) { delay(4_000); controls = false } }
    LaunchedEffect(osd) { if (osd != null) { delay(900); osd = null } }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) {
            delay(max(0L, sleepAt - System.currentTimeMillis()))
            exo.pause(); controls = true; osd = "Sleep timer: paused"
        }
    }

    fun go(d: Int) { idx = (idx + d + n) % n; poke++ }
    fun pip() {
        if (Build.VERSION.SDK_INT >= 26 && activity != null) {
            controls = false
            activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { useController = false; player = exo; keepScreenOn = true } },
            update = {
                it.resizeMode = when (resize) {
                    0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        // gestures: tap = controls, swipe middle = change channel, swipe left = brightness, swipe right = volume
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { if (panel) panel = false else controls = !controls }) }
                .pointerInput(idx) {
                    var d = 0f
                    var zone = 0
                    detectVerticalDragGestures(
                        onDragStart = { o -> d = 0f; zone = if (o.x < size.width * 0.3f) 1 else if (o.x > size.width * 0.7f) 2 else 0 },
                        onDragEnd = { if (zone == 0) { if (d < -140) go(1) else if (d > 140) go(-1) } },
                        onVerticalDrag = { _, dy ->
                            d += dy
                            val frac = -dy / size.height * 1.5f
                            if (zone == 1 && activity != null) {
                                val lp = activity.window.attributes
                                val b = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                lp.screenBrightness = (b + frac).coerceIn(0.02f, 1f)
                                activity.window.attributes = lp
                                osd = "Brightness ${(lp.screenBrightness * 100).toInt()}%"
                            } else if (zone == 2) {
                                val mx = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                vol = (vol + frac * mx).coerceIn(0f, mx.toFloat())
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol.toInt(), 0)
                                osd = "Volume ${(vol / mx * 100).toInt()}%"
                            }
                        })
                }
        )

        if (buffering && err == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        osd?.let {
            Text(it, Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(14.dp, 8.dp),
                color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        err?.let { e ->
            Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't play this stream", color = Color.White, fontWeight = FontWeight.Bold)
                Text(e, color = Color(0xFFB0B7C3), style = MaterialTheme.typography.bodySmall)
                if (info.isNotBlank()) Text(info, Modifier.padding(top = 8.dp), color = Color(0xFF8A93A3),
                    style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ tried[idx] = 0; retry++ }) { Text("Retry") }
                    OutlinedButton({ go(1) }) { Text("Next") }
                    OutlinedButton({
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("stream-details", info + "\nError: " + e))
                        osd = "Details copied"
                    }) { Text("Copy") }
                }
            }
        }

        AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClose) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                    Logo(ch, 36, logo)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${idx + 1}. ${ch.name}", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cur?.title ?: ch.group, color = Color(0xFFB0B7C3), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton({ resize = (resize + 1) % 3; poke++ }) { Text(listOf("Fit", "Fill", "Zoom")[resize], color = Color.White) }
                    IconButton({ vm.toggleFav(ch.url); favs[ch.url] = !(favs[ch.url] ?: false); poke++ }) {
                        Icon(if (favs[ch.url] == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite",
                            tint = if (favs[ch.url] == true) Color(0xFFFF6B8B) else Color.White)
                    }
                    Box {
                        IconButton({ menu = true; poke++ }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Audio, subtitles & quality") }, onClick = { menu = false; tracksDlg = true })
                            DropdownMenuItem(text = { Text("Picture-in-picture") }, onClick = { menu = false; pip() })
                            DropdownMenuItem(text = { Text("Rotate screen") }, onClick = {
                                menu = false
                                val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                activity?.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            })
                            DropdownMenuItem(text = { Text("Sleep in 15 min") }, onClick = { menu = false; sleepAt = System.currentTimeMillis() + 15 * 60_000L; osd = "Sleep in 15 min" })
                            DropdownMenuItem(text = { Text("Sleep in 30 min") }, onClick = { menu = false; sleepAt = System.currentTimeMillis() + 30 * 60_000L; osd = "Sleep in 30 min" })
                            DropdownMenuItem(text = { Text("Sleep in 60 min") }, onClick = { menu = false; sleepAt = System.currentTimeMillis() + 60 * 60_000L; osd = "Sleep in 60 min" })
                            DropdownMenuItem(text = { Text("Sleep timer off") }, onClick = { menu = false; sleepAt = 0L; osd = "Sleep timer off" })
                        }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(16.dp)
                ) {
                    if (cur != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("NOW", Modifier.background(Color(0xFFF5B042), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color(0xFF1A1205), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                            Text("${cur.title}  ${hm(cur.start)}–${hm(cur.stop)}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { ((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f) },
                            Modifier.fillMaxWidth())
                    } else Text("No guide data", color = Color(0xFFB0B7C3), style = MaterialTheme.typography.bodySmall)
                    next?.let { Text("NEXT ${hm(it.start)}  ${it.title}", color = Color(0xFFB0B7C3), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ go(-1) }) { Icon(Icons.Default.KeyboardArrowDown, "Previous", tint = Color.White) }
                        FilledTonalIconButton({ if (exo.isPlaying) exo.pause() else exo.play(); poke++ }) {
                            if (playing) Text("⏸") else Icon(Icons.Default.PlayArrow, "Play")
                        }
                        IconButton({ go(1) }) { Icon(Icons.Default.KeyboardArrowUp, "Next", tint = Color.White) }
                        IconButton({ panel = true }) { Icon(Icons.Default.Menu, "Channels", tint = Color.White) }
                    }
                }
            }
        }

        AnimatedVisibility(panel, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
            val ls = rememberLazyListState()
            LaunchedEffect(Unit) { ls.scrollToItem(max(0, idx - 2)) }
            LazyColumn(Modifier.width(300.dp).fillMaxHeight().background(Color(0xEE0E1116)), state = ls) {
                itemsIndexed(channels) { i, c ->
                    val p = vm.progs(c).firstOrNull { now >= it.start && now < it.stop }
                    Row(
                        Modifier.fillMaxWidth().clickable { idx = i; panel = false; poke++ }
                            .background(if (i == idx) Color(0x33F5B042) else Color.Transparent).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}", color = Color(0xFF6B7280), modifier = Modifier.width(34.dp))
                        Logo(c, 36, vm.logo(c))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(c.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p?.title ?: "—", color = Color(0xFFB0B7C3), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (tracksDlg) {
        val tr = exo.currentTracks
        AlertDialog(
            onDismissRequest = { tracksDlg = false },
            title = { Text("Audio, subtitles & quality") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrackSection("Quality", C.TRACK_TYPE_VIDEO, tr, exo, "Auto") { tracksDlg = false }
                    TrackSection("Audio", C.TRACK_TYPE_AUDIO, tr, exo, "Auto") { tracksDlg = false }
                    TrackSection("Subtitles", C.TRACK_TYPE_TEXT, tr, exo, "Off") { tracksDlg = false }
                }
            },
            confirmButton = { TextButton({ tracksDlg = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun TrackSection(title: String, type: Int, tracks: Tracks, exo: ExoPlayer, offLabel: String, onPick: () -> Unit) {
    val items = ArrayList<Triple<Tracks.Group, Int, String>>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val label = if (type == C.TRACK_TYPE_VIDEO) {
                if (f.height > 0) "${f.height}p" + (if (f.bitrate > 0) " • ${f.bitrate / 1000} kbps" else "") else "Video ${i + 1}"
            } else {
                listOfNotNull(f.label, f.language?.uppercase(), if (f.channelCount > 0) "${f.channelCount}ch" else null)
                    .joinToString(" • ").ifBlank { "Track ${i + 1}" }
            }
            items.add(Triple(g, i, label))
        }
    }
    if (items.isEmpty() && type != C.TRACK_TYPE_TEXT) return
    Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    TextButton({
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type).setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT).build()
        onPick()
    }) { Text(offLabel) }
    for ((g, i, label) in items) {
        TextButton({
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            onPick()
        }) { Text((if (g.isTrackSelected(i)) "✓ " else "    ") + label) }
    }
}
