package com.shadowbox.x.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.ui.theme.Midnight
import com.shadowbox.x.ui.theme.Navy

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
fun ShadowBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Midnight, Navy, Midnight)))) {
        Box(
            Modifier.size(340.dp).offset((-90).dp, (-70).dp)
                .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent)), CircleShape)
        )
        content()
    }
}

/** Glassmorphism card: translucent gradient + hairline border. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val base = modifier.clip(shape)
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))))
        .border(1.dp, Color.White.copy(alpha = 0.13f), shape)
    Column((if (onClick != null) base.clickable(onClick = onClick) else base).padding(16.dp), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent, titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground, actionIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/** Standard screen frame. Insets are handled by the shell and the top bar. */
@Composable
fun ScreenScaffold(
    title: String, onBack: (() -> Unit)? = null, snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {}, fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { ShadowTopBar(title, onBack, actions) },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        floatingActionButton = fab, content = content
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Delete", danger: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun InputDialog(title: String, label: String, initial: String = "", confirm: String = "Save", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = { onConfirm(v.trim()); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SecretField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, numeric: Boolean = false, error: Boolean = false) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, isError = error, modifier = modifier,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.NumberPassword else androidx.compose.ui.text.input.KeyboardType.Password
        ),
        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape)); Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}
