package com.shadowbox.x.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import com.shadowbox.x.core.crypto.BlobCrypto
import com.shadowbox.x.core.crypto.Crypto
import com.shadowbox.x.core.security.VaultManager
import com.shadowbox.x.core.util.ImageUtil
import com.shadowbox.x.data.db.FileDao
import com.shadowbox.x.data.db.FileEntity
import com.shadowbox.x.data.storage.BlobStore
import com.shadowbox.x.data.storage.VaultException
import com.shadowbox.x.domain.model.FileKind
import com.shadowbox.x.domain.model.VaultFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FileRepository(
    private val ctx: Context,
    private val dao: FileDao,
    private val vault: VaultManager,
    private val blobs: BlobStore
) {
    companion object { const val ATTACH_FOLDER = "__attachments__" }

    private val thumbCache = LruCache<String, Bitmap>(80)
    private fun aad(id: String) = "file:$id"
    private fun keyAad(id: String) = "filekey:$id"

    private fun decode(e: FileEntity): VaultFile? = runCatching {
        val j = JSONObject(vault.openField(e.meta, aad(e.id)))
        VaultFile(
            e.id, e.folderId, j.getString("name"), j.optString("mime", "application/octet-stream"),
            FileKind.valueOf(j.optString("kind", "OTHER")), e.size, e.blobSize,
            j.optBoolean("favorite"), j.optBoolean("hasThumb"), e.createdAt, e.updatedAt
        )
    }.getOrNull()

    /** Vault files (note attachments excluded). */
    fun observe(): Flow<List<VaultFile>> =
        vault.epoch.flatMapLatest { dao.observeAll() }
            .map { rows -> rows.filter { it.folderId != ATTACH_FOLDER }.mapNotNull(::decode) }
            .flowOn(Dispatchers.Default)

    suspend fun get(id: String): VaultFile? = withContext(Dispatchers.IO) { dao.get(id)?.let(::decode) }

    // ---------- import ----------
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').ifEmpty { "file" }.take(150)

    fun kindOf(mime: String, name: String): FileKind {
        val m = mime.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") -> FileKind.IMAGE
            m.startsWith("video/") -> FileKind.VIDEO
            m.startsWith("audio/") -> FileKind.AUDIO
            m == "application/pdf" || ext == "pdf" -> FileKind.PDF
            m.startsWith("text/") || ext in setOf("txt", "md", "log", "json", "xml", "csv") -> FileKind.TEXT
            m.contains("zip") || ext in setOf("zip", "rar", "7z", "tar", "gz") -> FileKind.ARCHIVE
            m.contains("word") || m.contains("officedocument") || ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "rtf") -> FileKind.DOCUMENT
            else -> FileKind.OTHER
        }
    }

    private fun queryMeta(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    /** Header sniffing so obviously corrupt/misnamed files are rejected. */
    private fun validate(uri: Uri, mime: String, name: String, kind: FileKind) {
        val head = ByteArray(8)
        val n = ctx.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: throw VaultException("Cannot open file")
        if (n <= 0) throw VaultException("File is empty")
        when (kind) {
            FileKind.PDF -> if (String(head, 0, minOf(n, 4), Charsets.US_ASCII) != "%PDF") throw VaultException("Not a valid PDF")
            FileKind.IMAGE -> if (mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                if (o.outWidth <= 0) throw VaultException("Corrupt or unsupported image")
            }
            else -> {}
        }
        if (name.endsWith(".zip", true) && !(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()))
            throw VaultException("Not a valid ZIP archive")
    }

    private fun makeThumb(uri: Uri, kind: FileKind): ByteArray? = try {
        val bmp: Bitmap? = when (kind) {
            FileKind.IMAGE -> ImageUtil.decode({ ctx.contentResolver.openInputStream(uri) }, 320)
            FileKind.VIDEO -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    val f = r.getFrameAtTime(1_000_000) ?: r.getFrameAtTime(0)
                    f?.let {
                        val s = 320f / maxOf(it.width, it.height)
                        if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).toInt(), (it.height * s).toInt(), true) else it
                    }
                } finally { r.release() }
            }
            else -> null
        }
        bmp?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }
    } catch (e: Throwable) { null }

    /** Encrypts [uri] straight into private storage. Writes to .part and renames atomically; partial files are removed on failure. */
    suspend fun import(uri: Uri, folderId: String?, onProgress: (Long, Long) -> Unit = { _, _ -> }): VaultFile =
        withContext(Dispatchers.IO) {
            val (dn, size) = queryMeta(uri)
            val name = sanitize(dn ?: "file")
            val mime = ctx.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val kind = kindOf(mime, name)
            if (size == 0L) throw VaultException("File is empty")
            blobs.ensureSpace(maxOf(size, 0))
            validate(uri, mime, name, kind)

            val id = UUID.randomUUID().toString()
            val stored = UUID.randomUUID().toString()
            val fileKey = Crypto.random(32)
            val part = blobs.part(stored)
            val fin = blobs.blob(stored)
            val md = MessageDigest.getInstance("SHA-256")
            var plain = 0L
            try {
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(part).use { fos ->
                        val out = BlobCrypto.encrypt(DigestOutputStream(fos, md), fileKey)
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            plain += n
                            onProgress(plain, size)
                        }
                        out.finish()
                        fos.fd.sync()
                    }
                }
                if (plain == 0L) throw VaultException("File is empty")
                if (!part.renameTo(fin)) throw VaultException("Could not finalize encrypted file")

                var hasThumb = false
                makeThumb(uri, kind)?.let { t ->
                    FileOutputStream(blobs.thumb(id)).use { it.write(Crypto.seal(fileKey, t)) }
                    hasThumb = true
                }
                val now = System.currentTimeMillis()
                val meta = JSONObject().put("name", name).put("mime", mime).put("kind", kind.name)
                    .put("favorite", false).put("hasThumb", hasThumb)
                val e = FileEntity(
                    id, folderId, stored, vault.wrapKey(fileKey, keyAad(id)), plain, fin.length(),
                    Crypto.hex(md.digest()), vault.sealField(meta.toString(), aad(id)), now, now
                )
                dao.upsert(e)
                decode(e)!!
            } catch (t: Throwable) {
                part.delete(); fin.delete(); blobs.thumb(id).delete()
                throw t
            } finally {
                fileKey.fill(0)
            }
        }

    // ---------- mutations ----------
    private suspend fun updateMeta(id: String, block: (JSONObject) -> Unit, folder: Pair<Boolean, String?>? = null) =
        withContext(Dispatchers.IO) {
            val e = dao.get(id) ?: return@withContext
            val j = JSONObject(vault.openField(e.meta, aad(id)))
            block(j)
            dao.upsert(e.copy(
                meta = vault.sealField(j.toString(), aad(id)),
                folderId = if (folder != null) folder.second else e.folderId,
                updatedAt = System.currentTimeMillis()
            ))
        }

    suspend fun rename(id: String, name: String) = updateMeta(id, { it.put("name", sanitize(name)) })
    suspend fun move(id: String, folderId: String?) = updateMeta(id, {}, true to folderId)
    suspend fun setFavorite(id: String, fav: Boolean) = updateMeta(id, { it.put("favorite", fav) })

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext
        blobs.blob(e.storedName).delete(); blobs.thumb(id).delete(); thumbCache.remove(id)
        dao.delete(id)
    }

    // ---------- reading ----------
    private fun openStream(e: FileEntity): InputStream {
        val fk = vault.unwrapKey(e.wrappedKey, keyAad(e.id))
        return BlobCrypto.decrypt(BufferedInputStream(FileInputStream(blobs.blob(e.storedName)), 80 * 1024), fk)
    }

    /** Decrypts into the app cache. Callers MUST call [wipeTemp] when done; everything is wiped on lock. */
    suspend fun decryptToTemp(id: String): File = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: throw VaultException("File not found")
        val name = JSONObject(vault.openField(e.meta, aad(id))).getString("name")
        blobs.ensureSpace(e.size)
        val out = blobs.newTmp(sanitize(name))
        try {
            openStream(e).use { i ->
                FileOutputStream(out).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
        } catch (t: Throwable) { blobs.wipeTmp(out); throw t }
        out
    }

    fun wipeTemp(f: File?) = blobs.wipeTmp(f)

    suspend fun exportTo(id: String, dest: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: throw VaultException("File not found")
        val out = ctx.contentResolver.openOutputStream(dest, "w") ?: throw VaultException("Cannot write destination")
        openStream(e).use { i ->
            out.use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n; onProgress(done, e.size)
                }
            }
        }
    }

    suspend fun readText(id: String, maxBytes: Int = 256 * 1024): String = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: throw VaultException("File not found")
        openStream(e).use { i ->
            val buf = ByteArray(maxBytes)
            var r = 0
            while (r < maxBytes) { val n = i.read(buf, r, maxBytes - r); if (n < 0) break; r += n }
            String(buf, 0, r, Charsets.UTF_8)
        }
    }

    suspend fun thumbnail(id: String): Bitmap? = withContext(Dispatchers.IO) {
        thumbCache.get(id)?.let { return@withContext it }
        val e = dao.get(id) ?: return@withContext null
        val f = blobs.thumb(id)
        if (!f.exists()) return@withContext null
        runCatching {
            val fk = vault.unwrapKey(e.wrappedKey, keyAad(id))
            val bytes = Crypto.open(fk, f.readBytes())
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { thumbCache.put(id, it) }
        }.getOrNull()
    }

    fun clearCaches() { thumbCache.evictAll() }

    /** Removes interrupted imports (.part) and blobs with no DB row. Call after unlock. */
    suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        val known = dao.getAll().map { "${it.storedName}.sbx" }.toSet()
        blobs.blobsDir.listFiles()?.forEach { if (it.name.endsWith(".part") || it.name !in known) it.delete() }
        val ids = dao.getAll().map { "${it.id}.thm" }.toSet()
        blobs.thumbsDir.listFiles()?.forEach { if (it.name !in ids) it.delete() }
    }
}
