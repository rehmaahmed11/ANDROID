package com.rehma.personas.crypto

/**
 * Wraps the per-persona data key for personas that have no PIN. On device this is backed by
 * the platform keystore; in unit tests it is an identity function, because what the tests
 * exercise is the vault logic, not the keystore.
 */
interface KeyWrapper {
    /** Honest, user-visible description of what the protection actually is. */
    val description: String
    fun isAvailable(): Boolean = true
    fun wrap(key: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/** Test-only wrapper so the crypto core stays free of Android imports. */
object IdentityKeyWrapper : KeyWrapper {
    override val description = "in-process key (test fixture, not hardware backed)"
    override fun wrap(key: ByteArray) = key.copyOf()
    override fun unwrap(blob: ByteArray) = blob.copyOf()
}
