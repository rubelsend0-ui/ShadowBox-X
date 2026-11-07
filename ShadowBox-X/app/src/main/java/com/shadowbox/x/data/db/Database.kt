package com.shadowbox.x.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

// Sensitive fields are stored ONLY inside the encrypted `meta` / `data` columns (AES-256-GCM, AAD bound to row id).
@Entity(tableName = "folders")
data class FolderEntity(@PrimaryKey val id: String, val parentId: String?, val meta: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "files", indices = [Index("folderId")])
data class FileEntity(
    @PrimaryKey val id: String, val folderId: String?, val storedName: String, val wrappedKey: String,
    val size: Long, val blobSize: Long, val blobSha256: String, val meta: String, val createdAt: Long, val updatedAt: Long
)

@Entity(tableName = "credentials")
data class CredentialEntity(@PrimaryKey val id: String, val data: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "notes")
data class NoteEntity(@PrimaryKey val id: String, val data: String, val createdAt: Long, val updatedAt: Long)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders") fun observeAll(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM folders") suspend fun getAll(): List<FolderEntity>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun get(id: String): FolderEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: FolderEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<FolderEntity>)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM folders") suspend fun deleteAll()
}

@Dao
interface FileDao {
    @Query("SELECT * FROM files") fun observeAll(): Flow<List<FileEntity>>
    @Query("SELECT * FROM files") suspend fun getAll(): List<FileEntity>
    @Query("SELECT * FROM files WHERE id = :id") suspend fun get(id: String): FileEntity?
    @Query("SELECT * FROM files WHERE folderId = :folderId") suspend fun inFolder(folderId: String): List<FileEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: FileEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<FileEntity>)
    @Query("DELETE FROM files WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM files") suspend fun deleteAll()
}

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials") fun observeAll(): Flow<List<CredentialEntity>>
    @Query("SELECT * FROM credentials") suspend fun getAll(): List<CredentialEntity>
    @Query("SELECT * FROM credentials WHERE id = :id") suspend fun get(id: String): CredentialEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: CredentialEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<CredentialEntity>)
    @Query("DELETE FROM credentials WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM credentials") suspend fun deleteAll()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes") fun observeAll(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes") suspend fun getAll(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun get(id: String): NoteEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: NoteEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<NoteEntity>)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM notes") suspend fun deleteAll()
}

@Database(
    entities = [FolderEntity::class, FileEntity::class, CredentialEntity::class, NoteEntity::class],
    version = 1, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun fileDao(): FileDao
    abstract fun credentialDao(): CredentialDao
    abstract fun noteDao(): NoteDao

    companion object {
        /** Add real Migration objects here when the schema changes. NEVER use destructive migration (it would wipe the vault). */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
