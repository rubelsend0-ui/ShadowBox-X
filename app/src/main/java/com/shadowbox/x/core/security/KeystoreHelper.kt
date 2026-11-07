package com.shadowbox.x.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class DeviceKeyException(cause: Throwable) : Exception("Device key unavailable", cause)

/** Android Keystore wrapper. Key material never leaves the Keystore. */
object KeystoreHelper {
    private const val PROVIDER = "AndroidKeyStore"
    private const val DEVICE_ALIAS = "shadowbox_device_wrap_v1"
    private const val BIO_ALIAS = "shadowbox_bio_unlock_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun create(alias: String, authRequired: Boolean): SecretKey {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (authRequired) {
            b.setUserAuthenticationRequired(true)
            b.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                b.setUserAuthenticationValidityDurationSeconds(-1)
            }
        }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        kg.init(b.build())
        return kg.generateKey()
    }

    private fun deviceKey(): SecretKey {
        val ks = store()
        (ks.getKey(DEVICE_ALIAS, null) as? SecretKey)?.let { return it }
        return create(DEVICE_ALIAS, false)
    }

    /** Device-bound wrapping layer: blob = ivLen || iv || ciphertext */
    fun deviceSeal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, deviceKey())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    @Throws(DeviceKeyException::class)
    fun deviceOpen(blob: ByteArray): ByteArray {
        try {
            val ivLen = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivLen)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, iv))
            return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
        } catch (e: Exception) {
            throw DeviceKeyException(e)
        }
    }

    fun bioEncryptCipher(): Cipher {
        deleteBioKey()
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, create(BIO_ALIAS, true))
        return c
    }

    fun bioDecryptCipher(iv: ByteArray): Cipher {
        val key = store().getKey(BIO_ALIAS, null) as? SecretKey ?: throw IllegalStateException("No biometric key")
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c
    }

    fun deleteBioKey() {
        runCatching {
            val ks = store()
            if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS)
        }
    }

    @Suppress("DEPRECATION")
    fun isHardwareBacked(): Boolean = runCatching {
        val k = deviceKey()
        val f = SecretKeyFactory.getInstance(k.algorithm, PROVIDER)
        (f.getKeySpec(k, KeyInfo::class.java) as KeyInfo).isInsideSecureHardware
    }.getOrDefault(false)
}
