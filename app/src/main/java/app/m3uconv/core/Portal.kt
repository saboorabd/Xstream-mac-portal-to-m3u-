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
fun PortalScreen(vm: MainVm, onBack: () -> Unit) {
    val init = remember { vm.portalCfg() }
    var url by remember { mutableStateOf(init.url) }
    var mac by remember { mutableStateOf(init.mac) }
    var d1 by remember { mutableStateOf(init.deviceId) }
    var d2 by remember { mutableStateOf(init.deviceId2) }
    var sn by remember { mutableStateOf(init.serial) }
    val macOk = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac.trim())

    Scaffold(topBar = {
        TopAppBar(title = { Text("MAC portal login") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Portal URL") }, singleLine = true)
            OutlinedTextField(mac, { mac = it.uppercase() }, Modifier.fillMaxWidth(), label = { Text("MAC address") },
                placeholder = { Text("00:1A:79:XX:XX:XX") }, singleLine = true, isError = mac.isNotEmpty() && !macOk)
            OutlinedTextField(d1, { d1 = it }, Modifier.fillMaxWidth(), label = { Text("Device ID 1") }, singleLine = true)
            OutlinedTextField(d2, { d2 = it }, Modifier.fillMaxWidth(), label = { Text("Device ID 2") }, singleLine = true)
            OutlinedTextField(sn, { sn = it }, Modifier.fillMaxWidth(), label = { Text("Serial number") }, singleLine = true)
            Button(
                onClick = { vm.portalLogin(PortalCfg(url.trim(), mac.trim(), d1.trim(), d2.trim(), sn.trim()), onBack) },
                enabled = url.isNotBlank() && macOk, modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Login & import channels") }
        }
    }
}


@Composable
fun XtreamDialog(vm: MainVm, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf("") }
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xtream Codes login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s, { s = it }, label = { Text("Server URL") }, singleLine = true)
                OutlinedTextField(u, { u = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(p, { p = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton({ onDismiss(); vm.xtreamLogin(s.trim(), u.trim(), p.trim()) },
                enabled = s.isNotBlank() && u.isNotBlank() && p.isNotBlank()) { Text("Login") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
