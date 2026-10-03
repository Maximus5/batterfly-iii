package io.github.maximus5.ffnotifyimporter.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal AES-GCM encrypt/decrypt backed by a hardware-bound Android Keystore key - stands in for
 * `androidx.security:security-crypto`'s `EncryptedSharedPreferences`/`MasterKey`, all of which
 * were deprecated as of security-crypto 1.1.0 with no further releases planned. This is what
 * Android's own deprecation notice points at instead: a Keystore-backed `Cipher` directly, with
 * the ciphertext kept in plain `SharedPreferences` (done by [FireflyPrefs], the one caller). One
 * key, reused for every value this app ever needs to encrypt - there's only ever been a couple of
 * short secret strings (Firefly base URL + token), no need for per-value keys/rotation.
 */
object KeystoreCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "firefly_prefs_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private val secretKey: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    /** `ivBase64:cipherBase64` - GCM generates a fresh random IV every encrypt call, and decrypt
     * needs that exact IV back, so it travels alongside the ciphertext instead of being fixed. */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val cipherText = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "$iv:$cipherText"
    }

    /** Null on any failure (corrupt/foreign-format stored value, key unavailable, etc.) - callers
     * treat that the same as "nothing stored yet" rather than crashing. */
    fun decrypt(stored: String): String? {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
