package com.shadowbox.x.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.shadowbox.x.core.security.BiometricHelper
import com.shadowbox.x.core.util.findActivity
import com.shadowbox.x.domain.model.CredentialType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks for the master secret again (used for sensitive actions when biometrics are unavailable/declined). */
@Composable
fun PasswordPrompt(title: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val numeric = c.vault.credentialType() != CredentialType.PASSWORD
    var v by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                SecretField(v, { v = it; err = null }, "Master ${if (numeric) "PIN" else "password"}", numeric = numeric, error = err != null)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(enabled = v.isNotEmpty() && !busy, onClick = {
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.Default) { c.vault.verify(v.toCharArray()) }
                    busy = false
                    if (ok) onOk() else err = if (c.vault.lockoutRemaining() > 0) "Too many attempts. Try later." else "Wrong secret"
                }
            }) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Returns a function: reauth(title) { action }.
 * Uses biometrics when enabled and available, otherwise the master secret.
 */
@Composable
fun rememberReauth(): (String, () -> Unit) -> Unit {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val settings by c.settings.settings.collectAsState()
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var showPw by remember { mutableStateOf(false) }

    val p = pending
    if (p != null && showPw) {
        PasswordPrompt(p.first, onDismiss = { pending = null; showPw = false }) {
            pending = null; showPw = false; p.second()
        }
    }
    val fn: (String, () -> Unit) -> Unit = { title, action ->
        val bio = BiometricHelper(ctx.findActivity(), c.vault)
        pending = title to action
        if (settings.biometricUnlock && bio.available()) {
            bio.prompt(title, "Confirm it's you", "Use master secret",
                onSuccess = { val a = pending?.second; pending = null; a?.invoke() },
                onFallback = { showPw = true },
                onError = { pending = null })
        } else showPw = true
    }
    return fn
}
