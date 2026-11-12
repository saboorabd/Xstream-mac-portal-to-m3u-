package com.m3upro

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

fun hm(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF5B042), onPrimary = Color(0xFF1A1205),
            primaryContainer = Color(0xFF4A3410), onPrimaryContainer = Color(0xFFFFE2B0),
            secondary = Color(0xFFFF7A3D), background = Color(0xFF05080F), onBackground = Color(0xFFF2F6FC),
            surface = Color(0xFF05080F), onSurface = Color(0xFFF2F6FC),
            surfaceVariant = Color(0xFF141F38), onSurfaceVariant = Color(0xFF8A9BB8),
            surfaceContainer = Color(0xFF0F182C), surfaceContainerHighest = Color(0xFF1A2745),
            outline = Color(0xFF3A4A6B)
        ), content = content
    )
}

@Composable
fun Dot(s: Int) {
    val c = when (s) { 1 -> Color(0xFF34D399); 2 -> Color(0xFFF87171); else -> Color(0xFF6B7280) }
    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
fun Logo(c: Channel, size: Int, url: String = c.logo) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        Text(c.name.trim().take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = (size / 2.4).sp,
            color = MaterialTheme.colorScheme.primary)
        if (url.startsWith("http")) AsyncImage(url, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
    }
}

data class PlayReq(val channels: List<Channel>, val index: Int)

@Composable
fun App(vm: MainVm) {
    val pls by vm.playlists.collectAsState()
    val busy by vm.busy.collectAsState()
    val prog by vm.progress.collectAsState()
    val msg by vm.message.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (vm.playlists.value.isEmpty()) 3 else 0) }
    var openId by remember { mutableStateOf<String?>(null) }
    var portal by remember { mutableStateOf(false) }
    var play by remember { mutableStateOf<PlayReq?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    BackHandler(play != null || openId != null || portal) {
        when { play != null -> play = null; portal -> portal = false; else -> openId = null }
    }
    val onPlay: (List<Channel>, Int) -> Unit = { l, i -> play = PlayReq(l, i) }

    Box(Modifier.fillMaxSize()) {
        val open = pls.firstOrNull { it.id == openId }
        val req = play
        when {
            req != null -> PlayerScreen(vm, req.channels, req.index) { play = null }
            portal -> PortalScreen(vm) { portal = false }
            open != null -> EditorScreen(vm, open, onPlay) { openId = null }
            else -> Scaffold(bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Live") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.DateRange, null) }, label = { Text("Guide") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Favorite, null) }, label = { Text("Favorites") })
                    NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Home, null) }, label = { Text("Library") })
                }
            }) { pad ->
                Box(Modifier.padding(pad)) {
                    when (tab) {
                        0 -> LiveScreen(vm, pls, onPlay)
                        1 -> GuideScreen(vm, pls, onPlay)
                        2 -> FavoritesScreen(vm, pls, onPlay)
                        else -> HomeScreen(vm, pls, { openId = it }, { portal = true })
                    }
                }
            }
        }
        if (req == null) busy?.let {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), tonalElevation = 6.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text(it, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    if (prog >= 0f) LinearProgressIndicator(progress = { prog }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = if (req == null && open == null && !portal) 84.dp else 0.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainVm, pls: List<Playlist>, onOpen: (String) -> Unit, onPortal: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var urlDlg by remember { mutableStateOf(false) }
    var xtreamDlg by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Playlist?>(null) }
    val deep by vm.deepCheck.collectAsState()
    val guide by vm.epg.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addFromUri) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("M3U Pro", fontWeight = FontWeight.Bold) }, actions = {
                IconButton({ vm.autoCheck() }) { Icon(Icons.Default.Refresh, "Recheck") }
                Box {
                    IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("Deep-check channels on launch") },
                            trailingIcon = { Checkbox(deep, null) }, onClick = { vm.setDeep(!deep) })
                        DropdownMenuItem(text = { Text("Merge all playlists into one") }, onClick = { more = false; vm.mergeAll() })
                    }
                }
            })
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(onClick = { menu = true }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add")
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("From URL") }, onClick = { menu = false; urlDlg = true })
                    DropdownMenuItem(text = { Text("From file") }, onClick = { menu = false; picker.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("Xtream Codes login") }, onClick = { menu = false; xtreamDlg = true })
                    DropdownMenuItem(text = { Text("MAC portal login") }, onClick = { menu = false; onPortal() })
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pls.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📺", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
                    Text("Add from URL, file, Xtream or MAC portal.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(pls, key = { it.id }) { p ->
                val ok = p.channels.count { it.status == 1 }
                val dead = p.channels.count { it.status == 2 }
                Card(onClick = { onOpen(p.id) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(p.state)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.channels.size} channels • $ok live • $dead dead" + if (guide.containsKey(p.id)) " • Guide ✓" else "",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Checked ${fmt.format(Date(p.lastChecked))}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ del = p }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (xtreamDlg) XtreamDialog(vm) { xtreamDlg = false }
    if (urlDlg) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { urlDlg = false }, title = { Text("Add playlist from URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("M3U URL") })
                }
            },
            confirmButton = { TextButton({ urlDlg = false; vm.addFromUrl(name, url.trim()) }, enabled = url.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton({ urlDlg = false }) { Text("Cancel") } })
    }
    del?.let { p ->
        AlertDialog(onDismissRequest = { del = null }, title = { Text("Delete \"${p.name}\"?") },
            confirmButton = { TextButton({ vm.delete(p.id); del = null }) { Text("Delete") } },
            dismissButton = { TextButton({ del = null }) { Text("Cancel") } })
    }
}

