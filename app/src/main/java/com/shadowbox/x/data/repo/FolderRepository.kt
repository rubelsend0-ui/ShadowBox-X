package com.shadowbox.x.data.repo

import com.shadowbox.x.core.crypto.Crypto
import com.shadowbox.x.core.security.VaultManager
import com.shadowbox.x.data.db.FileDao
import com.shadowbox.x.data.db.FolderDao
import com.shadowbox.x.data.db.FolderEntity
import com.shadowbox.x.data.storage.BlobStore
import com.shadowbox.x.domain.model.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FolderRepository(
    private val dao: FolderDao,
    private val fileDao: FileDao,
    private val vault: VaultManager,
    private val blobs: BlobStore
) {
    private fun aad(id: String) = "folder:$id"
    private val pinIter = 100_000

    private fun decode(e: FolderEntity): Folder? = runCatching {
        val j = JSONObject(vault.openField(e.meta, aad(e.id)))
        Folder(
            e.id, e.parentId, j.getString("name"), j.optInt("color", 0xFF2F80FF.toInt()), j.optString("icon", "folder"),
            j.optBoolean("hidePreviews"), j.optString("pinHash").isNotEmpty(), j.optBoolean("biometric"),
            e.createdAt, e.updatedAt
        )
    }.getOrNull()

    fun observe(): Flow<List<Folder>> =
        vault.epoch.flatMapLatest { dao.observeAll() }.map { rows -> rows.mapNotNull(::decode) }.flowOn(Dispatchers.Default)

    suspend fun create(
        name: String, parentId: String?, color: Int, icon: String,
        hidePreviews: Boolean, pin: CharArray?, biometric: Boolean
    ): Folder = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val j = JSONObject().put("name", name.trim()).put("color", color).put("icon", icon)
            .put("hidePreviews", hidePreviews).put("biometric", biometric)
        if (pin != null && pin.isNotEmpty()) setPin(j, pin)
        val e = FolderEntity(id, parentId, vault.sealField(j.toString(), aad(id)), now, now)
        dao.upsert(e)
        decode(e)!!
    }

    private fun setPin(j: JSONObject, pin: CharArray) {
        val salt = Crypto.random(16)
        j.put("pinSalt", Crypto.b64(salt)).put("pinHash", Crypto.b64(Crypto.kdf(pin, salt, pinIter)))
    }

    suspend fun update(
        id: String, name: String, color: Int, icon: String, hidePreviews: Boolean,
        newPin: CharArray?, removePin: Boolean, biometric: Boolean
    ) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext
        val j = JSONObject(vault.openField(e.meta, aad(id)))
        j.put("name", name.trim()).put("color", color).put("icon", icon).put("hidePreviews", hidePreviews).put("biometric", biometric)
        if (removePin) { j.remove("pinHash"); j.remove("pinSalt") }
        if (newPin != null && newPin.isNotEmpty()) setPin(j, newPin)
        dao.upsert(e.copy(meta = vault.sealField(j.toString(), aad(id)), updatedAt = System.currentTimeMillis()))
    }

    suspend fun verifyPin(id: String, pin: CharArray): Boolean = withContext(Dispatchers.Default) {
        val e = dao.get(id) ?: return@withContext false
        val j = JSONObject(vault.openField(e.meta, aad(id)))
        val hash = j.optString("pinHash")
        if (hash.isEmpty()) return@withContext true
        val calc = Crypto.kdf(pin, Crypto.unb64(j.getString("pinSalt")), pinIter)
        MessageDigest.isEqual(calc, Crypto.unb64(hash))
    }

    /** Returns false if target is the folder itself or one of its descendants. */
    suspend fun move(id: String, newParent: String?): Boolean = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        var cur = newParent
        while (cur != null) {
            if (cur == id) return@withContext false
            cur = all.firstOrNull { it.id == cur }?.parentId
        }
        val e = all.firstOrNull { it.id == id } ?: return@withContext false
        dao.upsert(e.copy(parentId = newParent, updatedAt = System.currentTimeMillis()))
        true
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        val ids = mutableListOf(id)
        var i = 0
        while (i < ids.size) { val p = ids[i++]; ids += all.filter { it.parentId == p }.map { it.id } }
        for (fid in ids) {
            fileDao.inFolder(fid).forEach { f ->
                blobs.blob(f.storedName).delete(); blobs.thumb(f.id).delete(); fileDao.delete(f.id)
            }
            dao.delete(fid)
        }
    }
}
