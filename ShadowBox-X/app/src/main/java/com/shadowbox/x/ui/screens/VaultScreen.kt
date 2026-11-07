package com.shadowbox.x.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.shadowbox.x.core.security.BiometricHelper
import com.shadowbox.x.core.util.CreateDocWithMime
import com.shadowbox.x.core.util.findActivity
import com.shadowbox.x.core.util.formatBytes
import com.shadowbox.x.core.util.formatDate
import com.shadowbox.x.data.storage.LowStorageException
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.domain.model.FileKind
import com.shadowbox.x.domain.model.Folder
import com.shadowbox.x.domain.model.VaultFile
import com.shadowbox.x.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortBy { NAME, DATE, SIZE }

val folderIcons: Map<String, ImageVector> = mapOf(
    "folder" to Icons.Filled.Folder, "photo" to Icons.Filled.Photo, "video" to Icons.Filled.Videocam,
    "doc" to Icons.Filled.Description, "star" to Icons.Filled.Star, "lock" to Icons.Filled.Lock,
    "key" to Icons.Filled.VpnKey, "home" to Icons.Filled.Home
)
fun iconFor(name: String): ImageVector = folderIcons[name] ?: Icons.Filled.Folder

fun kindIcon(k: FileKind): ImageVector = when (k) {
    FileKind.IMAGE -> Icons.Filled.Image; FileKind.VIDEO -> Icons.Filled.Videocam; FileKind.AUDIO -> Icons.Filled.MusicNote
    FileKind.PDF -> Icons.Filled.PictureAsPdf; FileKind.TEXT, FileKind.DOCUMENT -> Icons.Filled.Description
    FileKind.ARCHIVE -> Icons.Filled.FolderZip; FileKind.OTHER -> Icons.Filled.InsertDriveFile
}

private val palette = listOf(0xFF2F80FF, 0xFF22D3EE, 0xFF8B7CFF, 0xFF34D399, 0xFFFBBF24, 0xFFFF5C6C, 0xFFF472B6, 0xFF94A3B8).map { it.toInt() }

fun descendants(id: String, folders: List<Folder>): Set<String> {
    val out = mutableSetOf(id)
    var changed = true
    while (changed) { changed = false; folders.forEach { if (it.parentId in out && out.add(it.id)) changed = true } }
    return out
}

/** Folders the user cannot currently see into (protected and not yet unlocked this session, or inside such a folder). */
fun lockedFolderIds(folders: List<Folder>, unlocked: Set<String>): Set<String> {
    val byId = folders.associateBy { it.id }
    fun locked(id: String?): Boolean {
        var cur = id
        while (cur != null) {
            val f = byId[cur] ?: return false
            if (f.isProtected && f.id !in unlocked) return true
            cur = f.parentId
        }
        return false
    }
    return folders.filter { locked(it.id) }.map { it.id }.toSet()
}

class VaultViewModel(private val c: AppContainer) : ViewModel() {
    val folders = c.folders.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val files = c.files.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    data class ImportState(val index: Int, val total: Int, val done: Long, val size: Long)
    val importState = MutableStateFlow<ImportState?>(null)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var job: Job? = null

    fun import(uris: List<Uri>, folderId: String?) {
        if (uris.isEmpty()) return
        job = viewModelScope.launch {
            var ok = 0
            var firstError: String? = null
            try {
                for ((i, u) in uris.withIndex()) {
                    importState.value = ImportState(i + 1, uris.size, 0, 0)
                    try {
                        c.files.import(u, folderId) { d, s -> importState.value = ImportState(i + 1, uris.size, d, s) }
                        ok++
                    } catch (e: CancellationException) { throw e }
                    catch (e: LowStorageException) { firstError = e.message; break }
                    catch (e: Exception) { if (firstError == null) firstError = e.message ?: "Import failed" }
                }
            } finally { importState.value = null }
            events.emit(if (firstError == null) "Encrypted $ok file(s)" else "Imported $ok of ${uris.size}. $firstError")
        }
    }

    fun cancelImport() { job?.cancel() }
    fun rename(id: String, n: String) = viewModelScope.launch { c.files.rename(id, n) }
    fun move(id: String, folder: String?) = viewModelScope.launch { c.files.move(id, folder) }
    fun favorite(f: VaultFile) = viewModelScope.launch { c.files.setFavorite(f.id, !f.favorite) }
    fun delete(id: String) = viewModelScope.launch { c.files.delete(id); events.emit("File deleted") }
    fun deleteFolder(id: String) = viewModelScope.launch { c.folders.delete(id); events.emit("Folder deleted") }
    fun moveFolder(id: String, parent: String?) = viewModelScope.launch { if (!c.folders.move(id, parent)) events.emit("Cannot move a folder into itself") }
    fun export(id: String, dest: Uri) = viewModelScope.launch {
        try { c.files.exportTo(id, dest); events.emit("Exported") } catch (e: Exception) { events.emit("Export failed: ${e.message}") }
    }
    fun saveFolder(editing: Folder?, parent: String?, name: String, color: Int, icon: String, hide: Boolean, pin: String, removePin: Boolean, bio: Boolean) =
        viewModelScope.launch {
            if (editing == null) c.folders.create(name, parent, color, icon, hide, pin.toCharArray().takeIf { it.isNotEmpty() }, bio)
            else c.folders.update(editing.id, name, color, icon, hide, pin.toCharArray().takeIf { it.isNotEmpty() }, removePin, bio)
        }
    suspend fun verifyPin(id: String, pin: String) = c.folders.verifyPin(id, pin.toCharArray())
}

@Composable
fun FileThumb(c: AppContainer, f: VaultFile, hide: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f.id, hide) {
        value = if (f.hasThumb && !hide) c.files.thumbnail(f.id)?.asImageBitmap() else null
    }
    Box(modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)), Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(kindIcon(f.kind), null, tint = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
fun VaultScreen(c: AppContainer, folderId: String?, foldersOnly: Boolean, nav: NavController) {
    val vm: VaultViewModel = viewModel(
        key = "vault:${folderId ?: if (foldersOnly) "folders" else "root"}",
        factory = viewModelFactory { initializer { VaultViewModel(c) } }
    )
    val folders by vm.folders.collectAsState()
    val files by vm.files.collectAsState()
    val imp by vm.importState.collectAsState()
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val reauth = rememberReauth()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(SortBy.DATE) }
    var asc by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var unlockTick by remember { mutableIntStateOf(0) }
    var folderDialog by remember { mutableStateOf<Pair<Boolean, Folder?>?>(null) } // (visible, editing)
    var unlockFolder by remember { mutableStateOf<Folder?>(null) }
    var moveFile by remember { mutableStateOf<VaultFile?>(null) }
    var moveFolder by remember { mutableStateOf<Folder?>(null) }
    var renameFile by remember { mutableStateOf<VaultFile?>(null) }
    var details by remember { mutableStateOf<VaultFile?>(null) }
    var delFile by remember { mutableStateOf<VaultFile?>(null) }
    var delFolder by remember { mutableStateOf<Folder?>(null) }
    var pendingExport by remember { mutableStateOf<VaultFile?>(null) }

    LaunchedEffect(Unit) { vm.events.collect { snack.showSnackbar(it) } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it, folderId) }
    val exportLauncher = rememberLauncherForActivityResult(CreateDocWithMime()) { uri ->
        val f = pendingExport; pendingExport = null
        if (uri != null && f != null) vm.export(f.id, uri)
    }

    val current = folders.firstOrNull { it.id == folderId }
    val lockedIds = remember(folders, unlockTick) { lockedFolderIds(folders, c.vault.unlockedFolders) }
    val q = query.trim().lowercase()

    val subFolders = (if (q.isEmpty()) folders.filter { it.parentId == folderId } else folders.filter { it.name.lowercase().contains(q) && it.id !in lockedIds })
        .sortedBy { it.name.lowercase() }
    val subFiles = run {
        if (foldersOnly) emptyList() else {
            val base = if (q.isEmpty()) files.filter { it.folderId == folderId }
            else files.filter { it.name.lowercase().contains(q) && (it.folderId == null || it.folderId !in lockedIds) }
            val cmp: Comparator<VaultFile> = when (sort) {
                SortBy.NAME -> compareBy { it.name.lowercase() }; SortBy.DATE -> compareBy { it.createdAt }; SortBy.SIZE -> compareBy { it.size }
            }
            base.sortedWith(if (asc) cmp else cmp.reversed())
        }
    }
    fun stats(id: String): Pair<Int, Long> {
        val ids = descendants(id, folders)
        val fs = files.filter { it.folderId in ids }
        return fs.size to fs.sumOf { it.blobSize }
    }
    fun hide(f: VaultFile) = folders.firstOrNull { it.id == f.folderId }?.hidePreviews == true

    fun openFolder(f: Folder) {
        if (f.isProtected && f.id !in c.vault.unlockedFolders) unlockFolder = f else nav.navigate("folder/${f.id}")
    }

    ScreenScaffold(
        title = if (foldersOnly) "Secret Folders" else current?.name ?: "Secret Files",
        onBack = if (folderId != null || foldersOnly) ({ nav.popBackStack() }) else null,
        snackbar = snack,
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "Search") }
            if (!foldersOnly) Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SortBy.values().forEach { s ->
                        DropdownMenuItem(text = { Text("By ${s.name.lowercase()}" + if (sort == s) (if (asc) "  ↑" else "  ↓") else "") }, onClick = {
                            if (sort == s) asc = !asc else { sort = s; asc = s == SortBy.NAME }; sortMenu = false
                        })
                    }
                }
            }
        },
        fab = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallFloatingActionButton(onClick = { folderDialog = true to null }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                if (!foldersOnly) ExtendedFloatingActionButton(
                    onClick = { c.vault.externalFlow = true; importLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Import") }
                )
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 140.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (searching) item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search files and folders") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Search, null) })
            }
            if (current != null && folders.isNotEmpty()) item {
                val (n, b) = stats(current.id)
                Text("$n files · ${formatBytes(b)} encrypted", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            if (subFolders.isEmpty() && subFiles.isEmpty()) item {
                EmptyState(if (q.isNotEmpty()) Icons.Filled.SearchOff else Icons.Filled.Lock,
                    if (q.isNotEmpty()) "No matches" else if (foldersOnly) "No secret folders yet" else "Nothing here yet",
                    if (q.isNotEmpty()) "Try another search." else if (foldersOnly) "Tap the folder button to create one." else "Tap Import to encrypt photos, videos, documents and more.")
            }
            items(subFolders, key = { "d" + it.id }) { f ->
                val (n, b) = stats(f.id)
                FolderRow(f, n, b, locked = f.id in lockedIds || (f.isProtected && f.id !in c.vault.unlockedFolders),
                    onOpen = { openFolder(f) }, onEdit = { folderDialog = true to f }, onMove = { moveFolder = f }, onDelete = { delFolder = f })
            }
            items(subFiles, key = { "f" + it.id }) { f ->
                FileRow(c, f, hide(f), onOpen = { nav.navigate("viewer/${f.id}") }, onFav = { vm.favorite(f) }, onRename = { renameFile = f },
                    onMove = { moveFile = f }, onExport = {
                        reauth("Export file") { pendingExport = f; c.vault.externalFlow = true; exportLauncher.launch(f.name to f.mime) }
                    }, onDetails = { details = f }, onDelete = { delFile = f })
            }
        }
    }

    imp?.let { s ->
        AlertDialog(onDismissRequest = {}, title = { Text("Encrypting files") },
            text = {
                Column {
                    Text("File ${s.index} of ${s.total}")
                    Spacer(Modifier.height(12.dp))
                    if (s.size > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${formatBytes(s.done)} / ${if (s.size > 0) formatBytes(s.size) else "?"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelImport() }) { Text("Cancel") } })
    }

    folderDialog?.let { (_, editing) ->
        FolderDialog(editing, onDismiss = { folderDialog = null }) { name, color, icon, hide, pin, rm, bio ->
            vm.saveFolder(editing, folderId, name, color, icon, hide, pin, rm, bio)
        }
    }
    unlockFolder?.let { f ->
        FolderUnlockDialog(vm, f, onDismiss = { unlockFolder = null }) {
            c.vault.unlockedFolders.add(f.id); unlockTick++; unlockFolder = null; nav.navigate("folder/${f.id}")
        }
    }
    moveFile?.let { f -> MoveDialog(folders, null, onDismiss = { moveFile = null }) { vm.move(f.id, it) } }
    moveFolder?.let { f -> MoveDialog(folders, f.id, onDismiss = { moveFolder = null }) { vm.moveFolder(f.id, it) } }
    renameFile?.let { f -> InputDialog("Rename file", "Name", f.name, onDismiss = { renameFile = null }) { vm.rename(f.id, it) } }
    details?.let { f -> DetailsDialog(f, folders) { details = null } }
    delFile?.let { f -> ConfirmDialog("Delete file?", "\"${f.name}\" will be permanently erased from the vault.", onDismiss = { delFile = null }) { vm.delete(f.id) } }
    delFolder?.let { f -> ConfirmDialog("Delete folder?", "\"${f.name}\" and everything inside it will be permanently erased.", onDismiss = { delFolder = null }) { vm.deleteFolder(f.id) } }
}

@Composable
private fun FolderRow(f: Folder, files: Int, bytes: Long, locked: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(f.color).copy(alpha = 0.22f)), Alignment.Center) {
                Icon(iconFor(f.icon), null, tint = Color(f.color))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (locked) "Locked folder" else "$files files · ${formatBytes(bytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.isProtected) Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Filled.MoveToInbox, null) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    c: AppContainer, f: VaultFile, hide: Boolean, onOpen: () -> Unit, onFav: () -> Unit, onRename: () -> Unit,
    onMove: () -> Unit, onExport: () -> Unit, onDetails: () -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileThumb(c, f, hide, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatBytes(f.size)} · ${formatDate(f.createdAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (f.favorite) Icon(Icons.Filled.Star, null, tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (f.favorite) "Unfavorite" else "Favorite") }, leadingIcon = { Icon(Icons.Filled.Star, null) }, onClick = { menu = false; onFav() })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Filled.MoveToInbox, null) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Filled.Upload, null) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menu = false; onDetails() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun DetailsDialog(f: VaultFile, folders: List<Folder>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(f.name) }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "Type" to f.mime, "Original size" to formatBytes(f.size), "Encrypted size" to formatBytes(f.blobSize),
                "Folder" to (folders.firstOrNull { it.id == f.folderId }?.name ?: "Vault root"),
                "Added" to formatDate(f.createdAt), "Modified" to formatDate(f.updatedAt),
                "Encryption" to "AES-256-GCM, unique per-file key"
            ).forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
        }
    })
}

@Composable
private fun MoveDialog(folders: List<Folder>, excludeId: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val banned = if (excludeId != null) descendants(excludeId, folders) else emptySet()
    fun flatten(parent: String?, depth: Int): List<Pair<Folder, Int>> =
        folders.filter { it.parentId == parent && it.id !in banned }.sortedBy { it.name.lowercase() }.flatMap { listOf(it to depth) + flatten(it.id, depth + 1) }
    val list = flatten(null, 0)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Move to") }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { ListItem(headlineContent = { Text("Vault root") }, leadingContent = { Icon(Icons.Filled.Home, null) }, modifier = Modifier.clickable { onPick(null); onDismiss() }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
            items(list) { (f, d) ->
                ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(iconFor(f.icon), null, tint = Color(f.color)) },
                    modifier = Modifier.padding(start = (d * 16).dp).clickable { onPick(f.id); onDismiss() }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    })
}

@Composable
private fun FolderDialog(editing: Folder?, onDismiss: () -> Unit, onSave: (String, Int, String, Boolean, String, Boolean, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val bioOk = remember { BiometricHelper(ctx.findActivity()).available() }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var color by remember { mutableIntStateOf(editing?.color ?: palette[0]) }
    var icon by remember { mutableStateOf(editing?.icon ?: "folder") }
    var hide by remember { mutableStateOf(editing?.hidePreviews ?: false) }
    var bio by remember { mutableStateOf(editing?.biometric ?: false) }
    var pin by remember { mutableStateOf("") }
    var removePin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (editing == null) "New secret folder" else "Edit folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (editing == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("Personal" to "lock", "Private Photos" to "photo", "Documents" to "doc", "Videos" to "video", "Important Files" to "star")) { (n, i) ->
                        AssistChip(onClick = { name = n; icon = i }, label = { Text(n) })
                    }
                }
                Text("Color", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(palette) { col ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(col)).clickable { color = col }, Alignment.Center) { if (color == col) Icon(Icons.Filled.Check, null, tint = Color.White) }
                    }
                }
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folderIcons.keys.toList()) { k ->
                        FilterChip(selected = icon == k, onClick = { icon = k }, label = { Icon(iconFor(k), null, Modifier.size(18.dp)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Hide file previews", Modifier.weight(1f)); Switch(hide, { hide = it }) }
                if (bioOk) Row(verticalAlignment = Alignment.CenterVertically) { Text("Require biometrics to open", Modifier.weight(1f)); Switch(bio, { bio = it }) }
                SecretField(pin, { pin = it.filter(Char::isDigit); removePin = false }, if (editing?.hasPin == true) "New folder PIN (blank = keep)" else "Folder PIN (optional)", Modifier.fillMaxWidth(), numeric = true)
                if (editing?.hasPin == true) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { removePin = !removePin; if (removePin) pin = "" }) {
                    Checkbox(removePin, { removePin = it; if (it) pin = "" }); Text("Remove folder PIN")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && (pin.isEmpty() || pin.length >= 4), onClick = { onSave(name, color, icon, hide, pin, removePin, bio); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FolderUnlockDialog(vm: VaultViewModel, f: Folder, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    val bio = remember { BiometricHelper(ctx.findActivity(), c.vault) }
    fun promptBio() = bio.prompt("Open ${f.name}", "Confirm it's you", "Cancel", onSuccess = onUnlocked, onFallback = {}, onError = {})
    LaunchedEffect(Unit) { if (f.biometric && !f.hasPin && bio.available()) promptBio() }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Unlock ${f.name}") },
        text = {
            Column {
                if (f.hasPin) {
                    SecretField(pin, { pin = it.filter(Char::isDigit); err = false }, "Folder PIN", numeric = true, error = err)
                    if (err) Text("Wrong PIN", color = MaterialTheme.colorScheme.error)
                }
                if (f.biometric && bio.available()) TextButton(onClick = { promptBio() }) { Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Use biometrics") }
            }
        },
        confirmButton = {
            if (f.hasPin) TextButton(enabled = pin.length >= 4, onClick = { scope.launch { if (vm.verifyPin(f.id, pin)) onUnlocked() else { err = true; pin = "" } } }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
