package com.shadowbox.x.data.repo

import android.content.Context
import android.net.ConnectivityManager
import com.shadowbox.x.core.crypto.Crypto
import com.shadowbox.x.core.security.VaultManager
import com.shadowbox.x.data.db.AppDatabase
import com.shadowbox.x.data.db.FileEntity
import com.shadowbox.x.data.db.FolderEntity
import com.shadowbox.x.data.storage.BlobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shadow Sync: device-to-device over local Wi-Fi, no cloud.
 *  - Receiver shows a QR {ip, port, one-time secret}. Sender scans it.
 *  - Mutual HMAC challenge/response proves both sides hold the secret; a session key is derived from it.
 *  - All frames are AES-256-GCM (direction-separated nonces + counters).
 *  - Files travel as their already-encrypted blobs (never plaintext), with the per-file key sent inside the secure channel and
 *    re-wrapped under the receiver's VMK. Resume = byte offset of the staged encrypted blob. Integrity = SHA-256 of the blob.
 *  - Conflicts: same id + same content -> newest metadata wins; same id + different content -> kept as a "(conflict copy)".
 */
class SyncRepository(
    private val ctx: Context,
    private val db: AppDatabase,
    private val vault: VaultManager,
    private val blobs: BlobStore
) {
    enum class Phase { IDLE, WAITING, CONNECTING, TRANSFERRING, DONE, ERROR }
    data class State(val phase: Phase = Phase.IDLE, val message: String = "", val done: Long = 0, val total: Long = 0, val pairing: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    private companion object {
        const val M_MANIFEST = 1; const val M_PLAN = 2; const val M_DATA = 3; const val M_FILEEND = 4; const val M_DONE = 6; const val M_RESULT = 7
        const val MAX_FRAME = 64 * 1024 * 1024
        const val DATA_CHUNK = 256 * 1024
    }

    private class Channel(private val din: DataInputStream, private val dout: DataOutputStream, private val key: ByteArray, private val dir: Byte) {
        private var sendCtr = 0L
        private var recvCtr = 0L
        private fun nonce(d: Byte, c: Long) = ByteBuffer.allocate(12).put(d).put(ByteArray(3)).putLong(c).array()
        fun send(type: Int, payload: ByteArray) {
            val plain = ByteArray(payload.size + 1); plain[0] = type.toByte(); System.arraycopy(payload, 0, plain, 1, payload.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(dir, sendCtr++)))
            val ct = c.doFinal(plain)
            dout.writeInt(ct.size); dout.write(ct); dout.flush()
        }
        fun recv(): Pair<Int, ByteArray> {
            val len = din.readInt()
            if (len < 17 || len > MAX_FRAME) throw IOException("Bad frame")
            val ct = ByteArray(len); din.readFully(ct)
            val other: Byte = if (dir.toInt() == 1) 2 else 1
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(other, recvCtr++)))
            val plain = try { c.doFinal(ct) } catch (e: Exception) { throw IOException("Secure channel authentication failed") }
            return plain[0].toInt() to plain.copyOfRange(1, plain.size)
        }
    }

    private fun hmac(secret: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(secret, "HmacSHA256"))
        parts.forEach { m.update(it) }; return m.doFinal()
    }

    private fun localIp(): String? {
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress?.let { return it }
        }
        return NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
    }

    fun cancel() {
        job?.cancel(); runCatching { server?.close() }; runCatching { client?.close() }
        _state.value = State()
    }

    private fun fail(e: Throwable) {
        _state.value = State(Phase.ERROR, e.message ?: "Sync failed")
    }

    // =================== RECEIVER ===================
    fun startReceive() {
        cancel()
        if (!vault.isUnlocked) { _state.value = State(Phase.ERROR, "Vault is locked"); return }
        val ip = localIp()
        if (ip == null) { _state.value = State(Phase.ERROR, "Connect to Wi-Fi (or enable a hotspot) first"); return }
        job = scope.launch {
            try {
                val secret = Crypto.random(32)
                val ss = ServerSocket(0).also { it.soTimeout = 300_000; server = it }
                val qr = JSONObject().put("sbx", 1).put("ip", ip).put("port", ss.localPort).put("k", Crypto.b64(secret)).toString()
                _state.value = State(Phase.WAITING, "Waiting for the other device...", pairing = qr)
                val s = ss.accept().also { client = it; it.soTimeout = 60_000 }
                ss.close()
                _state.value = State(Phase.CONNECTING, "Device connected, verifying...")
                receive(s, secret)
            } catch (e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) fail(e) }
            finally { runCatching { client?.close() } }
        }
    }

    private fun receive(s: Socket, secret: ByteArray) {
        val din = DataInputStream(s.getInputStream().buffered(64 * 1024))
        val dout = DataOutputStream(s.getOutputStream().buffered(64 * 1024))
        val nS = ByteArray(16); din.readFully(nS)
        val nR = Crypto.random(16)
        dout.write(nR); dout.write(hmac(secret, "R".toByteArray(), nS, nR)); dout.flush()
        val macS = ByteArray(32); din.readFully(macS)
        if (!MessageDigest.isEqual(macS, hmac(secret, "S".toByteArray(), nR, nS))) throw IOException("Pairing code mismatch")
        val ch = Channel(din, dout, hmac(secret, "K".toByteArray(), nS, nR), 2)

        val (t, mbytes) = ch.recv()
        if (t != M_MANIFEST) throw IOException("Protocol error")
        val man = JSONObject(String(mbytes, Charsets.UTF_8))
        val fdao = db.fileDao(); val dao = db.folderDao()

        kotlinx.coroutines.runBlocking {
            // folders: insert missing / update when newer
            val fa = man.getJSONArray("folders")
            for (i in 0 until fa.length()) {
                val f = fa.getJSONObject(i); val id = f.getString("id")
                val ex = dao.get(id)
                val u = f.getLong("u")
                if (ex == null || u > ex.updatedAt) {
                    dao.upsert(FolderEntity(id, if (f.isNull("parentId")) null else f.getString("parentId"),
                        vault.sealField(f.getString("meta"), "folder:$id"), f.getLong("c"), u))
                }
            }
            // plan
            val files = man.getJSONArray("files")
            val plan = JSONArray(); var total = 0L
            val targets = HashMap<Int, String>()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i); val id = f.getString("id"); val sha = f.getString("sha")
                val ex = fdao.get(id)
                val p = JSONObject().put("i", i)
                if (ex != null && ex.blobSha256 == sha) {
                    if (f.getLong("u") > ex.updatedAt) {
                        fdao.upsert(ex.copy(folderId = if (f.isNull("folderId")) null else f.getString("folderId"),
                            meta = vault.sealField(f.getString("meta"), "file:$id"), updatedAt = f.getLong("u")))
                    }
                    p.put("a", "skip")
                } else {
                    targets[i] = if (ex != null) UUID.randomUUID().toString() else id
                    val staged = File(blobs.incomingDir, "$id-${sha.take(16)}.part")
                    val off = if (staged.exists() && staged.length() < f.getLong("blobSize")) staged.length() else { staged.delete(); 0L }
                    p.put("a", "send").put("off", off)
                    total += f.getLong("blobSize") - off
                }
                plan.put(p)
            }
            ch.send(M_PLAN, plan.toString().toByteArray())
            _state.value = State(Phase.TRANSFERRING, "Receiving files...", 0, total)

            var done = 0L; var ok = 0; val failed = JSONArray()
            var out: FileOutputStream? = null; var cur = -1
            while (true) {
                val (type, data) = ch.recv()
                when (type) {
                    M_DATA -> {
                        val idx = ByteBuffer.wrap(data, 0, 4).int
                        if (idx != cur) {
                            out?.close(); cur = idx
                            val f = files.getJSONObject(idx)
                            out = FileOutputStream(File(blobs.incomingDir, "${f.getString("id")}-${f.getString("sha").take(16)}.part"), true)
                        }
                        blobs.ensureSpace(data.size.toLong())
                        out!!.write(data, 4, data.size - 4); done += data.size - 4
                        _state.value = State(Phase.TRANSFERRING, "Receiving ${files.getJSONObject(idx).optString("name")}", done, total)
                    }
                    M_FILEEND -> {
                        out?.close(); out = null
                        val idx = ByteBuffer.wrap(data).int; cur = -1
                        val f = files.getJSONObject(idx)
                        val staged = File(blobs.incomingDir, "${f.getString("id")}-${f.getString("sha").take(16)}.part")
                        val md = MessageDigest.getInstance("SHA-256")
                        FileInputStream(staged).use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
                        if (Crypto.hex(md.digest()) != f.getString("sha")) { staged.delete(); failed.put(f.getString("id")); continue }
                        commit(f, targets[idx] ?: f.getString("id"), staged, f.getString("id") != (targets[idx] ?: f.getString("id")))
                        ok++
                    }
                    M_DONE -> {
                        ch.send(M_RESULT, JSONObject().put("ok", ok).put("failed", failed).toString().toByteArray())
                        _state.value = if (failed.length() == 0) State(Phase.DONE, "Received $ok file(s). Integrity verified.")
                        else State(Phase.ERROR, "$ok received, ${failed.length()} failed integrity check")
                        return@runBlocking
                    }
                    else -> throw IOException("Protocol error")
                }
            }
        }
    }

    private suspend fun commit(f: JSONObject, targetId: String, staged: File, conflict: Boolean) {
        val fileKey = Crypto.unb64(f.getString("key"))
        val stored = UUID.randomUUID().toString()
        val dest = blobs.blob(stored)
        if (!staged.renameTo(dest)) { staged.copyTo(dest, true); staged.delete() }
        val meta = JSONObject(f.getString("meta"))
        if (conflict) meta.put("name", meta.optString("name") + " (conflict copy)")
        if (!f.isNull("thumb")) FileOutputStream(blobs.thumb(targetId)).use { it.write(Crypto.seal(fileKey, Crypto.unb64(f.getString("thumb")))) }
        db.fileDao().upsert(FileEntity(
            targetId, if (f.isNull("folderId")) null else f.getString("folderId"), stored,
            vault.wrapKey(fileKey, "filekey:$targetId"), f.getLong("size"), dest.length(), f.getString("sha"),
            vault.sealField(meta.toString(), "file:$targetId"), f.getLong("c"), f.getLong("u")
        ))
        fileKey.fill(0)
    }

    // =================== SENDER ===================
    fun startSend(pairing: String) {
        cancel()
        if (!vault.isUnlocked) { _state.value = State(Phase.ERROR, "Vault is locked"); return }
        job = scope.launch {
            try {
                val q = JSONObject(pairing)
                if (q.optInt("sbx") != 1) throw IOException("Not a ShadowBox pairing code")
                val secret = Crypto.unb64(q.getString("k"))
                _state.value = State(Phase.CONNECTING, "Connecting...")
                val s = Socket().also { client = it }
                s.connect(InetSocketAddress(q.getString("ip"), q.getInt("port")), 15_000); s.soTimeout = 60_000
                send(s, secret)
            } catch (e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) fail(e) }
            finally { runCatching { client?.close() } }
        }
    }

    private suspend fun send(s: Socket, secret: ByteArray) {
        val din = DataInputStream(s.getInputStream().buffered(64 * 1024))
        val dout = DataOutputStream(s.getOutputStream().buffered(64 * 1024))
        val nS = Crypto.random(16)
        dout.write(nS); dout.flush()
        val nR = ByteArray(16); din.readFully(nR)
        val macR = ByteArray(32); din.readFully(macR)
        if (!MessageDigest.isEqual(macR, hmac(secret, "R".toByteArray(), nS, nR))) throw IOException("Pairing code mismatch")
        dout.write(hmac(secret, "S".toByteArray(), nR, nS)); dout.flush()
        val ch = Channel(din, dout, hmac(secret, "K".toByteArray(), nS, nR), 1)

        val folders = db.folderDao().getAll(); val files = db.fileDao().getAll()
        val man = JSONObject()
        man.put("folders", JSONArray().also { a -> folders.forEach {
            a.put(JSONObject().put("id", it.id).put("parentId", it.parentId ?: JSONObject.NULL)
                .put("meta", vault.openField(it.meta, "folder:${it.id}")).put("c", it.createdAt).put("u", it.updatedAt)) } })
        man.put("files", JSONArray().also { a -> files.forEach { e ->
            val fk = vault.unwrapKey(e.wrappedKey, "filekey:${e.id}")
            val metaJson = vault.openField(e.meta, "file:${e.id}")
            val th = blobs.thumb(e.id)
            val thumb = if (th.exists()) runCatching { Crypto.b64(Crypto.open(fk, th.readBytes())) }.getOrNull() else null
            a.put(JSONObject().put("id", e.id).put("folderId", e.folderId ?: JSONObject.NULL).put("size", e.size)
                .put("blobSize", e.blobSize).put("sha", e.blobSha256).put("meta", metaJson).put("key", Crypto.b64(fk))
                .put("thumb", thumb ?: JSONObject.NULL).put("name", JSONObject(metaJson).optString("name"))
                .put("c", e.createdAt).put("u", e.updatedAt))
            fk.fill(0)
        } })
        ch.send(M_MANIFEST, man.toString().toByteArray())
        val (t, pb) = ch.recv()
        if (t != M_PLAN) throw IOException("Protocol error")
        val plan = JSONArray(String(pb))
        var total = 0L
        for (i in 0 until plan.length()) { val p = plan.getJSONObject(i); if (p.getString("a") == "send") total += files[p.getInt("i")].blobSize - p.getLong("off") }
        var done = 0L
        _state.value = State(Phase.TRANSFERRING, "Sending...", 0, total)
        for (i in 0 until plan.length()) {
            val p = plan.getJSONObject(i)
            if (p.getString("a") != "send") continue
            val idx = p.getInt("i"); val e = files[idx]
            RandomAccessFile(blobs.blob(e.storedName), "r").use { raf ->
                raf.seek(p.getLong("off"))
                val buf = ByteArray(DATA_CHUNK)
                while (true) {
                    val n = raf.read(buf, 4, DATA_CHUNK - 4); if (n < 0) break
                    ByteBuffer.wrap(buf, 0, 4).putInt(idx)
                    ch.send(M_DATA, buf.copyOf(n + 4)); done += n
                    _state.value = State(Phase.TRANSFERRING, "Sending ${JSONObject(man.getJSONArray("files").getJSONObject(idx).getString("meta")).optString("name")}", done, total)
                }
            }
            ch.send(M_FILEEND, ByteBuffer.allocate(4).putInt(idx).array())
        }
        ch.send(M_DONE, ByteArray(0))
        val (rt, rb) = ch.recv()
        if (rt != M_RESULT) throw IOException("Protocol error")
        val r = JSONObject(String(rb)); val failed = r.getJSONArray("failed").length()
        _state.value = if (failed == 0) State(Phase.DONE, "Sent. ${r.getInt("ok")} file(s) transferred and verified.")
        else State(Phase.ERROR, "${r.getInt("ok")} sent, $failed failed integrity check - retry to resume")
    }
}
