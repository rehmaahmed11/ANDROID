package com.rehma.personas.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps the random data key of a persona that has no PIN.
 *
 * What this buys: the wrapped key cannot be lifted off the device by reading the app's files,
 * because unwrapping goes through the platform keystore for a key marked non-exportable.
 * What it does not buy: it is not a second factor. Anyone holding an unlocked phone running
 * this app can unwrap it. Only a PIN makes a persona's vault unreadable without you.
 */
class AndroidKeyStoreWrapper(
    private val alias: String = "com.rehma.personas.wrapper",
) : KeyWrapper {

    override var description: String = "AndroidKeyStore master key, non-exportable"
        private set

    private fun loadKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // KeyStore.Entry itself exposes nothing; AndroidKeyStore hands symmetric keys back
        // through getKey(), which is the only accessor that works for every keystore provider.
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Intentionally no setUserAuthenticationRequired: a persona without a PIN must
                // still open after a reboot. Requiring unlock would make the fallback key useless
                // for exactly the case it exists for.
                .build(),
        )
        generator.generateKey()
        return (ks.getKey(alias, null) as? SecretKey)
            ?: throw IllegalStateException("keystore rejected key generation")
    }

    /** Layout written by wrap(): [12-byte IV][AES-GCM ciphertext+tag]. */
    override fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey())
        return cipher.iv + cipher.doFinal(key)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "wrapped key too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadKey(),
            GCMParameterSpec(128, blob.copyOfRange(0, IV_BYTES)),
        )
        return cipher.doFinal(blob.copyOfRange(IV_BYTES, blob.size))
    }

    override fun isAvailable(): Boolean = runCatching { loadKey(); true }.getOrElse {
        description = "AndroidKeyStore unavailable: ${it.javaClass.simpleName}"
        false
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * Used when the platform keystore is broken or unreachable (seen on a few heavily skinned
 * Android 9 ROMs). The key lives in app-private storage, so protection is the Android app
 * sandbox only. [description] says so out loud instead of pretending to be hardware backed.
 */
class FileKeyWrapper(private val keyFile: File) : KeyWrapper {

    override var description: String = "file-backed key in app storage (sandbox only, no secure hardware)"
        private set

    private fun loadOrCreate(): SecretKey {
        if (keyFile.isFile) {
            val raw = keyFile.readBytes()
            if (raw.size == 32) return SecretKeySpec(raw, "AES")
        }
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val encoded = generator.generateKey().encoded
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(encoded)
        return SecretKeySpec(encoded, "AES")
    }

    override fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreate())
        return cipher.iv + cipher.doFinal(key)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 12) { "wrapped key too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreate(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    override fun isAvailable(): Boolean = true

    fun warnUser(): Boolean = true
}

/** Picks the strongest wrapper this device actually offers, and records which one won. */
object KeyWrappers {
    fun best(filesDir: File): Pair<KeyWrapper, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val keystore = AndroidKeyStoreWrapper()
            if (keystore.isAvailable()) return keystore to keystore.description
        }
        return FileKeyWrapper(File(filesDir, "personas/wrapper.key")) to
            "no usable AndroidKeyStore on this build; fallback in use"
    }
}
