package com.shadowbox.x.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.shadowbox.x.core.util.formatDate
import com.shadowbox.x.data.repo.FileRepository
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.domain.model.Note
import com.shadowbox.x.domain.model.VaultFile
import com.shadowbox.x.ui.components.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun NotesScreen(c: AppContainer, nav: NavController) {
    val notes by remember { c.notes.observe() }.collectAsState(initial = emptyList())
    val reauth = rememberReauth()
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    val cats = listOf("All") + notes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val list = notes.filter {
        (cat == "All" || it.category == cat) &&
            (query.isBlank() || it.title.contains(query.trim(), true) || (!it.locked && it.body.contains(query.trim(), true)))
    }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })

    ScreenScaffold("Private Notes", fab = {
        ExtendedFloatingActionButton(onClick = { nav.navigate("note/new") }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New note") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search notes") }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) { items(cats) { k -> FilterChip(cat == k, { cat = k }, label = { Text(k) }) } }
            }
            if (list.isEmpty()) item { EmptyState(Icons.Filled.StickyNote2, "No notes", "Notes are encrypted and autosaved as you type.") }
            items(list, key = { it.id }) { n ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { if (n.locked) reauth("Open locked note") { nav.navigate("note/${n.id}") } else nav.navigate("note/${n.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (n.locked) "Locked note" else n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(n.category.ifBlank { null }, formatDate(n.updatedAt)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (n.pinned) Icon(Icons.Filled.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        if (n.locked) Icon(Icons.Filled.Lock, null, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                        if (n.attachmentIds.isNotEmpty()) Icon(Icons.Filled.AttachFile, null, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                    }
                }
            }
        }
    }
}

/** Debounced encrypted autosave. Blank new notes are never written. */
class NoteEditViewModel(private val c: AppContainer, private val noteId: String, private val isNew: Boolean) : ViewModel() {
    val note = MutableStateFlow(Note(noteId, "", "", "", false, false, emptyList(), 0, 0))
    val loaded = MutableStateFlow(isNew)
    val saved = MutableStateFlow(false)
    val attachments = MutableStateFlow<List<VaultFile>>(emptyList())
    private var job: Job? = null
    private var dirty = false

    init {
        viewModelScope.launch {
            if (!isNew) c.notes.get(noteId)?.let { note.value = it }
            loaded.value = true
            refreshAttachments()
        }
    }

    private suspend fun refreshAttachments() { attachments.value = note.value.attachmentIds.mapNotNull { c.files.get(it) } }

    fun change(n: Note) {
        note.value = n; dirty = true; saved.value = false
        job?.cancel()
        job = viewModelScope.launch { delay(700); persist(); }
    }

    private suspend fun persist() {
        val n = note.value
        if (n.title.isBlank() && n.body.isBlank() && n.attachmentIds.isEmpty()) return
        c.notes.save(n); dirty = false; saved.value = true
    }

    fun flush() { if (dirty) c.appScope.launch { persist() } }

    fun attach(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val f = c.files.import(uri, FileRepository.ATTACH_FOLDER)
            change(note.value.copy(attachmentIds = note.value.attachmentIds + f.id)); refreshAttachments()
        } catch (e: Exception) { error.value = e.message }
    }
    val error = MutableStateFlow<String?>(null)

    fun removeAttachment(id: String) = viewModelScope.launch {
        c.files.delete(id); change(note.value.copy(attachmentIds = note.value.attachmentIds - id)); refreshAttachments()
    }

    fun delete(done: () -> Unit) = viewModelScope.launch { job?.cancel(); dirty = false; c.notes.delete(noteId); done() }
}

@Composable
fun NoteEditScreen(c: AppContainer, id: String, nav: NavController) {
    val isNew = id == "new"
    val noteId = remember { if (isNew) UUID.randomUUID().toString() else id }
    val vm: NoteEditViewModel = viewModel(key = "note:$noteId", factory = viewModelFactory { initializer { NoteEditViewModel(c, noteId, isNew) } })
    val note by vm.note.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val saved by vm.saved.collectAsState()
    val atts by vm.attachments.collectAsState()
    val error by vm.error.collectAsState()
    var del by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attach) }
    DisposableEffect(Unit) { onDispose { vm.flush() } }

    ScreenScaffold("Note", onBack = { vm.flush(); nav.popBackStack() }, actions = {
        Text(if (saved) "Saved" else "", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 4.dp))
        IconButton(onClick = { vm.change(note.copy(pinned = !note.pinned)) }) { Icon(Icons.Filled.PushPin, "Pin", tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground) }
        IconButton(onClick = { vm.change(note.copy(locked = !note.locked)) }) { Icon(if (note.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, "Lock note") }
        if (!isNew) IconButton(onClick = { del = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }) { pad ->
        if (!loaded) { Loading(Modifier.padding(pad)); return@ScreenScaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(note.title, { vm.change(note.copy(title = it)) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note.category, { vm.change(note.copy(category = it)) }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note.body, { vm.change(note.copy(body = it)) }, label = { Text("Write something private...") }, minLines = 10, modifier = Modifier.fillMaxWidth())
            Text("Attachments", style = MaterialTheme.typography.labelLarge)
            atts.forEach { f ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("viewer/${f.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(f.kind), null); Spacer(Modifier.width(10.dp)); Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.removeAttachment(f.id) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
            }
            OutlinedButton(onClick = { c.vault.externalFlow = true; picker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.AttachFile, null); Spacer(Modifier.width(8.dp)); Text("Attach file") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (del) ConfirmDialog("Delete note?", "The note and its attachments will be erased.", onDismiss = { del = false }) { vm.delete { nav.popBackStack() } }
}
