package com.shadowbox.x.data.repo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.shadowbox.x.core.crypto.BlobCrypto
import com.shadowbox.x.core.crypto.Crypto
import com.shadowbox.x.core.security.KeystoreHelper
import com.shadowbox.x.core.security.VaultManager
import com.shadowbox.x.data.db.AppDatabase
import com.shadowbox.x.data.db.CredentialEntity
import com.shadowbox.x.data.db.FileEntity
import com.shadowbox.x.data.db.FolderEntity
import com.shadowbox.x.data.db.NoteEntity
import com.shadowbox.x.data.storage.BlobStore
import com.shadowbox.x.data.storage.VaultException
import com.shadowbox.x.domain.model.CredentialType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup container (.sbxbak):
 *   "SBB1" | salt(16) | kdfIter(4) | AES-256-GCM chunk stream( ZIP{ manifest.json, db.json, blobs/*, thumbs/* } )
 * The key is PBKDF2(backup password). manifest.json carries the VMK, so a backup restores on any device.
 * Per-chunk GCM tags + final-chunk flag give integrity for the entire file.
 */
class BackupRepository(
    private val ctx: Context,
    private val db: AppDatabase,
    private val vault: VaultManager,
    private val blobs: BlobStore,
    private val settings: SettingsRepository
) {
    data class Progress(val phase: String, val done: Long, val total: Long)
    data class Info(val createdAt: Long, val folders: Int, val files: Int, val credentials: Int, val notes: Int)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private fun jFolders(l: List<FolderEntity>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("parentId", it.parentId ?: JSONObject.NULL).put("meta", it.meta).put("c", it.createdAt).put("u", it.updatedAt)) } }
    private fun jFiles(l: List<FileEntity>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("folderId", it.folderId ?: JSONObject.NULL).put("stored", it.storedName).put("wk", it.wrappedKey).put("size", it.size).put("blobSize", it.blobSize).put("sha", it.blobSha256).put("meta", it.meta).put("c", it.createdAt).put("u", it.updatedAt)) } }
    private fun jCreds(l: List<CredentialEntity>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("data", it.data).put("c", it.createdAt).put("u", it.updatedAt)) } }
    private fun jNotes(l: List<NoteEntity>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("data", it.data).put("c", it.createdAt).put("u", it.updatedAt)) } }

    suspend fun create(out: OutputStream, password: CharArray) = withContext(Dispatchers.IO) {
        val vmk = vault.copyKey()
        val folders = db.folderDao().getAll()
        val files = db.fileDao().getAll()
        val creds = db.credentialDao().getAll()
        val notes = db.noteDao().getAll()
        val total = files.sumOf { it.blobSize }
        var done = 0L
        val salt = Crypto.random(16)
        val key = Crypto.kdf(password, salt)
        val raw = BufferedOutputStream(out, 64 * 1024)
        raw.write("SBB1".toByteArray(Charsets.US_ASCII)); raw.write(salt)
        raw.write(ByteBuffer.allocate(4).putInt(Crypto.KDF_ITER).array())
        val zip = ZipOutputStream(BlobCrypto.encrypt(raw, key, "SBP1"))
        zip.setLevel(Deflater.NO_COMPRESSION)
        try {
            _progress.value = Progress("Preparing", 0, total)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("version", 1).put("createdAt", System.currentTimeMillis()).put("vmk", Crypto.b64(vmk)).toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("db.json"))
            zip.write(JSONObject().put("folders", jFolders(folders)).put("files", jFiles(files))
                .put("credentials", jCreds(creds)).put("notes", jNotes(notes)).toString().toByteArray())
            zip.closeEntry()
            val buf = ByteArray(64 * 1024)
            for (f in files) {
                val b = blobs.blob(f.storedName)
                if (!b.exists()) continue
                zip.putNextEntry(ZipEntry("blobs/${f.storedName}.sbx"))
                FileInputStream(b).use { i ->
                    while (true) { val n = i.read(buf); if (n < 0) break; zip.write(buf, 0, n); done += n; _progress.value = Progress("Encrypting backup", done, total) }
                }
                zip.closeEntry()
                val t = blobs.thumb(f.id)
                if (t.exists()) { zip.putNextEntry(ZipEntry("thumbs/${f.id}.thm")); zip.write(t.readBytes()); zip.closeEntry() }
            }
            zip.close()
        } finally {
            key.fill(0); vmk.fill(0); _progress.value = null
        }
    }

    private class Opened(val zip: ZipInputStream, val raw: InputStream)

    private fun open(input: InputStream, password: CharArray): Opened {
        val bis = BufferedInputStream(input, 64 * 1024)
        val head = ByteArray(24)
        var r = 0
        while (r < head.size) { val x = bis.read(head, r, head.size - r); if (x < 0) throw VaultException("Not a ShadowBox backup"); r += x }
        if (String(head, 0, 4, Charsets.US_ASCII) != "SBB1") throw VaultException("Not a ShadowBox backup")
        val iter = ByteBuffer.wrap(head, 20, 4).int
        if (iter !in 100_000..5_000_000) throw VaultException("Corrupt backup header")
        val key = Crypto.kdf(password, head.copyOfRange(4, 20), iter)
        val dec = BlobCrypto.decrypt(bis, key, "SBP1")
        key.fill(0)
        return Opened(ZipInputStream(dec), dec)
    }

    private fun safeName(n: String) = Regex("^(blobs|thumbs)/[A-Za-z0-9._-]+$").matches(n) && !n.contains("..")

    private fun readSmall(z: ZipInputStream): ByteArray {
        val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (true) { val n = z.read(buf); if (n < 0) break; total += n; if (total > 256 * 1024 * 1024) throw VaultException("Backup metadata too large"); bo.write(buf, 0, n) }
        return bo.toByteArray()
    }

    /** Reads and authenticates the ENTIRE backup without changing anything. */
    suspend fun verify(openStream: () -> InputStream?, password: CharArray): Info = withContext(Dispatchers.IO) {
        val input = openStream() ?: throw VaultException("Cannot open backup")
        try {
            val o = open(input, password)
            var manifest: JSONObject? = null; var dbj: JSONObject? = null; var blobCount = 0
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = o.zip.nextEntry ?: break
                when {
                    e.name == "manifest.json" -> manifest = JSONObject(String(readSmall(o.zip)))
                    e.name == "db.json" -> dbj = JSONObject(String(readSmall(o.zip)))
                    safeName(e.name) -> { if (e.name.startsWith("blobs/")) blobCount++; while (o.zip.read(buf) >= 0) { } }
                    else -> throw VaultException("Unexpected entry in backup")
                }
            }
            while (o.raw.read(buf) >= 0) { } // drain => authenticates final chunk (detects truncation)
            if (manifest == null || dbj == null) throw VaultException("Backup is incomplete")
            val files = dbj.getJSONArray("files")
            if (blobCount < files.length()) throw VaultException("Backup is missing encrypted files")
            Info(manifest.getLong("createdAt"), dbj.getJSONArray("folders").length(), files.length(),
                dbj.getJSONArray("credentials").length(), dbj.getJSONArray("notes").length())
        } catch (e: IOException) {
            throw VaultException("Wrong backup password or the backup is corrupted", e)
        } finally { input.close() }
    }

    /** Replaces the whole vault with the backup. Verifies first, then applies. Returns the NEW recovery key. */
    suspend fun restore(openStream: () -> InputStream?, backupPassword: CharArray, newSecret: CharArray, type: CredentialType): String =
        withContext(Dispatchers.IO) {
            verify(openStream, backupPassword)
            val rd = blobs.restoreDir
            rd.deleteRecursively()
            val rb = File(rd, "blobs").apply { mkdirs() }
            val rt = File(rd, "thumbs").apply { mkdirs() }
            var vmk: ByteArray? = null
            var dbj: JSONObject? = null
            val input = openStream() ?: throw VaultException("Cannot open backup")
            try {
                val o = open(input, backupPassword)
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val e = o.zip.nextEntry ?: break
                    when {
                        e.name == "manifest.json" -> vmk = Crypto.unb64(JSONObject(String(readSmall(o.zip))).getString("vmk"))
                        e.name == "db.json" -> {
                            dbj = JSONObject(String(readSmall(o.zip)))
                            blobs.ensureSpace(dbj.getJSONArray("files").let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).getLong("blobSize") } })
                        }
                        safeName(e.name) -> {
                            val dest = File(if (e.name.startsWith("blobs/")) rb else rt, e.name.substringAfter('/'))
                            FileOutputStream(dest).use { fo ->
                                while (true) { val n = o.zip.read(buf); if (n < 0) break; fo.write(buf, 0, n); done += n; _progress.value = Progress("Restoring", done, 0) }
                            }
                        }
                    }
                }
            } catch (t: Throwable) { rd.deleteRecursively(); _progress.value = null; throw t } finally { input.close() }

            val j = dbj ?: throw VaultException("Backup is incomplete")
            val key = vmk ?: throw VaultException("Backup is incomplete")
            fun <T> arr(name: String, f: (JSONObject) -> T) = j.getJSONArray(name).let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }
            fun JSONObject.nz(k: String): String? = if (isNull(k)) null else getString(k)
            db.withTransaction {
                db.folderDao().deleteAll(); db.fileDao().deleteAll(); db.credentialDao().deleteAll(); db.noteDao().deleteAll()
                db.folderDao().upsertAll(arr("folders") { FolderEntity(it.getString("id"), it.nz("parentId"), it.getString("meta"), it.getLong("c"), it.getLong("u")) })
                db.fileDao().upsertAll(arr("files") { FileEntity(it.getString("id"), it.nz("folderId"), it.getString("stored"), it.getString("wk"), it.getLong("size"), it.getLong("blobSize"), it.getString("sha"), it.getString("meta"), it.getLong("c"), it.getLong("u")) })
                db.credentialDao().upsertAll(arr("credentials") { CredentialEntity(it.getString("id"), it.getString("data"), it.getLong("c"), it.getLong("u")) })
                db.noteDao().upsertAll(arr("notes") { NoteEntity(it.getString("id"), it.getString("data"), it.getLong("c"), it.getLong("u")) })
            }
            val trash = File(blobs.root, "trash").apply { deleteRecursively(); mkdirs() }
            blobs.blobsDir.renameTo(File(trash, "blobs")); blobs.thumbsDir.renameTo(File(trash, "thumbs"))
            rb.renameTo(blobs.blobsDir); rt.renameTo(blobs.thumbsDir)
            blobs.blobsDir.mkdirs(); blobs.thumbsDir.mkdirs()
            trash.deleteRecursively(); rd.deleteRecursively()
            _progress.value = null
            vault.adoptVmk(key, newSecret, type)
        }

    // ---------- destinations / scheduling ----------
    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    suspend fun createToTree(tree: Uri, password: CharArray): Uri = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val target = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", "ShadowBox-${stamp()}.sbxbak")
            ?: throw VaultException("Cannot create file in the chosen folder")
        try {
            ctx.contentResolver.openOutputStream(target)!!.use { create(it, password) }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, target) }
            throw t
        }
        target
    }

    fun suggestedName() = "ShadowBox-${stamp()}.sbxbak"

    suspend fun markResult(ok: Boolean, msg: String) = settings.update {
        if (ok) it.copy(lastBackupAt = System.currentTimeMillis(), lastBackupStatus = "OK - $msg", backupDue = false)
        else it.copy(lastBackupStatus = "Failed - $msg")
    }

    /** Used by the scheduled worker. Password is stored sealed with the device Keystore key. */
    suspend fun runScheduled() {
        val s = settings.current()
        val tree = s.backupTree?.let(Uri::parse)
        val pwBlob = s.backupPwBlob
        if (tree == null || pwBlob == null) { markResult(false, "Scheduled backup is not configured"); return }
        val pw = String(KeystoreHelper.deviceOpen(Crypto.unb64(pwBlob)), Charsets.UTF_8).toCharArray()
        try { createToTree(tree, pw); markResult(true, "scheduled backup") } finally { pw.fill('0') }
    }

    suspend fun storeSchedulePassword(pw: CharArray) {
        val blob = Crypto.b64(KeystoreHelper.deviceSeal(String(pw).toByteArray(Charsets.UTF_8)))
        settings.update { it.copy(backupPwBlob = blob) }
    }
}
