package com.shadowbox.x.ui.screens

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shadowbox.x.core.security.BiometricHelper
import com.shadowbox.x.core.security.UnlockResult
import com.shadowbox.x.core.util.ClipboardHelper
import com.shadowbox.x.core.util.findActivity
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.domain.model.CredentialType
import com.shadowbox.x.ui.components.*
import com.shadowbox.x.ui.theme.Cyan
import com.shadowbox.x.ui.theme.Ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecretForm(initial: CredentialType = CredentialType.PASSWORD) {
    var type by mutableStateOf(initial)
    var secret by mutableStateOf("")
    var confirm by mutableStateOf("")

    fun error(): String? = when (type) {
        CredentialType.PASSWORD -> if (secret.length < 8) "Use at least 8 characters" else if (secret != confirm) "Entries do not match" else null
        CredentialType.PIN4 -> if (!secret.matches(Regex("\\d{4}"))) "Enter exactly 4 digits" else if (secret != confirm) "PINs do not match" else null
        CredentialType.PIN6 -> if (!secret.matches(Regex("\\d{6}"))) "Enter exactly 6 digits" else if (secret != confirm) "PINs do not match" else null
        CredentialType.CUSTOM_PIN -> if (!secret.matches(Regex("\\d{4,16}"))) "Use 4 to 16 digits" else if (secret != confirm) "PINs do not match" else null
    }
}

private fun CredentialType.label() = when (this) {
    CredentialType.PASSWORD -> "Password"; CredentialType.PIN4 -> "4-digit PIN"
    CredentialType.PIN6 -> "6-digit PIN"; CredentialType.CUSTOM_PIN -> "Custom PIN"
}

@Composable
fun SecretFormFields(form: SecretForm) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CredentialType.values().toList()) { t ->
            FilterChip(selected = form.type == t, onClick = { form.type = t; form.secret = ""; form.confirm = "" }, label = { Text(t.label()) })
        }
    }
    Spacer(Modifier.height(12.dp))
    val numeric = form.type != CredentialType.PASSWORD
    SecretField(form.secret, { form.secret = if (numeric) it.filter(Char::isDigit) else it }, if (numeric) "PIN" else "Master password", Modifier.fillMaxWidth(), numeric)
    Spacer(Modifier.height(8.dp))
    SecretField(form.confirm, { form.confirm = if (numeric) it.filter(Char::isDigit) else it }, "Confirm", Modifier.fillMaxWidth(), numeric)
    if (form.secret.isNotEmpty() || form.confirm.isNotEmpty()) {
        form.error()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
fun SetupScreen(c: AppContainer) {
    val form = remember { SecretForm() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Filled.Shield, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text("SHADOWBOX X", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Your Digital World. Your Privacy.", color = Cyan)
        Spacer(Modifier.height(24.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Create your master lock", style = MaterialTheme.typography.titleLarge)
            Text("It protects everything. It is never stored and cannot be reset without your recovery key or a backup.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SecretFormFields(form)
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = form.error() == null && !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        val rec = withContext(Dispatchers.Default) { c.vault.setup(form.secret.toCharArray(), form.type) }
                        c.pendingRecovery.value = rec
                        busy = false
                    }
                }
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Create vault") }
        }
        TextButton(onClick = { restore = true }) { Text("Restore from encrypted backup") }
    }
    if (restore) RestoreDialog(c) { restore = false }
}

@Composable
fun RecoveryKeyScreen(key: String, onDone: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.VpnKey, null, Modifier.size(56.dp), tint = Cyan)
        Text("Your recovery key", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Write it down and keep it somewhere safe and offline. It is the only way back in if you forget your master lock. It is shown once.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Text(key, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { ClipboardHelper.copy(ctx, c.appScope, "Recovery key", key, 30) }) { Text("Copy (clears in 30 s)") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { saved = !saved }) {
            Checkbox(saved, { saved = it }); Text("I have stored my recovery key safely")
        }
        Spacer(Modifier.height(12.dp))
        Button(enabled = saved, onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun PinPad(onDigit: (Char) -> Unit, onDelete: () -> Unit, onBio: (() -> Unit)?) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { k ->
                    Box(Modifier.size(72.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White.copy(alpha = if (k == "bio" && onBio == null) 0f else 0.08f))
                        .clickable(enabled = !(k == "bio" && onBio == null)) {
                            when (k) { "del" -> onDelete(); "bio" -> onBio?.invoke(); else -> onDigit(k[0]) }
                        }, contentAlignment = Alignment.Center) {
                        when (k) {
                            "del" -> Icon(Icons.Filled.Backspace, "Delete")
                            "bio" -> if (onBio != null) Icon(Icons.Filled.Fingerprint, "Biometric unlock", tint = Cyan)
                            else -> Text(k, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LockScreen(c: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = remember { c.vault.credentialType() }
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(c.vault.lockoutRemaining()) }
    var forgot by remember { mutableStateOf(false) }
    val hasBio = remember { c.vault.hasBiometric() }

    fun tryBio() {
        val cipher = try { c.vault.biometricUnlockCipher() } catch (e: KeyPermanentlyInvalidatedException) {
            c.vault.disableBiometric(); c.settings.set { it.copy(biometricUnlock = false) }
            msg = "Biometrics changed. Unlock with your master lock and re-enable them."; null
        } catch (e: Exception) { null }
        if (cipher != null) BiometricHelper(ctx.findActivity(), c.vault).promptCrypto(cipher, "Unlock SHADOWBOX X", "Use your fingerprint or face",
            onSuccess = { ci -> if (!c.vault.finishBiometricUnlock(ci)) msg = "Biometric unlock failed" }, onError = { })
    }

    fun submit(value: String) {
        if (busy || value.isEmpty()) return
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.Default) { c.vault.unlock(value.toCharArray()) }
            busy = false
            when (r) {
                UnlockResult.Success -> {}
                UnlockResult.Wrong -> { msg = "Wrong ${if (type == CredentialType.PASSWORD) "password" else "PIN"}"; input = ""; remaining = c.vault.lockoutRemaining() }
                UnlockResult.DeviceKeyLost -> msg = "This vault cannot be opened on this device (key lost). Restore from a backup."
                is UnlockResult.LockedOut -> { remaining = r.millis; input = "" }
            }
        }
    }

    LaunchedEffect(Unit) { if (hasBio && c.settings.current().biometricUnlock) tryBio() }
    LaunchedEffect(remaining) { while (remaining > 0) { delay(1000); remaining = c.vault.lockoutRemaining() } }

    val fixed = when (type) { CredentialType.PIN4 -> 4; CredentialType.PIN6 -> 6; else -> 0 }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Filled.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("SHADOWBOX X", style = MaterialTheme.typography.headlineLarge)
        Text("Vault locked", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (fixed > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(fixed) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < input.length) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f))) }
            }
        } else if (type == CredentialType.PASSWORD) {
            SecretField(input, { input = it; msg = null }, "Master password", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { submit(input) }, enabled = input.isNotEmpty() && !busy && remaining == 0L, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
        } else {
            Text("•".repeat(input.length).ifEmpty { " " }, style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))
        if (remaining > 0) Text("Too many attempts. Try again in ${remaining / 1000 + 1} s", color = MaterialTheme.colorScheme.error)
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (type != CredentialType.PASSWORD) {
            Spacer(Modifier.height(16.dp))
            PinPad(
                onDigit = { d -> if (remaining == 0L && input.length < 16) { input += d; msg = null; if (fixed > 0 && input.length == fixed) submit(input) } },
                onDelete = { input = input.dropLast(1) },
                onBio = if (hasBio) ({ tryBio() }) else null
            )
            if (fixed == 0) Button(onClick = { submit(input) }, enabled = input.length >= 4 && !busy && remaining == 0L, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) { Text("Unlock") }
        } else if (hasBio) {
            TextButton(onClick = { tryBio() }) { Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Use biometrics") }
        }
        TextButton(onClick = { forgot = true }) { Text("Forgot? Use recovery key") }
        if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
    }
    if (forgot) RecoveryDialog(c) { forgot = false }
}

@Composable
private fun RecoveryDialog(c: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val form = remember { SecretForm() }
    var key by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Recover with key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(key, { key = it; err = null }, label = { Text("Recovery key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Set a new master lock", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                SecretFormFields(form)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { restore = true }) { Text("Restore from backup instead") }
            }
        },
        confirmButton = {
            TextButton(enabled = key.length >= 10 && form.error() == null && !busy, onClick = {
                scope.launch {
                    busy = true
                    val rec = withContext(Dispatchers.Default) { c.vault.unlockWithRecovery(key, form.secret.toCharArray(), form.type) }
                    busy = false
                    if (rec == null) err = "Wrong recovery key" else { c.pendingRecovery.value = rec; onDismiss() }
                }
            }) { Text(if (busy) "Working..." else "Recover") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (restore) RestoreDialog(c) { restore = false; onDismiss() }
}
