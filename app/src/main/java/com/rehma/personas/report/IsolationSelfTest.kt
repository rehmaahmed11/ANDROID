package com.rehma.personas.report

import com.rehma.personas.crypto.Crypto
import com.rehma.personas.crypto.FileVaultStorage
import com.rehma.personas.crypto.IdentityKeyWrapper
import com.rehma.personas.crypto.PersonaVault
import com.rehma.personas.crypto.VaultCorruptException
import com.rehma.personas.crypto.VaultStorage
import com.rehma.personas.crypto.WrongPassphraseException
import com.rehma.personas.crypto.InMemoryVaultStorage
import com.rehma.personas.data.Persona
import com.rehma.personas.data.PersonasCodec
import java.io.File

data class Case(val title: String, val passed: Boolean, val detail: String) {
    val mark: String get() = if (passed) "PASS" else "FAIL"
    override fun toString() = "[$mark] $title — $detail"
}

/**
 * Executable proof of the isolation guarantee, run in CI as a unit test and in the app from
 * the Diagnostics screen. Same code both places, so "it passes in CI" means the code the user
 * is running behaves this way — not a claim in a README.
 *
 * Everything here is pure Kotlin + JCA: no Android framework, no emulator needed.
 */
object IsolationSelfTest {

    /** 2000 iterations instead of 150 000: a self-test must not take minutes on a 2018 phone. */
    private const val TEST_ITERATIONS = 2_000

    fun run(): List<Case> {
        val cases = ArrayList<Case>()

        cases += case("persona round-trip keeps hostile text intact") {
            val store = InMemoryVaultStorage()
            val vault = vault(store, "alice")
            val key = vault.deviceKey(IdentityKeyWrapper)
            val text = "café\n\temoji 🙂 = ok\n----- \nline with = sign"
            vault.save(text, key)
            assertEquals(text, vault.open(key) ?: "<null>", "stored text differs")
            "UTF-8, newlines and separator-looking content survived"
        }

        cases += case("persona B cannot read persona A's vault") {
            val store = InMemoryVaultStorage()
            val a = vault(store, "alice")
            val b = vault(store, "bob")
            val keyA = a.deviceKey(IdentityKeyWrapper)
            val keyB = b.deviceKey(IdentityKeyWrapper)
            a.save("private", keyA)
            val stolen = b.open(keyB)
            require(stolen == null) { "bob read alice's data" }
            // Bob has no files of his own; he only ever sees his own slot.
            require(b.files().isEmpty()) { "bob can see alice's files: ${b.files()}" }
            "bob's slot is empty while alice's ciphertext exists in the same directory"
        }

        cases += case("ciphertext copied between personas does not decrypt") {
            val store = InMemoryVaultStorage()
            val a = vault(store, "alice")
            a.save("secret-a", a.deviceKey(IdentityKeyWrapper))
            val blob = store.read("p-alice.vault")!!
            val b = vault(store, "bob")
            val keyB = b.deviceKey(IdentityKeyWrapper)
            // Same key material, different owner id in the AAD: the swap must fail.
            store.write("p-bob.vault", blob)
            val opened = b.open(keyB)
            require(opened == null) { "swapped blob decrypted to '$opened'" }
            "AAD binding rejects the swapped blob"
        }

        cases += case("single-bit tampering is detected") {
            val store = InMemoryVaultStorage()
            val v = vault(store, "tamper")
            val key = v.deviceKey(IdentityKeyWrapper)
            v.save("hello", key)
            val blob = store.read("p-tamper.vault")!!
            blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
            store.write("p-tamper.vault", blob)
            require(v.open(key) == null) { "modified ciphertext was accepted" }
            "GCM tag rejected the edited byte"
        }

        cases += case("wrong PIN is refused, correct PIN opens the vault") {
            val store = InMemoryVaultStorage()
            val v = vault(store, "pinned")
            v.save("note", v.deviceKey(IdentityKeyWrapper))
            v.enrollPin("1234-abcd", v.deviceKey(IdentityKeyWrapper))
            var rejected = false
            try {
                v.unlockWithPin("0000-0000")
            } catch (e: WrongPassphraseException) {
                rejected = true
            }
            require(rejected) { "wrong PIN unlocked the vault" }
            val key = v.unlockWithPin("1234-abcd")
            assertEquals("note", v.open(key) ?: "<null>", "data lost across PIN enrol")
            "verifier file gates the data key"
        }

        cases += case("enrolling a PIN re-encrypts and keeps data") {
            val store = InMemoryVaultStorage()
            val v = vault(store, "rotate")
            val before = v.deviceKey(IdentityKeyWrapper)
            v.save("must survive", before)
            v.enrollPin("pin-99", before)
            val after = v.unlockWithPin("pin-99")
            require(!after.contentEquals(before)) { "PIN enrol did not change the key" }
            assertEquals("must survive", v.open(after) ?: "<null>", "re-encryption lost data")
            "key rotated, plaintext identical"
        }

        cases += case("removing a persona wipes every one of its files") {
            val dir = tempDir()
            try {
                val store = FileVaultStorage(dir)
                val v = PersonaVault(store, "wipe", TEST_ITERATIONS)
                v.save("x", v.deviceKey(IdentityKeyWrapper))
                v.enrollPin("pin-1234", v.deviceKey(IdentityKeyWrapper))
                require(v.files().isNotEmpty()) { "no files created" }
                v.wipe()
                require(v.files().isEmpty()) { "left behind: ${v.files()}" }
                "${store.names().size} files remain in the directory, none belonging to the persona"
            } finally {
                dir.deleteRecursively()
            }
        }

        cases += case("real FileVaultStorage persists across a reload") {
            val dir = tempDir()
            try {
                val v1 = PersonaVault(FileVaultStorage(dir), "disk", TEST_ITERATIONS)
                val key = v1.deviceKey(IdentityKeyWrapper)
                v1.save("written to disk", key)
                val v2 = PersonaVault(FileVaultStorage(dir), "disk", TEST_ITERATIONS)
                assertEquals("written to disk", v2.open(key) ?: "<null>", "file-backed vault unreadable")
                "write-then-rename produced a readable file"
            } finally {
                dir.deleteRecursively()
            }
        }

        cases += case("registry codec survives separator-looking names") {
            val input = listOf(
                Persona("a1", "-\n=personas.v1", 0xFF112233, 1, 2, true, 30, "line1\nline2"),
                Persona("b2", "plain", 0xFF445566, 3, 4, false, 60, ""),
                Persona("c3", "‏ unicode ✅", 0xFF778899, 5, 6, false, 0, ""),
            )
            val decoded = PersonasCodec.decode(PersonasCodec.encode(input))
            assertEquals(input.toString(), decoded.toString(), "codec round-trip changed data")
            "${input.size} personas, ${PersonasCodec.encode(input).length} bytes on disk"
        }

        cases += case("truncated vault file fails closed, no crash") {
            val store = InMemoryVaultStorage()
            val v = vault(store, "short")
            v.save("payload", v.deviceKey(IdentityKeyWrapper))
            store.write("p-short.vault", byteArrayOf(1, 2, 3))
            require(v.open(vault(store, "short").deviceKey(IdentityKeyWrapper)) == null) {
                "truncated blob was accepted"
            }
            var threw = false
            try {
                Crypto.decrypt(ByteArray(32), byteArrayOf(1), ByteArray(1))
            } catch (e: VaultCorruptException) {
                threw = true
            }
            require(threw) { "Crypto.decrypt did not reject a short blob" }
            "short blob -> null, raw decrypt -> VaultCorruptException"
        }

        return cases
    }

    private fun vault(store: VaultStorage, id: String) = PersonaVault(store, id, TEST_ITERATIONS)

    /**
     * java.io only: java.nio.file.Files exists from API 26 but not every Android 9 OEM image
     * exposes the same subset, and this code has to run on the phone as well as in CI.
     */
    private fun tempDir(): File {
        val base = File(System.getProperty("java.io.tmpdir") ?: System.getProperty("user.dir") ?: ".")
        val dir = File(base, "personas-selftest-" + System.nanoTime())
        check(dir.mkdirs() || dir.isDirectory) { "cannot create " + dir }
        return dir
    }

    private inline fun case(title: String, block: () -> String): Case =
        try {
            Case(title, true, block())
        } catch (e: Throwable) {
            Case(title, false, e.message ?: e.javaClass.simpleName)
        }

    private fun assertEquals(expected: String, actual: String, context: String) {
        if (expected != actual) throw AssertionError("$context: expected <$expected> got <$actual>")
    }
}
