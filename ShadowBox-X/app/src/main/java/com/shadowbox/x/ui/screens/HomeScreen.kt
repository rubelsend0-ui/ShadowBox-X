package com.shadowbox.x.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shadowbox.x.core.util.formatBytes
import com.shadowbox.x.core.util.formatDate
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.ui.components.*
import com.shadowbox.x.ui.theme.Cyan
import com.shadowbox.x.ui.theme.Ok

@Composable
fun HomeScreen(c: AppContainer, go: (String) -> Unit) {
    val files by remember { c.files.observe() }.collectAsState(initial = emptyList())
    val folders by remember { c.folders.observe() }.collectAsState(initial = emptyList())
    val creds by remember { c.credentials.observe() }.collectAsState(initial = emptyList())
    val notes by remember { c.notes.observe() }.collectAsState(initial = emptyList())
    val settings by c.settings.settings.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SHADOWBOX X", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Your Digital World. Your Privacy.", color = Cyan, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { c.vault.lock() }) { Icon(Icons.Filled.LockOpen, "Lock now") }
        }
        Spacer(Modifier.height(14.dp))
        StatusPill("Vault Status: PROTECTED", Ok)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Secret Files", Icons.Filled.Lock, Modifier.weight(1f)) { go("vault") }
            Tile("Password Manager", Icons.Filled.VpnKey, Modifier.weight(1f)) { go("passwords") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Secret Folders", Icons.Filled.Folder, Modifier.weight(1f)) { go("folders") }
            Tile("Private Notes", Icons.Filled.StickyNote2, Modifier.weight(1f)) { go("notes") }
        }
        Spacer(Modifier.height(12.dp))
        WideTile("Shadow Gallery", "Photos, videos and audio", Icons.Filled.PhotoLibrary) { go("gallery") }
        Spacer(Modifier.height(10.dp))
        WideTile("Shadow Sync", "Device to device over local Wi-Fi", Icons.Filled.Sync) { go("sync") }
        Spacer(Modifier.height(10.dp))
        WideTile("Encrypted Backup", "Export, restore and schedule", Icons.Filled.Backup) { go("backup") }

        SectionTitle("Storage dashboard")
        GlassCard(Modifier.fillMaxWidth()) {
            Stat("Total vault size", formatBytes(files.sumOf { it.blobSize }))
            Stat("Encrypted files", files.size.toString())
            Stat("Folders", folders.size.toString())
            Stat("Saved credentials", creds.size.toString())
            Stat("Private notes", notes.size.toString())
            Stat("Last backup", if (settings.lastBackupAt == 0L) "Never" else formatDate(settings.lastBackupAt))
            Text(settings.lastBackupStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tile(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.height(112.dp), onClick = onClick) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun WideTile(title: String, sub: String, icon: ImageVector, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
