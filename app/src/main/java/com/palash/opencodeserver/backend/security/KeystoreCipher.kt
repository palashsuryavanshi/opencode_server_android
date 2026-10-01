package com.palash.opencodeserver.backend.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * Spec 27 - Android Keystore-backed encryption.
 *
 * The AES key is generated inside the Keystore and is never exportable, so this class cannot leak
 * the key material even if the process memory is dumped. Ciphertext is stored as
 * `base64(iv) : base64(ciphertext+tag)`.
 *
 * Devices below API 23 have no Keystore, but the app's `minSdk` is 24, so this path always exists.
 */
class KeystoreCipher(
    private val alias: String = DEFAULT_ALIAS
) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    /** True when the key store is usable on this device, creating the key if needed. */
    val isAvailable: Boolean
        get() = runCatching { secretKey(); true }.getOrDefault(false)

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Deliberately not requiring user authentication: the server credential must be usable
            // for a background restart while the screen is locked (spec 36/38).
            .setUserAuthenticationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(false)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun secretKey(): SecretKey =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()

    /** Encrypt with a fresh random IV. Returns the encoded payload, never the key. */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    /** Returns null when the payload cannot be decrypted, so callers can re-prompt rather than crash. */
    fun decrypt(encoded: String): String? = runCatching {
        val parts = encoded.split(":")
        require(parts.size == 2) { "Malformed encrypted payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Spec 90 - encrypt an export so a backup archive is never plaintext on disk.
     *
     * A stable passphrase-derived key is used rather than the Keystore key, because the archive must
     * remain decryptable on another install where the Keystore key does not exist.
     */
    fun encryptForBackup(plainText: String, passphrase: CharArray): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(BACKUP_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, salt))
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(salt, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    fun decryptFromBackup(encoded: String, passphrase: CharArray): String? = runCatching {
        val parts = encoded.split(":")
        require(parts.size == 2) { "Malformed backup payload" }
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(BACKUP_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, salt))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /** PBKDF2 with a high iteration count; the archive format has no room for a stored IV. */
    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(passphrase, salt, 200_000, 256)
        return factory.generateSecret(spec).encoded.also { spec.clearPassword() }
    }

    /** Rotate the key. Called when the user regenerates the server password. */
    fun deleteKey() {
        runCatching {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val BACKUP_TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "opencode_backend_credentials"

        /** Spec 15 - a strong random password, generated locally and never transmitted. */
        fun generateServerPassword(length: Int = 32): String {
            val alphabet = ("ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnopqrstuvwxyz" + "23456789")
            val secure = java.security.SecureRandom()
            return buildString(length) {
                repeat(length) { append(alphabet[secure.nextInt(alphabet.length)]) }
            }
        }
    }
}