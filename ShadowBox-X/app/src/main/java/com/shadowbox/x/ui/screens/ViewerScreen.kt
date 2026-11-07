package com.shadowbox.x.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shadowbox.x.core.util.CreateDocWithMime
import com.shadowbox.x.core.util.ImageUtil
import com.shadowbox.x.core.util.formatBytes
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.domain.model.FileKind
import com.shadowbox.x.domain.model.VaultFile
import com.shadowbox.x.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** Decrypts to a temp file in app cache; the file is wiped when this screen is left or the vault locks. */
@Composable
fun ViewerScreen(c: AppContainer, id: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val reauth = rememberReauth()
    var file by remember { mutableStateOf<VaultFile?>(null) }
    var temp by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        try {
            val f = c.files.get(id) ?: throw IllegalStateException("File not found")
            file = f
            if (f.kind == FileKind.TEXT) text = c.files.readText(id)
            else if (f.kind in setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.PDF)) temp = c.files.decryptToTemp(id)
        } catch (e: Exception) { error = e.message ?: "Cannot open file" }
    }
    DisposableEffect(id) { onDispose { c.files.wipeTemp(temp) } }

    val export = rememberLauncherForActivityResult(CreateDocWithMime()) { uri ->
        val f = file
        if (uri != null && f != null) scope.launch {
            try { c.files.exportTo(f.id, uri); snack.showSnackbar("Exported") } catch (e: Exception) { snack.showSnackbar("Export failed: ${e.message}") }
        }
    }

    ScreenScaffold(
        title = file?.name ?: "Viewer", onBack = onBack, snackbar = snack,
        actions = {
            file?.let { f -> IconButton(onClick = { reauth("Export file") { c.vault.externalFlow = true; export.launch(f.name to f.mime) } }) { Icon(Icons.Filled.Upload, "Export") } }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
            val f = file
            val t = temp
            when {
                error != null -> EmptyState(Icons.Filled.Warning, "Cannot open file", error ?: "")
                f == null -> Loading()
                f.kind == FileKind.TEXT -> {
                    val s = text
                    if (s == null) Loading() else SelectionContainer { Text(s, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                f.kind in setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.PDF) && t == null -> Loading()
                f.kind == FileKind.IMAGE && t != null -> ImageViewer(t)
                (f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) && t != null -> MediaPlayerView(t)
                f.kind == FileKind.PDF && t != null -> PdfViewer(t)
                else -> EmptyState(Icons.Filled.InsertDriveFile, f.name, "No built-in preview for this type (${f.mime}, ${formatBytes(f.size)}). Use Export to open it elsewhere.")
            }
        }
    }
}

@Composable
private fun ImageViewer(file: File) {
    var bmp by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(file) {
        bmp = withContext(Dispatchers.IO) { ImageUtil.decode({ FileInputStream(file) }, 2400) }
        failed = bmp == null
    }
    val b = bmp
    when {
        failed -> EmptyState(Icons.Filled.Warning, "Cannot decode image", "The file may be damaged.")
        b == null -> Loading()
        else -> Image(
            b.asImageBitmap(), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
    }
}

@Composable
private fun MediaPlayerView(file: File) {
    val ctx = LocalContext.current
    val player = remember(file) { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(player) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare(); player.playWhenReady = true
        onDispose { player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = 0; controllerHideOnTouch = false } }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun PdfViewer(file: File) {
    val renderer = remember(file) { runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) { EmptyState(Icons.Filled.Warning, "Cannot open PDF", "The file may be damaged or password protected."); return }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(renderer.pageCount) { i ->
            val page by produceState<Bitmap?>(null, i) {
                value = withContext(Dispatchers.IO) {
                    synchronized(renderer) {
                        val p = renderer.openPage(i)
                        try {
                            val h = (widthPx.toFloat() * p.height / p.width).toInt()
                            Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(AColor.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        } finally { p.close() }
                    }
                }
            }
            val pg = page
            if (pg != null) Image(pg.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            else Box(Modifier.fillMaxWidth().height(300.dp), Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
