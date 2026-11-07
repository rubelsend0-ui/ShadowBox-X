package com.shadowbox.x.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.shadowbox.x.core.util.formatBytes
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.domain.model.FileKind
import com.shadowbox.x.domain.model.Folder
import com.shadowbox.x.domain.model.VaultFile
import com.shadowbox.x.ui.components.*

@Composable
fun GalleryScreen(c: AppContainer, nav: NavController) {
    val files by remember { c.files.observe() }.collectAsState(initial = emptyList())
    val folders by remember { c.folders.observe() }.collectAsState(initial = emptyList<Folder>())
    var grid by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    val locked = lockedFolderIds(folders, c.vault.unlockedFolders)
    val hidden = folders.filter { it.hidePreviews }.map { it.id }.toSet()

    val media = files.filter { (it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO) && (it.folderId == null || it.folderId !in locked) }
        .filter {
            when (filter) {
                "Photos" -> it.kind == FileKind.IMAGE; "Videos" -> it.kind == FileKind.VIDEO
                "Audio" -> it.kind == FileKind.AUDIO; "Favorites" -> it.favorite; else -> true
            }
        }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.createdAt }

    ScreenScaffold("Shadow Gallery", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, "Toggle view") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search media") }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Photos", "Videos", "Audio", "Favorites")) { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
            }
            if (media.isEmpty()) EmptyState(Icons.Filled.PhotoLibrary, "No media found", "Import photos, videos or audio in the Vault and they appear here.")
            else if (grid) LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(media, key = { it.id }) { f ->
                    Box(Modifier.aspectRatio(1f).clickable { nav.navigate("viewer/${f.id}") }) {
                        FileThumb(c, f, f.folderId in hidden, 400.dp, Modifier.fillMaxSize())
                        if (f.kind == FileKind.VIDEO) Icon(Icons.Filled.PlayCircle, null, Modifier.align(Alignment.Center), tint = Color.White.copy(alpha = 0.9f))
                        if (f.favorite) Icon(Icons.Filled.Star, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Color(0xFFFBBF24))
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(media, key = { it.id }) { f ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("viewer/${f.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FileThumb(c, f, f.folderId in hidden, 56.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text("${f.mime} · ${formatBytes(f.size)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (f.favorite) Icon(Icons.Filled.Star, null, tint = Color(0xFFFBBF24))
                        }
                    }
                }
            }
        }
    }
}
