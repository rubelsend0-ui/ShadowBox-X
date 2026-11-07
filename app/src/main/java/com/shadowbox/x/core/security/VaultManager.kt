package com.shadowbox.x.core.security

import android.content.Context
import com.shadowbox.x.core.crypto.Crypto
import com.shadowbox.x.domain.model.CredentialType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

class LockedException : IllegalStateException("Vault is locked")

sealed class UnlockResult {
    object Success : UnlockResult()
    object Wrong : UnlockResult()
    object DeviceKeyLost : UnlockResult()
    data class LockedOut(val millis: Long) : UnlockResult()
}

/**
 * Key hierarchy:
 *  - Vault Master Key (VMK): random 256-bit, lives ONLY in RAM while unlocked.
 *  - At rest the VMK is stored three ways:
 *      password blob : Keystore-AES( AES-GCM( PBKDF2(secret), VMK ) )
 *      recovery blob : Keystore-AES( AES-GCM( PBKDF2(recovery key), VMK ) )
 *      biometric blob: AES-GCM with an auth-bound Keystore key (optional)
 *  - Each file has its own random file key, wrapped by the VMK.
 */
class VaultManager(private val ctx: Context) {
    private class Header(
        val type: CredentialType, val iter: Int,
        val pwSalt: ByteArray, val pwBlob: ByteArray,
        val recSalt: ByteArray, val recBlob: ByteArray,
        val bio: ByteArray?
    )

    private val headerFile = File(ctx.filesDir, "vault.hdr")
    private val guard = ctx.getSharedPreferences("sbx_guard", Context.MODE_PRIVATE)

    @Volatile private var vmk: ByteArray? = null
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> = _epoch.asStateFlow()
    private val _setUp = MutableStateFlow(headerFile.exists())
    val setUp: StateFlow<Boolean> = _setUp.asStateFlow()

    val unlockedFolders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var externalFlow = false
    val isUnlocked: Boolean get() = vmk != null

    private fun publish() {
        _unlocked.value = vmk != null
        _epoch.value = _epoch.value + 1
    }

    private fun key(): ByteArray = vmk ?: throw LockedException()

    // ---------- header persistence ----------
    private fun readHeader(): Header? = runCatching {
        val j = JSONObject(headerFile.readText())
        Header(
            CredentialType.valueOf(j.getString("type")), j.getInt("iter"),
            Crypto.unb64(j.getString("ps")), Crypto.unb64(j.getString("pb")),
            Crypto.unb64(j.getString("rs")), Crypto.unb64(j.getString("rb")),
            j.optString("bio").takeIf { it.isNotEmpty() }?.let { Crypto.unb64(it) }
        )
    }.getOrNull()

    private fun writeHeader(h: Header) {
        val j = JSONObject().put("v", 1).put("type", h.type.name).put("iter", h.iter)
            .put("ps", Crypto.b64(h.pwSalt)).put("pb", Crypto.b64(h.pwBlob))
            .put("rs", Crypto.b64(h.recSalt)).put("rb", Crypto.b64(h.recBlob))
        if (h.bio != null) j.put("bio", Crypto.b64(h.bio))
        val tmp = File(headerFile.path + ".tmp")
        tmp.writeText(j.toString())
        Files.move(tmp.toPath(), headerFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _setUp.value = true
    }

    private fun wrapWith(secret: CharArray, salt: ByteArray, iter: Int, vmk: ByteArray, aad: String): ByteArray {
        val k = Crypto.kdf(secret, salt, iter)
        try { return KeystoreHelper.deviceSeal(Crypto.seal(k, vmk, aad.toByteArray())) } finally { k.fill(0) }
    }

    private fun unwrapWith(secret: CharArray, salt: ByteArray, iter: Int, blob: ByteArray, aad: String): ByteArray? {
        val inner = KeystoreHelper.deviceOpen(blob) // throws DeviceKeyException
        val k = Crypto.kdf(secret, salt, iter)
        return try { Crypto.open(k, inner, aad.toByteArray()) } catch (e: GeneralSecurityException) { null } finally { k.fill(0) }
    }

    private fun normalizeRecovery(s: String) = s.uppercase().filter { it.isLetterOrDigit() }.toCharArray()

    private fun newRecoveryKey(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val raw = Crypto.random(24)
        return (0 until 24).map { alphabet[(raw[it].toInt() and 0xff) % 32] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    private fun build(vmk: ByteArray, secret: CharArray, type: CredentialType, recovery: String, bio: ByteArray?): Header {
        val ps = Crypto.random(16); val rs = Crypto.random(16); val iter = Crypto.KDF_ITER
        return Header(type, iter, ps, wrapWith(secret, ps, iter, vmk, "pw"), rs,
            wrapWith(normalizeRecovery(recovery), rs, iter, vmk, "rec"), bio)
    }

    // ---------- public API ----------
    fun credentialType(): CredentialType = readHeader()?.type ?: CredentialType.PASSWORD
    fun hasBiometric(): Boolean = readHeader()?.bio != null

    /** Creates a brand-new vault. Returns the recovery key (shown once). */
    fun setup(secret: CharArray, type: CredentialType): String {
        val k = Crypto.random(32)
        val recovery = newRecoveryKey()
        writeHeader(build(k, secret, type, recovery, null))
        KeystoreHelper.deleteBioKey()
        guard.edit().clear().apply()
        vmk = k
        publish()
        return recovery
    }

    fun lockoutRemaining(): Long = (guard.getLong("lockUntil", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    private fun registerFail() {
        val n = guard.getInt("fails", 0) + 1
        val e = guard.edit().putInt("fails", n)
        if (n >= 5) {
            val delay = (30_000L shl (n - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
            e.putLong("lockUntil", System.currentTimeMillis() + delay)
        }
        e.apply()
    }

    private fun clearFails() { guard.edit().clear().apply() }

    fun unlock(secret: CharArray): UnlockResult {
        val rem = lockoutRemaining()
        if (rem > 0) return UnlockResult.LockedOut(rem)
        val h = readHeader() ?: return UnlockResult.Wrong
        val k = try { unwrapWith(secret, h.pwSalt, h.iter, h.pwBlob, "pw") } catch (e: DeviceKeyException) { return UnlockResult.DeviceKeyLost }
        if (k == null) { registerFail(); return UnlockResult.Wrong }
        clearFails()
        vmk = k
        publish()
        return UnlockResult.Success
    }

    /** Re-auth check that does not change session state. */
    fun verify(secret: CharArray): Boolean {
        if (lockoutRemaining() > 0) return false
        val h = readHeader() ?: return false
        val k = try { unwrapWith(secret, h.pwSalt, h.iter, h.pwBlob, "pw") } catch (e: DeviceKeyException) { null }
        if (k == null) { registerFail(); return false }
        k.fill(0); clearFails(); return true
    }

    /** Unlock using recovery key, set a new master secret, returns NEW recovery key (or null if wrong). */
    fun unlockWithRecovery(recovery: String, newSecret: CharArray, newType: CredentialType): String? {
        if (lockoutRemaining() > 0) return null
        val h = readHeader() ?: return null
        val k = try { unwrapWith(normalizeRecovery(recovery), h.recSalt, h.iter, h.recBlob, "rec") } catch (e: DeviceKeyException) { null }
        if (k == null) { registerFail(); return null }
        val newRecovery = newRecoveryKey()
        writeHeader(build(k, newSecret, newType, newRecovery, h.bio))
        clearFails(); vmk = k; publish()
        return newRecovery
    }

    fun changeSecret(old: CharArray, new: CharArray, type: CredentialType): Boolean {
        val h = readHeader() ?: return false
        val k = try { unwrapWith(old, h.pwSalt, h.iter, h.pwBlob, "pw") } catch (e: DeviceKeyException) { null }
        if (k == null) { registerFail(); return false }
        val ps = Crypto.random(16)
        writeHeader(Header(type, h.iter, ps, wrapWith(new, ps, h.iter, k, "pw"), h.recSalt, h.recBlob, h.bio))
        k.fill(0); clearFails()
        return true
    }

    /** Needs unlocked state; the user must supply the current secret to re-wrap. */
    fun regenerateRecovery(secret: CharArray): String? {
        val h = readHeader() ?: return null
        val k = try { unwrapWith(secret, h.pwSalt, h.iter, h.pwBlob, "pw") } catch (e: DeviceKeyException) { null } ?: return null
        val rec = newRecoveryKey()
        val rs = Crypto.random(16)
        writeHeader(Header(h.type, h.iter, h.pwSalt, h.pwBlob, rs, wrapWith(normalizeRecovery(rec), rs, h.iter, k, "rec"), h.bio))
        k.fill(0)
        return rec
    }

    /** Used by restore: adopt the VMK from a backup and protect it with a new master secret. */
    fun adoptVmk(newVmk: ByteArray, secret: CharArray, type: CredentialType): String {
        val rec = newRecoveryKey()
        writeHeader(build(newVmk, secret, type, rec, null))
        KeystoreHelper.deleteBioKey()
        clearFails()
        vmk?.fill(0)
        vmk = newVmk
        publish()
        return rec
    }

    // ---------- biometrics ----------
    fun newBiometricEnableCipher(): Cipher = KeystoreHelper.bioEncryptCipher()

    fun finishEnableBiometric(cipher: Cipher) {
        val h = readHeader() ?: return
        val ct = cipher.doFinal(key())
        val iv = cipher.iv
        writeHeader(Header(h.type, h.iter, h.pwSalt, h.pwBlob, h.recSalt, h.recBlob, byteArrayOf(iv.size.toByte()) + iv + ct))
    }

    fun disableBiometric() {
        val h = readHeader() ?: return
        writeHeader(Header(h.type, h.iter, h.pwSalt, h.pwBlob, h.recSalt, h.recBlob, null))
        KeystoreHelper.deleteBioKey()
    }

    /** May throw KeyPermanentlyInvalidatedException when biometrics changed. */
    fun biometricUnlockCipher(): Cipher? {
        val blob = readHeader()?.bio ?: return null
        val ivLen = blob[0].toInt()
        return KeystoreHelper.bioDecryptCipher(blob.copyOfRange(1, 1 + ivLen))
    }

    fun finishBiometricUnlock(cipher: Cipher): Boolean {
        val blob = readHeader()?.bio ?: return false
        val ivLen = blob[0].toInt()
        return try {
            vmk = cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
            clearFails(); publish(); true
        } catch (e: Exception) { false }
    }

    fun lock() {
        vmk?.fill(0)
        vmk = null
        unlockedFolders.clear()
        publish()
    }

    // ---------- field & key helpers (all require unlocked state) ----------
    fun sealField(plain: String, aad: String): String =
        Crypto.b64(Crypto.seal(key(), plain.toByteArray(Charsets.UTF_8), aad.toByteArray()))

    fun openField(b64: String, aad: String): String =
        String(Crypto.open(key(), Crypto.unb64(b64), aad.toByteArray()), Charsets.UTF_8)

    fun wrapKey(fileKey: ByteArray, aad: String): String = Crypto.b64(Crypto.seal(key(), fileKey, aad.toByteArray()))
    fun unwrapKey(b64: String, aad: String): ByteArray = Crypto.open(key(), Crypto.unb64(b64), aad.toByteArray())
    fun copyKey(): ByteArray = key().copyOf()
}
