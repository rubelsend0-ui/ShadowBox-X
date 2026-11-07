package com.shadowbox.x.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

class BiometricHelper(private val activity: FragmentActivity, private val vault: VaultManager? = null) {
    fun available(): Boolean =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun strongAvailable(): Boolean =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Presence check (no key release). Negative button => [onFallback]. */
    fun prompt(title: String, subtitle: String, negative: String, onSuccess: () -> Unit, onFallback: () -> Unit, onError: (String) -> Unit) {
        vault?.externalFlow = true
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) onFallback() else onError(errString.toString())
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK).setNegativeButtonText(negative).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb).authenticate(info)
    }

    /** Keystore-bound prompt: the cipher is only usable after a successful strong biometric. */
    fun promptCrypto(cipher: Cipher, title: String, subtitle: String, onSuccess: (Cipher) -> Unit, onError: (String) -> Unit) {
        vault?.externalFlow = true
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) onSuccess(c) else onError("Biometric key unavailable")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError(errString.toString()) }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText("Cancel").build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
