package com.m3upro

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

private const val DPM = 4f                 // dp per minute (30 min = 120 dp)
private const val HALF = 30 * 60_000L
private const val HOUR = 60 * 60_000L
private fun span(ms: Long): Dp = (ms / 60_000f * DPM).dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(vm: MainVm, pls: List<Playlist>, onPlay: (List<Channel>, Int) -> Unit) {
    val epgMap by vm.epg.collectAsState()
    val epgBusy by vm.epgBusy.collectAsState()
    var selId by rememberSaveable { mutableStateOf<String?>(null) }
    val pl = pls.firstOrNull { it.id == selId } ?: pls.firstOrNull()
    var q by remember { mutableStateOf("") }
    var onlyEpg by remember { mutableStateOf(true) }
    var epgDlg by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<Channel, Prog>?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val winStart = remember { (System.currentTimeMillis() / HALF) * HALF - 2 * HOUR }
    val winEnd = winStart + 28 * HOUR

    fun jumpNow(animate: Boolean) {
        val px = with(density) { span(System.currentTimeMillis() - winStart - HALF).toPx().toInt() }.coerceAtLeast(0)
        scope.launch { if (animate) scroll.animateScrollTo(px) else scroll.scrollTo(px) }
    }
    LaunchedEffect(pl?.id) {
        withTimeoutOrNull(3000) { snapshotFlow { scroll.maxValue }.first { it > 0 } }
        jumpNow(false)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("TV Guide", fontWeight = FontWeight.Bold) }, actions = {
            TextButton({ jumpNow(true) }) { Text("Now") }
            IconButton({ pl?.let { vm.loadEpg(it.id, true) } }) { Icon(Icons.Default.Refresh, "Reload guide") }
            IconButton({ epgDlg = true }) { Icon(Icons.Default.Settings, "Guide source") }
        })
    }) { pad ->
        if (pl == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add a playlist first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val data = epgMap[pl.id]
        val rows = remember(pl.channels, q, onlyEpg, data) {
            pl.channels.filter { c ->
                (q.isBlank() || c.name.contains(q, true)) && (!onlyEpg || data == null || data.forChannel(c).isNotEmpty())
            }
        }
        Column(Modifier.padding(pad)) {
            if (epgBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Search channels") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) })
            LazyRow(contentPadding = PaddingValues(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(onlyEpg, { onlyEpg = !onlyEpg }, { Text("With guide only") }) }
                if (pls.size > 1) itemsIndexed(pls) { _, p -> FilterChip(p.id == pl.id, { selId = p.id }, { Text(p.name, maxLines = 1) }) }
            }
            if (data == null) {
                Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No guide loaded for this playlist", fontWeight = FontWeight.SemiBold)
                        Text("Add an XMLTV (EPG) URL. Many playlists include one automatically.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ epgDlg = true }) { Text("Set guide source") }
                    }
                }
            }
            // time ruler (shares scroll state with every row)
            Row(Modifier.height(32.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
                Spacer(Modifier.width(112.dp))
                Row(Modifier.horizontalScroll(scroll)) {
                    for (k in 0 until 56) Box(Modifier.width(span(HALF)).fillMaxHeight().padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text(hm(winStart + k * HALF), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { i, c -> "$i${c.url}" }) { i, c ->
                    val logo = remember(c, epgMap) { vm.logo(c) }
                    Row(Modifier.height(68.dp)) {
                        Row(Modifier.width(112.dp).fillMaxHeight().clickable { onPlay(rows, i) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Logo(c, 32, logo); Spacer(Modifier.width(6.dp))
                            Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.horizontalScroll(scroll)) {
                            val progs = data?.forChannel(c).orEmpty().filter { it.stop > winStart && it.start < winEnd }
                            Box(Modifier.width(span(winEnd - winStart)).fillMaxHeight()) {
                                if (progs.isEmpty()) {
                                    Box(Modifier.fillMaxSize().padding(8.dp).clickable { onPlay(rows, i) }, contentAlignment = Alignment.CenterStart) {
                                        Text("No guide data • tap to watch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    Row {
                                        var cursor = winStart
                                        progs.forEach { p ->
                                            val s = max(p.start, cursor); val e = min(p.stop, winEnd)
                                            if (e <= s) return@forEach
                                            if (s > cursor) Spacer(Modifier.width(span(s - cursor)))
                                            val live = now >= p.start && now < p.stop
                                            Row(
                                                Modifier.width(span(e - s)).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(12.dp))
                                                    .background(if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                                    .combinedClickable(onClick = { onPlay(rows, i) }, onLongClick = { detail = c to p })
                                                    .padding(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (p.img.startsWith("http") && e - s >= 40 * 60_000L) {
                                                    AsyncImage(p.img, null, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Column {
                                                    Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                                                        style = MaterialTheme.typography.bodySmall)
                                                    Text("${hm(p.start)} – ${hm(p.stop)}", maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            cursor = e
                                        }
                                    }
                                }
                                if (now in winStart..winEnd) {
                                    Box(Modifier.offset(x = span(now - winStart)).width(2.dp).fillMaxHeight().background(Color(0xFFFF4D4D)))
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }

        detail?.let { (c, p) ->
            AlertDialog(onDismissRequest = { detail = null }, title = { Text(p.title) },
                text = {
                    Column {
                        if (p.img.startsWith("http")) AsyncImage(p.img, null,
                            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        Text("${c.name} • ${hm(p.start)} – ${hm(p.stop)}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                        if (p.desc.isNotBlank()) Text(p.desc, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { TextButton({ detail = null; onPlay(rows, rows.indexOf(c).coerceAtLeast(0)) }) { Text("Watch") } },
                dismissButton = { TextButton({ detail = null }) { Text("Close") } })
        }
        if (epgDlg) {
            var u by remember { mutableStateOf(pl.epgUrl) }
            AlertDialog(onDismissRequest = { epgDlg = false }, title = { Text("Guide source (XMLTV)") },
                text = {
                    Column {
                        if (pl.source.startsWith("portal:")) Text("This is a portal playlist; its guide is fetched from the portal automatically.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(u, { u = it }, label = { Text("EPG URL (.xml or .xml.gz)") })
                        Text("Community guides (examples):", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium)
                        TextButton({ u = "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz" }) { Text("epgshare01 • UK") }
                        TextButton({ u = "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz" }) { Text("epgshare01 • Plex") }
                        Text("Other countries use the same file pattern (epg_ripper_XX1.xml.gz); availability depends on the community host.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton({ epgDlg = false; vm.setEpgUrl(pl.id, u) }) { Text("Save & load") } },
                dismissButton = { TextButton({ epgDlg = false }) { Text("Cancel") } })
        }
    }
}
