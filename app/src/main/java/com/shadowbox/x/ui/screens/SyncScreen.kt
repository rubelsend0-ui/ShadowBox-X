package com.shadowbox.x.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.shadowbox.x.core.util.QrUtil
import com.shadowbox.x.core.util.formatBytes
import com.shadowbox.x.data.repo.SyncRepository
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.ui.components.*
import com.shadowbox.x.ui.theme.Ok

@Composable
fun SyncScreen(c: AppContainer, onBack: () -> Unit) {
    val state by c.sync.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { c.sync.startSend(it) } }
    DisposableEffect(Unit) { onDispose { c.sync.cancel() } }

    ScreenScaffold("Shadow Sync", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                Tab(tab == 0, { tab = 0; c.sync.cancel() }, text = { Text("Receive") })
                Tab(tab == 1, { tab = 1; c.sync.cancel() }, text = { Text("Send") })
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Both devices must be unlocked and on the same Wi-Fi (or one device's hotspot). Files stay encrypted in transit and on disk. No cloud account is used.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tab == 0) {
                if (state.phase == SyncRepository.Phase.IDLE || state.phase == SyncRepository.Phase.ERROR || state.phase == SyncRepository.Phase.DONE)
                    Button(onClick = { c.sync.startReceive() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("Show pairing QR") }
                state.pairing?.let { p ->
                    if (state.phase == SyncRepository.Phase.WAITING) {
                        val bmp = remember(p) { QrUtil.bitmap(p) }
                        GlassCard(Modifier.fillMaxWidth()) {
                            Image(bmp.asImageBitmap(), "Pairing QR code", Modifier.fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
                            Text("Scan this from the other device (Send tab). The code is valid for one transfer.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
            } else {
                Button(onClick = {
                    c.vault.externalFlow = true
                    scanner.launch(ScanOptions().setPrompt("Scan the receiver's QR code").setBeepEnabled(false).setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE))
                }, modifier = Modifier.fillMaxWidth(), enabled = state.phase != SyncRepository.Phase.TRANSFERRING) {
                    Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan QR and send vault files")
                }
            }
            if (state.phase != SyncRepository.Phase.IDLE) {
                GlassCard(Modifier.fillMaxWidth()) {
                    val color = when (state.phase) { SyncRepository.Phase.ERROR -> MaterialTheme.colorScheme.error; SyncRepository.Phase.DONE -> Ok; else -> MaterialTheme.colorScheme.onSurface }
                    Text(state.message, color = color, style = MaterialTheme.typography.titleMedium)
                    if (state.phase == SyncRepository.Phase.TRANSFERRING || state.phase == SyncRepository.Phase.CONNECTING) {
                        Spacer(Modifier.height(8.dp))
                        if (state.total > 0) {
                            LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${formatBytes(state.done)} / ${formatBytes(state.total)}", style = MaterialTheme.typography.bodyMedium)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { c.sync.cancel() }) { Text("Cancel (resumes next time)") }
                    }
                }
            }
        }
    }
}
