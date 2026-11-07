package com.shadowbox.x.di

import android.app.Application
import androidx.room.Room
import com.shadowbox.x.core.security.VaultManager
import com.shadowbox.x.data.db.AppDatabase
import com.shadowbox.x.data.repo.BackupRepository
import com.shadowbox.x.data.repo.CredentialRepository
import com.shadowbox.x.data.repo.FileRepository
import com.shadowbox.x.data.repo.FolderRepository
import com.shadowbox.x.data.repo.NoteRepository
import com.shadowbox.x.data.repo.SettingsRepository
import com.shadowbox.x.data.repo.SyncRepository
import com.shadowbox.x.data.storage.BlobStore
import com.shadowbox.x.work.BackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Manual dependency injection: a single composition root, no reflection, easy to audit. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val blobs = BlobStore(app)
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "shadowbox.db")
        .addMigrations(*AppDatabase.MIGRATIONS).build()
    val vault = VaultManager(app)
    val settings = SettingsRepository(app, appScope)
    val files = FileRepository(app, db.fileDao(), vault, blobs)
    val folders = FolderRepository(db.folderDao(), db.fileDao(), vault, blobs)
    val credentials = CredentialRepository(db.credentialDao(), vault)
    val notes = NoteRepository(db.noteDao(), vault, files)
    val backup = BackupRepository(app, db, vault, blobs, settings)
    val sync = SyncRepository(app, db, vault, blobs)

    /** Recovery key waiting to be shown to the user (setup / recovery / restore). */
    val pendingRecovery = MutableStateFlow<String?>(null)

    fun onAppStart() {
        appScope.launch {
            // wipe decrypted temp files + caches whenever the vault locks (and on cold start)
            blobs.clearTmp()
            vault.unlocked.collect { u ->
                if (!u) { blobs.clearTmp(); files.clearCaches() }
                else { runCatching { files.cleanupOrphans() } }
            }
        }
        appScope.launch { BackupScheduler.apply(app, settings.current().backupDays) }
    }
}
