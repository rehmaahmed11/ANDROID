package com.rehma.personas.crypto

/**
 * One encrypted document per persona, keyed on that persona's own secret.
 *
 * Isolation model, stated precisely:
 *  - PIN-protected persona: the data key is PBKDF2(pin, salt). It is never stored, so the
 *    vault file is unreadable without the PIN even if the whole app data dir is copied off
 *    the device (which itself requires root).
 *  - Non-PIN persona: the data key is random and stored wrapped by an AndroidKeyStore key.
 *    Protection is the Android per-app/per-user sandbox, not a second secret.
 *
 * Files per persona: `.vault` (ciphertext), `.salt`, `.lock` (PIN verifier), `.key`
 * (wrapped key for the no-PIN mode). AAD binds every blob to its persona id.
 */
class PersonaVault(
    private val storage: VaultStorage,
    private val personaId: String,
    /** Tunable so the self-test does not burn 150k iterations a dozen times. */
    private val iterations: Int = Crypto.PBKDF2_ITERATIONS,
) {
    companion object {
        private const val VERIFIER = "personas.verifier.v1"
        private const val DATA_LABEL = "data-v1"
        private const val LOCK_LABEL = "lock-v1"
        private const val WRAP_LABEL = "wrap-v1"
    }

    private val vaultName = "p-$personaId.vault"
    private val saltName = "p-$personaId.salt"
    private val lockName = "p-$personaId.lock"
    private val wrappedName = "p-$personaId.key"
    private val aad = "personas/$personaId".toByteArray(Charsets.UTF_8)

    val hasData: Boolean get() = storage.exists(vaultName)
    val hasPin: Boolean get() = storage.exists(lockName) && storage.exists(saltName)

    private fun keyFromPin(pin: String): ByteArray {
        val salt = storage.read(saltName) ?: throw WrongPassphraseException("no PIN enrolled")
        val master = Crypto.pbkdf2(pin, salt, iterations)
        return Crypto.expand(master, DATA_LABEL)
    }

    /** Enrols (or replaces) a PIN, re-encrypting existing data under the new key. */
    fun enrollPin(pin: String, currentKey: ByteArray?) {
        require(pin.length >= 4) { "PIN must be at least 4 characters" }
        val salt = Crypto.random(Crypto.SALT_BYTES)
        val master = Crypto.pbkdf2(pin, salt, iterations)
        val newKey = Crypto.expand(master, DATA_LABEL)

        if (hasData) {
            // Without the old key we could not re-encrypt, and silently orphaning the data
            // would be far worse than refusing the operation.
            checkNotNull(currentKey) { "unlock this persona before setting a PIN" }
            if (!currentKey.contentEquals(newKey)) {
                val plain = decryptWith(currentKey)
                    ?: throw VaultCorruptException("existing vault unreadable")
                storage.write(vaultName, Crypto.encrypt(newKey, plain, aad))
            }
        }
        storage.write(saltName, salt)
        storage.write(lockName, Crypto.encrypt(Crypto.expand(master, LOCK_LABEL), VERIFIER.toByteArray(), aad))
        // A PIN now guards this vault, so the wrapped fallback key is dead weight: remove it.
        storage.delete(wrappedName)
    }

    fun unlockWithPin(pin: String): ByteArray {
        val salt = storage.read(saltName) ?: throw WrongPassphraseException("no PIN enrolled")
        val master = Crypto.pbkdf2(pin, salt, iterations)
        val lockKey = Crypto.expand(master, LOCK_LABEL)
        val blob = storage.read(lockName) ?: throw WrongPassphraseException("no PIN enrolled")
        try {
            val opened = String(Crypto.decrypt(lockKey, blob, aad), Charsets.UTF_8)
            if (opened != VERIFIER) throw WrongPassphraseException("verifier mismatch")
        } catch (e: VaultCorruptException) {
            throw WrongPassphraseException("incorrect PIN")
        }
        return Crypto.expand(master, DATA_LABEL)
    }

    fun removePin(pin: String, wrapper: KeyWrapper): ByteArray {
        val key = unlockWithPin(pin)
        storage.delete(lockName)
        storage.delete(saltName)
        return rotateToWrappedKey(key, wrapper)
    }

    /** Creates (or recovers) the random key used by a persona without a PIN. */
    fun deviceKey(wrapper: KeyWrapper): ByteArray {
        val existing = storage.read(wrappedName)
        if (existing != null) return Crypto.expand(wrapper.unwrap(existing), WRAP_LABEL + "-u")
        val fresh = Crypto.random(Crypto.KEY_BYTES)
        storage.write(wrappedName, wrapper.wrap(fresh))
        return Crypto.expand(fresh, WRAP_LABEL + "-u")
    }

    private fun rotateToWrappedKey(fromKey: ByteArray, wrapper: KeyWrapper): ByteArray {
        val plain = decryptWith(fromKey)
        val newKey = Crypto.random(Crypto.KEY_BYTES)
        storage.write(wrappedName, wrapper.wrap(newKey))
        val expanded = Crypto.expand(newKey, WRAP_LABEL + "-u")
        if (plain != null) storage.write(vaultName, Crypto.encrypt(expanded, plain, aad))
        return expanded
    }

    fun save(text: String, key: ByteArray) {
        if (text.isEmpty()) {
            storage.delete(vaultName)
            return
        }
        storage.write(vaultName, Crypto.encrypt(key, text.toByteArray(Charsets.UTF_8), aad))
    }

    /** @return null when the persona has no data yet or the key does not match it. */
    fun open(key: ByteArray): String? = decryptWith(key)?.toString(Charsets.UTF_8)

    private fun decryptWith(key: ByteArray): ByteArray? {
        val blob = storage.read(vaultName) ?: return null
        return try {
            Crypto.decrypt(key, blob, aad)
        } catch (e: VaultCorruptException) {
            null
        }
    }

    /** Removes every trace of this persona's material from storage. */
    fun wipe() {
        listOf(vaultName, saltName, lockName, wrappedName).forEach(storage::delete)
    }

    fun files(): List<String> = storage.names().filter { it.contains(personaId) }
}
