package com.shadowbox.x.core.crypto

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Low-level primitives. AES-256-GCM everywhere, PBKDF2-HMAC-SHA256 for passwords. */
object Crypto {
    const val CHUNK = 64 * 1024
    const val KDF_ITER = 400_000
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun kdf(secret: CharArray, salt: ByteArray, iterations: Int = KDF_ITER): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Output: 12-byte random nonce || ciphertext+tag */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    @Throws(GeneralSecurityException::class)
    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        if (blob.size < 28) throw GeneralSecurityException("Blob too short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

internal object ChunkCipher {
    private fun nonce(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    /** AAD binds chunk index and "is final" flag => reordering/truncation is detected. */
    private fun aad(index: Int, final: Boolean): ByteArray =
        ByteBuffer.allocate(5).putInt(index).put(if (final) 1 else 0).array()

    fun encrypt(key: SecretKeySpec, prefix: ByteArray, index: Int, final: Boolean, data: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, index)))
        c.updateAAD(aad(index, final))
        return c.doFinal(data, 0, len)
    }

    fun decrypt(key: SecretKeySpec, prefix: ByteArray, index: Int, final: Boolean, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, index)))
        c.updateAAD(aad(index, final))
        return c.doFinal(ct)
    }
}

/** Streaming AES-256-GCM: 64 KiB chunks, never loads a whole file in RAM. */
class GcmChunkOutputStream(
    private val out: OutputStream,
    key: ByteArray,
    private val prefix: ByteArray
) : OutputStream() {
    private val keySpec = SecretKeySpec(key, "AES")
    private val buf = ByteArray(Crypto.CHUNK)
    private var n = 0
    private var index = 0
    private var finished = false

    override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "Stream finished" }
        var o = off
        var l = len
        while (l > 0) {
            if (n == buf.size) emit(false)
            val c = minOf(l, buf.size - n)
            System.arraycopy(b, o, buf, n, c)
            n += c; o += c; l -= c
        }
    }

    private fun emit(final: Boolean) {
        val ct = ChunkCipher.encrypt(keySpec, prefix, index, final, buf, n)
        out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
        out.write(ct)
        n = 0
        index++
    }

    /** Writes the final chunk without closing the underlying stream. */
    fun finish() {
        if (!finished) {
            emit(true)
            finished = true
            out.flush()
        }
    }

    override fun flush() { /* chunks are flushed on boundaries only */ }
    override fun close() { finish(); out.close() }
}

class GcmChunkInputStream(
    private val inp: InputStream,
    key: ByteArray,
    private val prefix: ByteArray
) : InputStream() {
    private val keySpec = SecretKeySpec(key, "AES")
    private var cur = ByteArray(0)
    private var pos = 0
    private var index = 0
    private var eof = false
    private var pendingLen: Int

    init {
        pendingLen = readLen()
        if (pendingLen < 0) throw IOException("Truncated encrypted data")
    }

    private fun readLen(): Int {
        val h = ByteArray(4)
        var r = 0
        while (r < 4) {
            val x = inp.read(h, r, 4 - r)
            if (x < 0) {
                if (r == 0) return -1
                throw IOException("Truncated encrypted data")
            }
            r += x
        }
        val l = ByteBuffer.wrap(h).int
        if (l < 16 || l > Crypto.CHUNK + 16) throw IOException("Corrupt encrypted data")
        return l
    }

    private fun fill(): Boolean {
        if (eof) return false
        val ct = ByteArray(pendingLen)
        var r = 0
        while (r < ct.size) {
            val x = inp.read(ct, r, ct.size - r)
            if (x < 0) throw IOException("Truncated encrypted data")
            r += x
        }
        val next = readLen()
        val final = next < 0
        cur = try {
            ChunkCipher.decrypt(keySpec, prefix, index, final, ct)
        } catch (e: GeneralSecurityException) {
            throw IOException("Authentication failed (wrong key or tampered data)")
        }
        index++
        pos = 0
        if (final) eof = true else pendingLen = next
        return true
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n < 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= cur.size) {
            if (!fill()) return -1
        }
        val c = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, c)
        pos += c
        return c
    }

    override fun close() { inp.close() }
}

object BlobCrypto {
    const val FILE_MAGIC = "SBX1"

    fun encrypt(out: OutputStream, key: ByteArray, magic: String = FILE_MAGIC): GcmChunkOutputStream {
        val prefix = Crypto.random(8)
        out.write(magic.toByteArray(Charsets.US_ASCII))
        out.write(prefix)
        return GcmChunkOutputStream(out, key, prefix)
    }

    fun decrypt(input: InputStream, key: ByteArray, magic: String = FILE_MAGIC): GcmChunkInputStream {
        val h = ByteArray(12)
        var r = 0
        while (r < h.size) {
            val x = input.read(h, r, h.size - r)
            if (x < 0) throw IOException("Not an encrypted blob")
            r += x
        }
        if (String(h, 0, 4, Charsets.US_ASCII) != magic) throw IOException("Bad magic")
        return GcmChunkInputStream(input, key, h.copyOfRange(4, 12))
    }
}
