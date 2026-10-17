package com.rehma.personas.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException(message: String) : Exception(message)
class VaultCorruptException(message: String) : Exception(message)

/**
 * All primitives come from the JDK/Conscrypt that ships with Android itself
 * (PBKDF2WithHmacSHA256, HmacSHA256, AES/GCM/NoPadding). No third-party crypto, no
 * native code, nothing that can silently degrade to ECB on an OEM ROM.
 */
object Crypto {

    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BYTES = 32
    const val PBKDF2_ITERATIONS = 150_000

    /** Byte 0 is a format version, so a future release can migrate without guessing. */
    private const val BLOB_VERSION: Byte = 1

    private val random = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /**
     * Password-based key derivation. Iteration count is chosen for a ~150 ms delay on a
     * 2018-era mid-range SoC: annoying enough to slow an offline attacker, short enough
     * that unlocking by hand does not feel broken.
     */
    fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BYTES * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.copyOf(KEY_BYTES)
    }

    /** HKDF-Expand (RFC 5869, SHA-256): one master secret -> independent sub-keys. */
    fun expand(master: ByteArray, label: String, length: Int = KEY_BYTES): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        val info = label.toByteArray(Charsets.UTF_8)
        var block = ByteArray(0)
        val out = ByteArray(length)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.reset()
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - written)
            System.arraycopy(block, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    /**
     * Envelope: version | 12-byte random IV | AES-256-GCM ciphertext+tag.
     * `aad` binds the blob to its owner id, so a ciphertext copied from persona A to
     * persona B's slot decrypts to nothing rather than to A's data.
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = random(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plaintext)
        return ByteArray(1 + IV_BYTES + body.size).also { out ->
            out[0] = BLOB_VERSION
            System.arraycopy(iv, 0, out, 1, IV_BYTES)
            System.arraycopy(body, 0, out, 1 + IV_BYTES, body.size)
        }
    }

    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + 16) throw VaultCorruptException("blob truncated")
        if (blob[0] != BLOB_VERSION) throw VaultCorruptException("unsupported blob version ${blob[0]}")
        val iv = blob.copyOfRange(1, 1 + IV_BYTES)
        val body = blob.copyOfRange(1 + IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(body)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultCorruptException("authentication tag mismatch")
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
