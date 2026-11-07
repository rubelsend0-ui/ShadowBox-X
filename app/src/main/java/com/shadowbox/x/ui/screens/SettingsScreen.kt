package com.shadowbox.x.ui.screens

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shadowbox.x.core.security.BiometricHelper
import com.shadowbox.x.core.security.KeystoreHelper
import com.shadowbox.x.core.util.ClipboardHelper
import com.shadowbox.x.core.util.findActivity
import com.shadowbox.x.core.util.formatBytes
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.domain.model.CredentialType
import com.shadowbox.x.ui.components.*
import com.shadowbox.x.ui.theme.Accent
import com.shadowbox.x.ui.theme.Danger
import com.shadowbox.x.ui.theme.Ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(c: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsState()
    val snack = remember { SnackbarHostState() }
    val reauth = rememberReauth()
    var changeType by remember { mutableStateOf<CredentialType?>(null) }
    var regen by remember { mutableStateOf(false) }
    var storage by remember { mutableLongStateOf(-1L) }
    var about by remember { mutableStateOf(false) }
    val bio = remember { BiometricHelper(ctx.findActivity(), c.vault) }
    LaunchedEffect(Unit) { storage = withContext(Dispatchers.IO) { c.blobs.totalBytes() } }

    fun enableBiometric() {
        if (!bio.strongAvailable()) { scope.launch { snack.showSnackbar("Strong biometrics are not set up on this device") }; return }
        try {
            val cipher = c.vault.newBiometricEnableCipher()
            bio.promptCrypto(cipher, "Enable biometric unlock", "Confirm with your fingerprint or face", onSuccess = { ci ->
                c.vault.finishEnableBiometric(ci); c.settings.set { it.copy(biometricUnlock = true) }
            }, onError = { m -> scope.launch { snack.showSnackbar("Not enabled: $m") } })
        } catch (e: Exception) { scope.launch { snack.showSnackbar("Could not enable biometrics") } }
    }

    ScreenScaffold("Settings", snackbar = snack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Master lock")
            SettingRow("Change master password", Icons.Filled.Password) { changeType = CredentialType.PASSWORD }
            SettingRow("Change PIN", Icons.Filled.Pin) { changeType = CredentialType.PIN6 }
            ToggleRow("Biometric unlock", "Fingerprint / face (Keystore-bound)", s.biometricUnlock, Icons.Filled.Fingerprint) { on ->
                if (on) enableBiometric() else { c.vault.disableBiometric(); c.settings.set { it.copy(biometricUnlock = false) } }
            }
            ToggleRow("Biometrics before revealing passwords", "Also used for exports and locked notes", s.requireBioReveal, Icons.Filled.VisibilityOff) { on -> c.settings.set { it.copy(requireBioReveal = on) } }

            SectionTitle("Auto-lock")
            ToggleRow("Lock when app goes to background", null, s.lockOnBackground, Icons.Filled.Lock) { on -> c.settings.set { it.copy(lockOnBackground = on) } }
            Text("Lock delay", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(0 to "Immediately", 15 to "15 s", 30 to "30 s", 60 to "1 min", 300 to "5 min")) { (v, l) ->
                    FilterChip(selected = s.autoLockSeconds == v, onClick = { c.settings.set { it.copy(autoLockSeconds = v) } }, label = { Text(l) })
                }
            }
            Text("Clipboard auto-clear", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(0 to "Never", 10 to "10 s", 30 to "30 s", 60 to "1 min", 120 to "2 min")) { (v, l) ->
                    FilterChip(selected = s.clipboardClearSeconds == v, onClick = { c.settings.set { it.copy(clipboardClearSeconds = v) } }, label = { Text(l) })
                }
            }

            SectionTitle("Recovery")
            SettingRow("Generate a new recovery key", Icons.Filled.VpnKey) { regen = true }
            Text("The old recovery key stops working immediately.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Backup")
            Text("Last: ${s.lastBackupStatus}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Open Encrypted Backup from the Home screen to export, restore or schedule.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Theme")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Accent.values().toList()) { a -> FilterChip(selected = s.accent == a.name, onClick = { c.settings.set { it.copy(accent = a.name) } }, label = { Text(a.label) }) }
            }

            SectionTitle("Storage")
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Vault storage on device: ${if (storage < 0) "..." else formatBytes(storage)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { c.blobs.clearTmp(); c.files.clearCaches(); c.files.cleanupOrphans(); storage = c.blobs.totalBytes(); snack.showSnackbar("Temporary files and orphans cleared") } }) { Text("Clean up") }
                }
            }

            SectionTitle("Security status")
            val secure = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
            GlassCard(Modifier.fillMaxWidth()) {
                StatusRow("AES-256-GCM encryption at rest", true)
                StatusRow("Per-file keys, VMK in RAM only", true)
                StatusRow("Keystore key hardware-backed", KeystoreHelper.isHardwareBacked())
                StatusRow("Device screen lock enabled", secure)
                StatusRow("Biometric unlock enabled", s.biometricUnlock)
                StatusRow("Screenshots / recents blocked (FLAG_SECURE)", true)
                StatusRow("Backup made", s.lastBackupAt != 0L)
            }
            SettingRow("About ShadowBox", Icons.Filled.Info) { about = true }
            Spacer(Modifier.height(24.dp))
        }
    }

    changeType?.let { t -> ChangeSecretDialog(c, t, onDismiss = { changeType = null }) { scope.launch { snack.showSnackbar("Master lock changed") } } }
    if (regen) RegenRecoveryDialog(c, onDismiss = { regen = false })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("SHADOWBOX X 1.0.0") }, confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
        text = { Text("Your Digital World. Your Privacy.\n\nOffline-first encrypted vault. No analytics, no tracking, no hidden uploads. Security cannot be absolute: keep your recovery key and backups safe.") })
}

@Composable
private fun RegenRecoveryDialog(c: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val numeric = c.vault.credentialType() != CredentialType.PASSWORD
    var v by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New recovery key") },
        text = {
            Column {
                Text("Enter your current master ${if (numeric) "PIN" else "password"}. The old recovery key will stop working.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SecretField(v, { v = it; err = false }, "Current secret", Modifier.fillMaxWidth(), numeric = numeric, error = err)
            }
        },
        confirmButton = {
            TextButton(enabled = v.isNotEmpty() && !busy, onClick = {
                scope.launch {
                    busy = true
                    val rec = withContext(Dispatchers.Default) { c.vault.regenerateRecovery(v.toCharArray()) }
                    busy = false
                    if (rec == null) err = true else { c.pendingRecovery.value = rec; onDismiss() }
                }
            }) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ChangeSecretDialog(c: AppContainer, initial: CredentialType, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val form = remember { SecretForm(initial) }
    var old by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val oldNumeric = c.vault.credentialType() != CredentialType.PASSWORD
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Change master lock") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretField(old, { old = it }, "Current ${if (oldNumeric) "PIN" else "password"}", Modifier.fillMaxWidth(), numeric = oldNumeric)
                Text("New lock", style = MaterialTheme.typography.labelLarge)
                SecretFormFields(form)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = old.isNotEmpty() && form.error() == null && !busy, onClick = {
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.Default) { c.vault.changeSecret(old.toCharArray(), form.secret.toCharArray(), form.type) }
                    busy = false
                    if (ok) { onDone(); onDismiss() } else err = "Current secret is wrong"
                }
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SettingRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(14.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String?, checked: Boolean, icon: ImageVector, onChange: (Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onChange)
        }
    }
}

@Composable
private fun StatusRow(label: String, good: Boolean) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (good) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (good) Ok else Danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp)); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
