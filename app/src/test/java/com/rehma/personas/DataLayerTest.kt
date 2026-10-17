package com.rehma.personas

import com.rehma.personas.crypto.FileVaultStorage
import com.rehma.personas.crypto.InMemoryVaultStorage
import com.rehma.personas.data.Entry
import com.rehma.personas.data.JournalCodec
import com.rehma.personas.data.Persona
import com.rehma.personas.data.PersonaStore
import com.rehma.personas.data.PersonasCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerTest {

    @Test
    fun registryCodecRoundTripsHostileNames() {
        val personas = listOf(
            Persona("a1", "-\npersonas.v1", 0xFF112233, 1, 2, true, 30, "tag = with\tseparators"),
            Persona("b2", "مريم", 0xFF445566, 3, 4, false, 60, ""),
            Persona("c3", "  padded  ", 0xFF778899, 5, 6, false, 0, "🙂"),
        )
        val decoded = PersonasCodec.decode(PersonasCodec.encode(personas))
        assertEquals(3, decoded.size)
        assertEquals(personas, decoded)
    }

    @Test
    fun decoderIgnoresGarbageInsteadOfCrashing() {
        assertTrue(PersonasCodec.decode("").isEmpty())
        assertTrue(PersonasCodec.decode("total nonsense\n-\nid=x").isEmpty())
        assertTrue(PersonasCodec.decode("personas.v1\n-\ngarbage line\nnoequals").isEmpty())
        // A record with only an id still survives: a lost name is recoverable, a lost persona is not.
        val one = PersonasCodec.decode("personas.v1\n-\nid=abc12345")
        assertEquals(1, one.size)
        assertEquals("abc12345", one[0].id)
        assertEquals("Unnamed", one[0].name)
    }

    @Test
    fun emptyIdRecordsAreDropped() {
        val text = "personas.v1\n-\nid=\nname=x\n-\nid=ok1\nname=y"
        val decoded = PersonasCodec.decode(text)
        assertEquals(listOf("ok1"), decoded.map { it.id })
    }

    @Test
    fun storePersistsAcrossInstancesAndSortsByUse() {
        val dir = java.nio.file.Files.createTempDirectory("personas-store").toFile()
        try {
            var clock = 1_000L
            val storage = FileVaultStorage(dir)
            val store = PersonaStore(storage, { clock })
            val first = store.create("First", 0xFF000000, "one")
            clock += 1_000
            val second = store.create("Second", 0xFF000000, "two")
            assertEquals(2, store.all().size)

            val reopened = PersonaStore(storage, { clock })
            // File order is insertion order; PersonaStore.all() does not sort, callers do.
            assertEquals(listOf("First", "Second"), reopened.all().map { it.name })

            clock += 5_000
            reopened.touch(first.id)
            assertEquals(listOf("First", "Second"), reopened.all().sortedByDescending { it.lastUsedAt }.map { it.name })

            assertNotNull(reopened.find(second.id))
            reopened.delete(second.id)
            assertNull(reopened.find(second.id))
            assertEquals(1, reopened.all().size)
            // The file on disk is the source of truth, not the in-memory cache.
            assertEquals(1, PersonaStore(storage, { clock }).all().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lookupByNameIsTrimmedAndCaseInsensitive() {
        val store = PersonaStore(InMemoryVaultStorage(), { 0 })
        store.create("Rehma", 0xFF000000)
        assertNotNull(store.byName("  rehma "))
        assertNull(store.byName("someone else"))
    }

    @Test
    fun storeRefusesBlankNamesAndEnforcesTheCap() {
        val store = PersonaStore(InMemoryVaultStorage(), { 0 })
        var thrown = false
        try {
            store.create("   ", 0xFF000000)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)

        repeat(PersonaStore.MAX_PERSONAS) { store.create("P$it", 0xFF000000) }
        assertTrue(store.isFull())
        thrown = false
        try {
            store.create("one too many", 0xFF000000)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("the cap must be enforced, not advisory", thrown)
    }

    @Test
    fun updateChangesOnlyTheTargetPersona() {
        val store = PersonaStore(InMemoryVaultStorage(), { 100 })
        val a = store.create("A", 0xFF000000)
        store.create("B", 0xFF000000)
        store.update(a.copy(name = "A2", pinProtected = true, autoLockSeconds = 5))
        assertEquals("A2", store.find(a.id)?.name)
        assertTrue(store.find(a.id)?.pinProtected == true)
        assertEquals(2, store.all().size)
        assertEquals(listOf("A2", "B"), store.all().map { it.name }.sorted())
    }

    @Test
    fun deletingAnUnknownIdIsANoOp() {
        val store = PersonaStore(InMemoryVaultStorage(), { 0 })
        store.create("A", 0xFF000000)
        assertNull(store.delete("nope"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun initialsHandleTheEdgeCases() {
        assertEquals("JO", Persona("1", "John O'brien", 0, 0, 0, false, 0, "").initials)
        assertEquals("MA", Persona("1", "mary", 0, 0, 0, false, 0, "").initials)
        assertEquals("?", Persona("1", "   ", 0, 0, 0, false, 0, "").initials)
        assertEquals("AB", Persona("1", "a b c d", 0, 0, 0, false, 0, "").initials)
    }

    @Test
    fun journalCodecIsLineSafe() {
        val entries = listOf(
            Entry(1, "first"),
            Entry(2, "second\nwith newline and\ttab"),
            Entry(3, ""),
        )
        val decoded = JournalCodec.decode(JournalCodec.encode(entries))
        assertEquals(2, decoded.size)
        assertEquals("first", decoded[0].text)
        assertEquals("second with newline and tab", decoded[1].text)
        assertEquals(1L, decoded[0].at)
    }

    @Test
    fun journalCodecToleratesCorruption() {
        assertEquals(emptyList<Entry>(), JournalCodec.decode(null))
        assertEquals(emptyList<Entry>(), JournalCodec.decode(""))
        // A torn line must not take the whole journal down with it.
        val text = "100\tkept\nnotanumber\tbroken\n200\tkept too"
        assertEquals(listOf("kept", "kept too"), JournalCodec.decode(text).map { it.text })
    }

    @Test
    fun fileStorageOverwritesAtomicallyAndNeverLeavesTmp() {
        val dir = java.nio.file.Files.createTempDirectory("personas-storage").toFile()
        try {
            val storage = FileVaultStorage(dir)
            storage.write("x.bin", "one".toByteArray())
            storage.write("x.bin", "two".toByteArray())
            assertEquals("two", String(storage.read("x.bin")!!))
            assertFalse("temp file left behind", storage.exists("x.bin.tmp"))
            storage.delete("x.bin")
            assertNull(storage.read("x.bin"))
            assertFalse(dir.list()!!.any { it.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
