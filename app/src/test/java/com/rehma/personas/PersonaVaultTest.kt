package com.rehma.personas

import com.rehma.personas.crypto.InMemoryVaultStorage
import com.rehma.personas.crypto.IdentityKeyWrapper
import com.rehma.personas.crypto.PersonaVault
import com.rehma.personas.crypto.WrongPassphraseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The isolation guarantee, as an executable specification. These run in CI on every push, so
 * "profiles are separated" is a tested property rather than an adjective in a README.
 */
class PersonaVaultTest {

    /** 2000 iterations keeps the suite under a second; production uses 150 000. */
    private fun vault(storage: InMemoryVaultStorage, id: String) = PersonaVault(storage, id, 2_000)

    @Test
    fun saveAndOpenRoundTrip() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        val key = vault.deviceKey(IdentityKeyWrapper)
        vault.save("a note", key)
        assertEquals("a note", vault.open(key))
    }

    @Test
    fun emptySaveDeletesTheVault() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        val key = vault.deviceKey(IdentityKeyWrapper)
        vault.save("a note", key)
        assertTrue(vault.hasData)
        vault.save("", key)
        assertFalse("saving empty text must remove the file", vault.hasData)
        assertNull(vault.open(key))
    }

    @Test
    fun personasDoNotShareFiles() {
        val storage = InMemoryVaultStorage()
        val alice = vault(storage, "alice")
        val bob = vault(storage, "bob")
        alice.save("private", alice.deviceKey(IdentityKeyWrapper))
        bob.deviceKey(IdentityKeyWrapper)
        assertEquals(listOf("p-alice.key", "p-alice.vault"), alice.files().sorted())
        assertEquals(listOf("p-bob.key"), bob.files().sorted())
    }

    @Test
    fun deviceKeyIsStableAcrossInstances() {
        val storage = InMemoryVaultStorage()
        val key1 = vault(storage, "alice").deviceKey(IdentityKeyWrapper)
        val key2 = vault(storage, "alice").deviceKey(IdentityKeyWrapper)
        assertNotEquals(0, key1.size)
        assertTrue("wrapped key must be re-derivable after a process restart", key1.contentEquals(key2))
    }

    @Test
    fun wrongPinIsRejectedBeforeDataIsTouched() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        val plain = vault.deviceKey(IdentityKeyWrapper)
        vault.save("secret", plain)
        vault.enrollPin("correct horse", plain)

        var thrown = false
        try {
            vault.unlockWithPin("wrong horse")
        } catch (e: WrongPassphraseException) {
            thrown = true
        }
        assertTrue("a wrong PIN must not return a key", thrown)
        assertEquals("secret", vault.open(vault.unlockWithPin("correct horse")))
    }

    @Test
    fun enrollingPinReEncryptsAndDiscardsTheWrappedKey() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        val before = vault.deviceKey(IdentityKeyWrapper)
        vault.save("keep me", before)
        vault.enrollPin("pin-1234", before)

        val after = vault.unlockWithPin("pin-1234")
        assertFalse("device key must not survive PIN enrolment", storage.exists("p-alice.key"))
        assertNotEquals(before.toList(), after.toList())
        assertEquals("keep me", vault.open(after))
        // The pre-PIN key is now useless: that is the point of the rotation.
        assertNull(vault.open(before))
    }

    @Test
    fun removingPinRestoresAccessUnderADeviceKey() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        val first = vault.deviceKey(IdentityKeyWrapper)
        vault.save("data", first)
        vault.enrollPin("pin-1234", first)

        val pinKey = vault.unlockWithPin("pin-1234")
        val afterRemoval = vault.removePin("pin-1234", IdentityKeyWrapper)
        assertFalse(vault.hasPin)
        assertEquals("data", vault.open(afterRemoval))
        assertNull("the old PIN-derived key must stop working", vault.open(pinKey))
    }

    @Test
    fun enrollPinRefusesToOrphanExistingData() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        vault.save("data", vault.deviceKey(IdentityKeyWrapper))
        var thrown = false
        try {
            vault.enrollPin("pin-1234", null)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("enrolling without the current key must fail loudly", thrown)
        // ...and the data is still reachable with the original key.
        assertEquals("data", vault.open(vault.deviceKey(IdentityKeyWrapper)))
    }

    @Test
    fun shortPinIsRejected() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        var thrown = false
        try {
            vault.enrollPin("123", vault.deviceKey(IdentityKeyWrapper))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun wipeRemovesEveryTrace() {
        val storage = InMemoryVaultStorage()
        val vault = vault(storage, "alice")
        vault.save("data", vault.deviceKey(IdentityKeyWrapper))
        vault.enrollPin("pin-1234", vault.deviceKey(IdentityKeyWrapper))
        assertTrue(vault.files().isNotEmpty())
        vault.wipe()
        assertTrue(vault.files().isEmpty())
        assertEquals(emptyList<String>(), storage.names())
    }

    @Test
    fun blobsAreNotInterchangeableBetweenPersonas() {
        val storage = InMemoryVaultStorage()
        val alice = vault(storage, "alice")
        val bob = vault(storage, "bob")
        val aliceKey = alice.deviceKey(IdentityKeyWrapper)
        alice.save("alice only", aliceKey)
        // Same key material, different owner id: the AAD has to be what stops this.
        storage.write("p-bob.vault", storage.read("p-alice.vault")!!)
        val bob2 = vault(storage, "bob")
        assertNull("persona B accepted persona A's ciphertext", bob2.open(aliceKey))
    }
}
