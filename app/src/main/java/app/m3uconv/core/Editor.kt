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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainVm, pl: Playlist, onPlay: (List<Channel>, Int) -> Unit, onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var sel by remember { mutableStateOf(setOf<Int>()) }
    var menu by remember { mutableStateOf(false) }
    var groupDlg by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Int?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { it?.let { u -> vm.export(u, pl) } }
    val groups = remember(pl.channels) { pl.channels.map { it.group }.filter { it.isNotBlank() }.distinct().sorted() }
    val shown = remember(pl.channels, q, group) {
        pl.channels.withIndex().filter { (_, c) ->
            (group == null || c.group == group) && (q.isBlank() || c.name.contains(q, true))
        }.toList()
    }
    fun setChannels(f: (List<Channel>) -> List<Channel>) { vm.update(pl.id) { it.copy(channels = f(it.channels)) }; sel = emptySet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton({ vm.checkChannels(pl.id) }) { Icon(Icons.Default.Refresh, "Check channels") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove dead channels") }, onClick = { menu = false; setChannels { l -> l.filter { it.status != 2 } } })
                            DropdownMenuItem(text = { Text("Remove duplicates") }, onClick = { menu = false; setChannels { l -> l.distinctBy { it.url } } })
                            DropdownMenuItem(text = { Text("Sort A–Z") }, onClick = { menu = false; setChannels { l -> l.sortedBy { it.name.lowercase() } } })
                            DropdownMenuItem(text = { Text("Select all shown") }, onClick = { menu = false; sel = shown.map { it.index }.toSet() })
                            DropdownMenuItem(text = { Text("Export .m3u") }, onClick = { menu = false; export.launch(pl.name.replace(Regex("[^\\w.-]"), "_") + ".m3u") })
                        }
                    }
                })
        },
        bottomBar = {
            if (sel.isNotEmpty()) BottomAppBar {
                Text("${sel.size} selected", Modifier.padding(start = 16.dp).weight(1f))
                TextButton({ groupDlg = true }) { Text("Set group") }
                TextButton({ setChannels { l -> l.filterIndexed { i, _ -> i !in sel } } }) { Text("Delete") }
                IconButton({ sel = emptySet() }) { Icon(Icons.Default.Close, "Clear") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Search ${pl.channels.size} channels") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Default.Search, null) })
            if (groups.isNotEmpty()) LazyRow(contentPadding = PaddingValues(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(group == null, { group = null }, { Text("All") }) }
                items(groups) { g -> FilterChip(group == g, { group = if (group == g) null else g }, { Text(g, maxLines = 1) }) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, it -> it.index }) { pos, (i, c) ->
                    ListItem(
                        modifier = Modifier.clickable { onPlay(shown.map { it.value }, pos) },
                        headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(c.group.ifBlank { "No group" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(i in sel, { on -> sel = if (on) sel + i else sel - i })
                                Logo(c, 40)
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(c.status)
                                IconButton({ vm.toggleFav(c.url) }) {
                                    Icon(if (c.fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite",
                                        tint = if (c.fav) Color(0xFFFF6B8B) else LocalContentColor.current)
                                }
                                IconButton({ edit = i }) { Icon(Icons.Default.Edit, "Edit") }
                            }
                        })
                }
            }
        }
    }

    if (groupDlg) {
        var g by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { groupDlg = false }, title = { Text("Set group for ${sel.size} channels") },
            text = { OutlinedTextField(g, { g = it }, label = { Text("Group name") }, singleLine = true) },
            confirmButton = { TextButton({ groupDlg = false; setChannels { l -> l.mapIndexed { i, c -> if (i in sel) c.copy(group = g) else c } } }) { Text("Apply") } },
            dismissButton = { TextButton({ groupDlg = false }) { Text("Cancel") } })
    }
    edit?.let { i ->
        pl.channels.getOrNull(i)?.let { c ->
            var n by remember(i) { mutableStateOf(c.name) }
            var g by remember(i) { mutableStateOf(c.group) }
            var u by remember(i) { mutableStateOf(c.url) }
            AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit channel") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(n, { n = it }, label = { Text("Name") })
                        OutlinedTextField(g, { g = it }, label = { Text("Group") })
                        OutlinedTextField(u, { u = it }, label = { Text("URL") })
                    }
                },
                confirmButton = { TextButton({ edit = null; setChannels { l -> l.mapIndexed { k, x -> if (k == i) x.copy(name = n, group = g, url = u, status = 0) else x } } }) { Text("Save") } },
                dismissButton = { TextButton({ edit = null }) { Text("Cancel") } })
        }
    }
}

