package com.shadowbox.x.data.storage

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class VaultException(msg: String, cause: Throwable? = null) : Exception(msg, cause)
class LowStorageException : Exception("Not enough free storage space")

class BlobStore(ctx: Context) {
    val root = File(ctx.filesDir, "vault").apply { mkdirs() }
    val blobsDir = File(root, "blobs").apply { mkdirs() }
    val thumbsDir = File(root, "thumbs").apply { mkdirs() }
    val incomingDir = File(root, "incoming").apply { mkdirs() }
    val restoreDir = File(root, "restore")
    val tmpDir = File(ctx.cacheDir, "sbx_tmp").apply { mkdirs() }

    fun blob(stored: String) = File(blobsDir, "$stored.sbx")
    fun part(stored: String) = File(blobsDir, "$stored.sbx.part")
    fun thumb(id: String) = File(thumbsDir, "$id.thm")

    fun ensureSpace(bytes: Long) {
        val free = StatFs(root.path).availableBytes
        if (free < bytes + bytes / 20 + 16L * 1024 * 1024) throw LowStorageException()
    }

    fun newTmp(name: String): File {
        tmpDir.mkdirs()
        val d = File(tmpDir, UUID.randomUUID().toString()).apply { mkdirs() }
        return File(d, name)
    }

    /** Best-effort overwrite (files <= 256 MB) then delete. */
    fun wipe(f: File) {
        runCatching {
            if (f.isFile && f.length() in 1..(256L * 1024 * 1024)) {
                RandomAccessFile(f, "rw").use { raf ->
                    val z = ByteArray(64 * 1024)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) { val n = minOf(left, z.size.toLong()).toInt(); raf.write(z, 0, n); left -= n }
                    raf.fd.sync()
                }
            }
        }
        f.delete()
    }

    fun wipeTmp(f: File?) {
        if (f == null) return
        wipe(f)
        f.parentFile?.takeIf { it.parentFile == tmpDir }?.delete()
    }

    fun clearTmp() {
        tmpDir.listFiles()?.forEach { d -> d.walkBottomUp().forEach { if (it.isFile) wipe(it) else it.delete() } }
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
