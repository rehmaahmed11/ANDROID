package com.rehma.personas.data

import com.rehma.personas.crypto.VaultStorage
import java.util.UUID

/**
 * Holds the persona registry in one small file inside app-private storage.
 *
 * This is *app* data, not system data: Android gives a third-party app no API to enumerate
 * or touch the OS user list, so the list of people who share this phone lives here.
 */
class PersonaStore(
    private val storage: VaultStorage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val REGISTRY = "registry.v1"
        const val MAX_PERSONAS = 8
    }

    private var cache: MutableList<Persona>? = null

    fun all(): List<Persona> = cache ?: load().also { cache = it.toMutableList() }

    fun find(id: String?): Persona? = id?.let { want -> all().firstOrNull { it.id == want } }

    fun byName(name: String): Persona? =
        all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    private fun load(): List<Persona> =
        PersonasCodec.decode(storage.read(REGISTRY)?.toString(Charsets.UTF_8) ?: "")

    private fun persist(list: List<Persona>) {
        cache = list.toMutableList()
        storage.write(REGISTRY, PersonasCodec.encode(list).toByteArray(Charsets.UTF_8))
    }

    fun create(name: String, colorArgb: Long, tagline: String = ""): Persona {
        require(name.isNotBlank()) { "name is required" }
        require(all().size < MAX_PERSONAS) { "profile limit reached" }
        val p = Persona(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = name.trim(),
            colorArgb = colorArgb,
            createdAt = now(),
            lastUsedAt = now(),
            pinProtected = false,
            autoLockSeconds = 60,
            tagline = tagline.trim(),
        )
        persist(all() + p)
        return p
    }

    fun update(persona: Persona) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == persona.id }
        if (index >= 0) {
            list[index] = persona
            persist(list)
        }
    }

    fun touch(id: String) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(lastUsedAt = now())
            persist(list)
        }
    }

    /** Returns the deleted persona so the caller can wipe its vault files too. */
    fun delete(id: String): Persona? {
        val victim = find(id) ?: return null
        persist(all().filterNot { it.id == id })
        return victim
    }

    fun isFull(): Boolean = all().size >= MAX_PERSONAS
}
