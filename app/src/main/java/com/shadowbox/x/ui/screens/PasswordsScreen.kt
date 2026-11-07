package com.shadowbox.x.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.shadowbox.x.core.util.ClipboardHelper
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.domain.model.Credential
import com.shadowbox.x.domain.tools.PasswordTools
import com.shadowbox.x.ui.components.*
import com.shadowbox.x.ui.theme.Danger
import com.shadowbox.x.ui.theme.Ok
import kotlinx.coroutines.launch
import java.util.UUID

val credentialCategories = listOf("Social", "Email", "Banking", "Work", "Shopping", "Apps", "Other")

@Composable
fun PasswordsScreen(c: AppContainer, nav: NavController) {
    val creds by remember { c.credentials.observe() }.collectAsState(initial = emptyList())
    val settings by c.settings.settings.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val reauth = rememberReauth()
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    var gen by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(setOf<String>()) }

    fun guarded(title: String, action: () -> Unit) { if (settings.requireBioReveal) reauth(title, action) else action() }

    val cats = listOf("All", "Favorites") + (credentialCategories + creds.map { it.category }).distinct().filter { c0 -> creds.any { it.category == c0 } }
    val list = creds.filter {
        (cat == "All" || (cat == "Favorites" && it.favorite) || it.category == cat) &&
            (query.isBlank() || listOf(it.title, it.url, it.username, it.email, it.category).any { s -> s.contains(query.trim(), true) })
    }.sortedWith(compareByDescending<Credential> { it.favorite }.thenBy { it.title.lowercase() })

    ScreenScaffold("Passwords", snackbar = snack,
        actions = { IconButton(onClick = { gen = true }) { Icon(Icons.Filled.Casino, "Generator") } },
        fab = { ExtendedFloatingActionButton(onClick = { nav.navigate("password/new") }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search credentials") }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(cats) { k -> FilterChip(selected = cat == k, onClick = { cat = k }, label = { Text(k) }) }
                }
            }
            if (list.isEmpty()) item { EmptyState(Icons.Filled.VpnKey, "No credentials", "Add accounts to keep them encrypted. ShadowBox never auto-captures passwords from other apps.") }
            items(list, key = { it.id }) { cr ->
                val shown = cr.id in revealed
                GlassCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("password/${cr.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), Alignment.Center) {
                            Text(cr.title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cr.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cr.username.ifEmpty { cr.email }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (shown) cr.password else "••••••••••", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                        if (cr.favorite) Icon(Icons.Filled.Star, null, tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            ClipboardHelper.copy(ctx, c.appScope, "Username", cr.username.ifEmpty { cr.email }, settings.clipboardClearSeconds)
                            scope.launch { snack.showSnackbar("Username copied") }
                        }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("User") }
                        TextButton(onClick = {
                            guarded("Copy password") {
                                ClipboardHelper.copy(ctx, c.appScope, "Password", cr.password, settings.clipboardClearSeconds)
                                scope.launch { snack.showSnackbar(if (settings.clipboardClearSeconds > 0) "Password copied. Clipboard clears in ${settings.clipboardClearSeconds} s" else "Password copied") }
                            }
                        }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Password") }
                        TextButton(onClick = { if (shown) revealed = revealed - cr.id else guarded("Reveal password") { revealed = revealed + cr.id } }) {
                            Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (shown) "Hide" else "Reveal")
                        }
                    }
                }
            }
        }
    }
    if (gen) GeneratorDialog(onDismiss = { gen = false }, actionLabel = "Copy") {
        ClipboardHelper.copy(ctx, c.appScope, "Generated password", it, settings.clipboardClearSeconds)
        scope.launch { snack.showSnackbar("Copied") }
    }
}

@Composable
fun StrengthBar(pw: String) {
    val s = PasswordTools.strength(pw)
    val col = when (s.score) { 0, 1 -> Danger; 2 -> Color(0xFFFBBF24); else -> Ok }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        LinearProgressIndicator(progress = { (s.score + 1) / 5f }, color = col, modifier = Modifier.fillMaxWidth())
        Text(s.label, color = col, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun GeneratorDialog(onDismiss: () -> Unit, actionLabel: String, onUse: (String) -> Unit) {
    var len by remember { mutableFloatStateOf(20f) }
    var up by remember { mutableStateOf(true) }
    var lo by remember { mutableStateOf(true) }
    var dg by remember { mutableStateOf(true) }
    var sy by remember { mutableStateOf(true) }
    var seed by remember { mutableIntStateOf(0) }
    val pw = remember(len, up, lo, dg, sy, seed) { PasswordTools.generate(len.toInt(), up, lo, dg, sy) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Password generator") },
        text = {
            Column {
                GlassCard(Modifier.fillMaxWidth()) { Text(pw, style = MaterialTheme.typography.titleMedium) }
                StrengthBar(pw)
                Text("Length: ${len.toInt()}", Modifier.padding(top = 8.dp))
                Slider(len, { len = it }, valueRange = 8f..64f)
                listOf("Uppercase" to (up to { v: Boolean -> up = v }), "Lowercase" to (lo to { v: Boolean -> lo = v }),
                    "Digits" to (dg to { v: Boolean -> dg = v }), "Symbols" to (sy to { v: Boolean -> sy = v })).forEach { (n, p) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(n, Modifier.weight(1f)); Switch(p.first, p.second) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(pw); onDismiss() }) { Text(actionLabel) } },
        dismissButton = { Row { TextButton(onClick = { seed++ }) { Text("Regenerate") }; TextButton(onClick = onDismiss) { Text("Cancel") } } }
    )
}

@Composable
fun PasswordEditScreen(c: AppContainer, id: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState()
    val reauth = rememberReauth()
    val isNew = id == "new"
    val credId = remember { if (isNew) UUID.randomUUID().toString() else id }
    var loaded by remember { mutableStateOf(isNew) }
    var original by remember { mutableStateOf<Credential?>(null) }
    var title by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }
    var codes by remember { mutableStateOf("") }; var category by remember { mutableStateOf("Other") }
    var fav by remember { mutableStateOf(false) }
    var gen by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    var pwVisible by remember { mutableStateOf(isNew) }

    LaunchedEffect(id) {
        if (!isNew) c.credentials.get(id)?.let {
            original = it; title = it.title; url = it.url; user = it.username; email = it.email; pw = it.password
            notes = it.notes; codes = it.recoveryCodes; category = it.category; fav = it.favorite
        }
        loaded = true
    }

    ScreenScaffold(if (isNew) "New credential" else "Edit credential", onBack = onBack, actions = {
        IconButton(onClick = { fav = !fav }) { Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite", tint = if (fav) Color(0xFFFBBF24) else MaterialTheme.colorScheme.onBackground) }
        if (!isNew) IconButton(onClick = { del = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }) { pad ->
        if (!loaded) { Loading(Modifier.padding(pad)); return@ScreenScaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Website / app name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("Website URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pw, { pw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { if (pwVisible) pwVisible = false else if (settings.requireBioReveal && !isNew) reauth("Reveal password") { pwVisible = true } else pwVisible = true }) {
                            Icon(if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Reveal")
                        }
                        IconButton(onClick = { gen = true }) { Icon(Icons.Filled.Casino, "Generate") }
                    }
                }
            )
            StrengthBar(pw)
            Text("Category", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(credentialCategories) { k -> FilterChip(category == k, { category = k }, label = { Text(k) }) } }
            OutlinedTextField(category, { category = it }, label = { Text("Custom category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Private account notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(codes, { codes = it }, label = { Text("Recovery codes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Button(enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    c.credentials.save(Credential(credId, title.trim(), url.trim(), user.trim(), email.trim(), pw, notes, codes, category.trim().ifEmpty { "Other" }, fav, 0, 0))
                    onBack()
                }
            }) { Text("Save") }
        }
    }
    if (gen) GeneratorDialog({ gen = false }, "Use") { pw = it; pwVisible = true }
    if (del) ConfirmDialog("Delete credential?", "This cannot be undone.", onDismiss = { del = false }) { scope.launch { c.credentials.delete(credId); onBack() } }
}
