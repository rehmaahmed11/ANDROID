package com.rehma.personas

import com.rehma.personas.crypto.Crypto
import com.rehma.personas.crypto.VaultCorruptException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    private val salt = ByteArray(Crypto.SALT_BYTES) { (it * 7 + 3).toByte() }

    @Test
    fun pbkdf2IsDeterministicAndSaltDependent() {
        val a = Crypto.pbkdf2("hunter2", salt, 1_000)
        val b = Crypto.pbkdf2("hunter2", salt, 1_000)
        val c = Crypto.pbkdf2("hunter2", ByteArray(Crypto.SALT_BYTES) { (it * 11).toByte() }, 1_000)
        val d = Crypto.pbkdf2("hunter3", salt, 1_000)
        assertEquals(Crypto.KEY_BYTES, a.size)
        assertArrayEquals(a, b)
        assertNotEquals(a.toList(), c.toList())
        assertNotEquals(a.toList(), d.toList())
    }

    /**
     * Known-answer vectors, computed with an independent implementation (Python's
     * hashlib.pbkdf2_hmac). If a device's crypto provider ever produced a different key here,
     * every vault in this app would be quietly incompatible - so the test pins the bytes.
     */
    @Test
    fun pbkdf2MatchesPublishedVectors() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            Crypto.sha256Hex(Crypto.pbkdf2("password", "salt".toByteArray(), 1)),
        )
        assertEquals(
            "9446e2c5ca10e6da1894b7266fea28fa5695ec3485acb77ecf35efb4a01248d5",
            Crypto.sha256Hex(Crypto.pbkdf2("password", "password".toByteArray(), 4096)),
        )
    }

    @Test
    fun expandProducesIndependentKeys() {
        val master = Crypto.pbkdf2("pin-1234", salt, 1_000)
        val data = Crypto.expand(master, "data-v1")
        val lock = Crypto.expand(master, "lock-v1")
        assertEquals(32, data.size)
        assertEquals(32, lock.size)
        assertNotEquals(data.toList(), lock.toList())
        assertArrayEquals(data, Crypto.expand(master, "data-v1"))
    }

    @Test
    fun encryptDecryptRoundTripsUtf8() {
        val key = Crypto.random(Crypto.KEY_BYTES)
        val plain = "hello 🙂 — em dash\n\tand a newline".toByteArray()
        val blob = Crypto.encrypt(key, plain, "aad".toByteArray())
        // GCM output must not contain the plaintext at any offset, or the mode is not what
        // we asked the provider for.
        val leaks = (0..blob.size - plain.size).any { i ->
            plain.indices.all { j -> blob[i + j] == plain[j] }
        }
        assertTrue("ciphertext contains plaintext", !leaks)
        assertArrayEquals(plain, Crypto.decrypt(key, blob, "aad".toByteArray()))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val key = Crypto.random(Crypto.KEY_BYTES)
        val a = Crypto.encrypt(key, "same".toByteArray(), "aad".toByteArray())
        val b = Crypto.encrypt(key, "same".toByteArray(), "aad".toByteArray())
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals("same".toByteArray(), Crypto.decrypt(key, a, "aad".toByteArray()))
        assertArrayEquals("same".toByteArray(), Crypto.decrypt(key, b, "aad".toByteArray()))
    }

    @Test
    fun wrongKeyAndWrongAadAreBothRejected() {
        val key = Crypto.random(Crypto.KEY_BYTES)
        val blob = Crypto.encrypt(key, "secret".toByteArray(), "alice".toByteArray())
        var wrongKey = false
        try {
            Crypto.decrypt(Crypto.random(Crypto.KEY_BYTES), blob, "alice".toByteArray())
        } catch (e: VaultCorruptException) {
            wrongKey = true
        }
        var wrongAad = false
        try {
            Crypto.decrypt(key, blob, "bob".toByteArray())
        } catch (e: VaultCorruptException) {
            wrongAad = true
        }
        assertTrue("wrong key must not decrypt", wrongKey)
        assertTrue("a different owner must not decrypt", wrongAad)
    }

    @Test
    fun tamperedBlobIsRejected() {
        val key = Crypto.random(Crypto.KEY_BYTES)
        val blob = Crypto.encrypt(key, "0123456789".toByteArray(), "aad".toByteArray())
        for (index in blob.indices step 7) {
            val edited = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x40).toByte() }
            var rejected = false
            try {
                Crypto.decrypt(key, edited, "aad".toByteArray())
            } catch (e: VaultCorruptException) {
                rejected = true
            }
            assertTrue("flip at $index was not detected", rejected)
        }
    }

    @Test
    fun versionByteIsEnforced() {
        val key = Crypto.random(Crypto.KEY_BYTES)
        val blob = Crypto.encrypt(key, "x".toByteArray(), ByteArray(0))
        blob[0] = 99
        var rejected = false
        try {
            Crypto.decrypt(key, blob, ByteArray(0))
        } catch (e: VaultCorruptException) {
            rejected = true
        }
        assertTrue("unknown format version accepted", rejected)
    }
}
