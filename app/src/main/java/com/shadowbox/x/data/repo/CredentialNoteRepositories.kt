package com.shadowbox.x.data.repo

import com.shadowbox.x.core.security.VaultManager
import com.shadowbox.x.data.db.CredentialDao
import com.shadowbox.x.data.db.CredentialEntity
import com.shadowbox.x.data.db.NoteDao
import com.shadowbox.x.data.db.NoteEntity
import com.shadowbox.x.domain.model.Credential
import com.shadowbox.x.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Whole credential is one AES-GCM blob (title, url, username, password... nothing readable in the DB). */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialRepository(private val dao: CredentialDao, private val vault: VaultManager) {
    private fun aad(id: String) = "cred:$id"

    private fun decode(e: CredentialEntity): Credential? = runCatching {
        val j = JSONObject(vault.openField(e.data, aad(e.id)))
        Credential(
            e.id, j.optString("title"), j.optString("url"), j.optString("username"), j.optString("email"),
            j.optString("password"), j.optString("notes"), j.optString("recovery"), j.optString("category", "Other"),
            j.optBoolean("favorite"), e.createdAt, e.updatedAt
        )
    }.getOrNull()

    fun observe(): Flow<List<Credential>> =
        vault.epoch.flatMapLatest { dao.observeAll() }.map { r -> r.mapNotNull(::decode) }.flowOn(Dispatchers.Default)

    suspend fun get(id: String): Credential? = withContext(Dispatchers.IO) { dao.get(id)?.let(::decode) }

    suspend fun save(c: Credential) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val created = dao.get(c.id)?.createdAt ?: now
        val j = JSONObject().put("title", c.title).put("url", c.url).put("username", c.username).put("email", c.email)
            .put("password", c.password).put("notes", c.notes).put("recovery", c.recoveryCodes)
            .put("category", c.category).put("favorite", c.favorite)
        dao.upsert(CredentialEntity(c.id, vault.sealField(j.toString(), aad(c.id)), created, now))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.delete(id) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NoteRepository(private val dao: NoteDao, private val vault: VaultManager, private val files: FileRepository) {
    private fun aad(id: String) = "note:$id"

    private fun decode(e: NoteEntity): Note? = runCatching {
        val j = JSONObject(vault.openField(e.data, aad(e.id)))
        val att = j.optJSONArray("attachments") ?: JSONArray()
        Note(
            e.id, j.optString("title"), j.optString("body"), j.optString("category", ""), j.optBoolean("pinned"),
            j.optBoolean("locked"), (0 until att.length()).map { att.getString(it) }, e.createdAt, e.updatedAt
        )
    }.getOrNull()

    fun observe(): Flow<List<Note>> =
        vault.epoch.flatMapLatest { dao.observeAll() }.map { r -> r.mapNotNull(::decode) }.flowOn(Dispatchers.Default)

    suspend fun get(id: String): Note? = withContext(Dispatchers.IO) { dao.get(id)?.let(::decode) }

    suspend fun save(n: Note) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val created = dao.get(n.id)?.createdAt ?: now
        val j = JSONObject().put("title", n.title).put("body", n.body).put("category", n.category)
            .put("pinned", n.pinned).put("locked", n.locked).put("attachments", JSONArray(n.attachmentIds))
        dao.upsert(NoteEntity(n.id, vault.sealField(j.toString(), aad(n.id)), created, now))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.get(id)?.let(::decode)?.attachmentIds?.forEach { files.delete(it) }
        dao.delete(id)
    }
}
