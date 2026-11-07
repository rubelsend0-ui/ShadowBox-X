package com.shadowbox.x.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shadowbox.x.core.util.CreateDocWithMime
import com.shadowbox.x.core.util.formatBytes
import com.shadowbox.x.core.util.formatDate
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.ui.components.*
import com.shadowbox.x.work.BackupScheduler
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(c: AppContainer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val settings by c.settings.settings.collectAsState()
    val progress by c.backup.progress.collectAsState()
    val reauth = rememberReauth()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    var verifyUri by remember { mutableStateOf<Uri?>(null) }
    val pwOk = pw.length >= 8 && pw == pw2

    val create = rememberLauncherForActivityResult(CreateDocWithMime()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { c.backup.create(it, pw.toCharArray()) }
                c.backup.markResult(true, "manual backup")
                snack.showSnackbar("Encrypted backup saved")
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                c.backup.markResult(false, e.message ?: "error"); snack.showSnackbar("Backup failed: ${e.message}")
            }
            busy = false
        }
    }
    val verifyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { verifyUri = it }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            c.settings.set { it.copy(backupTree = uri.toString()) }
        }
    }

    ScreenScaffold("Encrypted Backup", onBack = onBack, snackbar = snack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Last backup", style = MaterialTheme.typography.titleMedium)
                Text(if (settings.lastBackupAt == 0L) "Never" else formatDate(settings.lastBackupAt))
                Text(settings.lastBackupStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Export backup", style = MaterialTheme.typography.titleMedium)
                Text("Everything is encrypted with a backup password (AES-256-GCM, PBKDF2). Save the file anywhere, including a cloud drive via the system file picker. Without the password it cannot be opened.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SecretField(pw, { pw = it }, "Backup password (min 8)", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecretField(pw2, { pw2 = it }, "Confirm password", Modifier.fillMaxWidth(), error = pw2.isNotEmpty() && pw != pw2)
                Spacer(Modifier.height(8.dp))
                Button(enabled = pwOk && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    reauth("Create backup") { c.vault.externalFlow = true; create.launch(c.backup.suggestedName() to "application/octet-stream") }
                }) { Icon(Icons.Filled.Backup, null); Spacer(Modifier.width(8.dp)); Text("Create backup") }
            }
            progress?.let { p ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(p.phase)
                    if (p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text("${formatBytes(p.done)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Verify or restore", style = MaterialTheme.typography.titleMedium)
                Text("Verify reads the whole file and checks every authentication tag. Restore replaces the entire vault with the backup.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.vault.externalFlow = true; verifyPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.VerifiedUser, null); Spacer(Modifier.width(6.dp)); Text("Verify") }
                    OutlinedButton(onClick = { restore = true }) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(6.dp)); Text("Restore") }
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Scheduled backup", style = MaterialTheme.typography.titleMedium)
                Text("Saved into a folder you choose. Because the vault key exists only while the app is unlocked, a due backup runs right after your next unlock. The backup password is stored sealed by the device Keystore.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = { c.vault.externalFlow = true; treePicker.launch(null) }) {
                    Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(if (settings.backupTree == null) "Choose destination folder" else "Destination chosen - change")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 1 to "Daily", 7 to "Weekly").forEach { (d, l) ->
                        FilterChip(selected = settings.backupDays == d, label = { Text(l) }, enabled = d == 0 || (settings.backupTree != null && settings.backupPwBlob != null), onClick = {
                            c.settings.set { it.copy(backupDays = d) }; BackupScheduler.apply(ctx.applicationContext, d)
                        })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(enabled = pwOk && settings.backupTree != null, onClick = {
                    scope.launch { c.backup.storeSchedulePassword(pw.toCharArray()); snack.showSnackbar("Scheduled-backup password saved (sealed by Keystore)") }
                }) { Text("Use password above for scheduled backups") }
            }
        }
    }
    if (restore) RestoreDialog(c) { restore = false }
    verifyUri?.let { u -> VerifyDialog(c, u) { verifyUri = null } }
}

@Composable
private fun VerifyDialog(c: AppContainer, uri: Uri, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Verify backup") },
        text = {
            Column {
                SecretField(pw, { pw = it }, "Backup password", Modifier.fillMaxWidth())
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                result?.let { Text(it, color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty() && !busy, onClick = {
                scope.launch {
                    busy = true; result = null
                    try {
                        val i = c.backup.verify({ ctx.contentResolver.openInputStream(uri) }, pw.toCharArray())
                        ok = true; result = "Integrity verified. Created ${formatDate(i.createdAt)}: ${i.files} files, ${i.folders} folders, ${i.credentials} credentials, ${i.notes} notes."
                    } catch (e: Exception) { ok = false; result = e.message ?: "Verification failed" }
                    busy = false
                }
            }) { Text("Verify") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Restore replaces the whole vault and asks for a NEW master lock (works on a fresh install too). */
@Composable
fun RestoreDialog(c: AppContainer, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = remember { SecretForm() }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri = it }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Restore backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This replaces everything currently in the vault.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { c.vault.externalFlow = true; picker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text(if (uri == null) "Choose backup file" else "Backup file selected") }
                SecretField(pw, { pw = it }, "Backup password", Modifier.fillMaxWidth())
                Text("New master lock", style = MaterialTheme.typography.labelLarge)
                SecretFormFields(form)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = uri != null && pw.isNotEmpty() && form.error() == null && !busy, onClick = {
                scope.launch {
                    busy = true; err = null
                    try {
                        val u = uri!!
                        val rec = c.backup.restore({ ctx.contentResolver.openInputStream(u) }, pw.toCharArray(), form.secret.toCharArray(), form.type)
                        c.pendingRecovery.value = rec
                        onDismiss()
                    } catch (e: Exception) { err = e.message ?: "Restore failed" }
                    busy = false
                }
            }) { Text("Restore") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
