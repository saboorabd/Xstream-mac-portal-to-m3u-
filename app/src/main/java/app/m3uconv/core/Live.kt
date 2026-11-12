package com.m3upro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Main "Live TV" screen: every channel of every playlist, tap = play instantly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(vm: MainVm, pls: List<Playlist>, onPlay: (List<Channel>, Int) -> Unit) {
    val guide by vm.epg.collectAsState()
    val recents by vm.recents.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    val all = remember(pls) { pls.flatMap { it.channels } }
    val groups = remember(all) {
        all.map { it.group }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(40).map { it.key }
    }
    val shown = remember(all, q, group) {
        all.filter { (group == null || it.group == group) && (q.isBlank() || it.name.contains(q, true)) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Live TV", fontWeight = FontWeight.Bold) }) }) { pad ->
        if (all.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add a playlist in the Library tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Search ${all.size} channels") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Default.Search, null) })

            if (q.isBlank() && recents.isNotEmpty()) {
                Text("Continue watching", Modifier.padding(start = 16.dp, top = 12.dp),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LazyRow(contentPadding = PaddingValues(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(recents) { i, c ->
                        Card(onClick = { onPlay(recents, i) }, Modifier.width(150.dp), shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Logo(c, 40, remember(c, guide) { vm.logo(c) })
                                Spacer(Modifier.height(8.dp))
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("Resume", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            if (groups.isNotEmpty()) LazyRow(contentPadding = PaddingValues(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(group == null, { group = null }, { Text("All") }) }
                items(groups) { g -> FilterChip(group == g, { group = if (group == g) null else g }, { Text(g, maxLines = 1) }) }
            }

            LazyVerticalGrid(
                GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                gridItemsIndexed(shown) { i, c ->
                    val cur = remember(c, guide, now) { vm.progs(c).firstOrNull { now >= it.start && now < it.stop } }
                    val logo = remember(c, guide) { vm.logo(c) }
                    Card(onClick = { onPlay(shown, i) }, shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Logo(c, 44, logo)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text(c.group.ifBlank { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(cur?.title ?: "Tap to watch", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cur != null) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { ((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f) },
                                    Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}
