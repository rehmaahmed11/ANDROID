package com.rehma.personas.crypto

import java.io.File

/**
 * Minimal storage abstraction so the crypto core is unit-testable on a plain JVM
 * (no Robolectric, no Android framework) while still writing real files on device.
 */
interface VaultStorage {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
    fun exists(name: String): Boolean
    fun names(): List<String>
}

/** One directory per app install; each persona gets its own set of files inside it. */
class FileVaultStorage(private val root: File) : VaultStorage {
    private fun file(name: String) = File(root, name)

    override fun read(name: String): ByteArray? {
        val f = file(name)
        return if (f.isFile) f.readBytes() else null
    }

    override fun write(name: String, bytes: ByteArray) {
        root.mkdirs()
        // Write-then-rename keeps a crash from leaving a half-written vault behind.
        val tmp = file("$name.tmp")
        tmp.writeBytes(bytes)
        val target = file(name)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    override fun delete(name: String) {
        file(name).delete()
        file("$name.tmp").delete()
    }

    override fun exists(name: String) = file(name).isFile

    override fun names(): List<String> =
        root.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
}

class InMemoryVaultStorage : VaultStorage {
    private val map = sortedMapOf<String, ByteArray>()

    @Synchronized
    override fun read(name: String): ByteArray? = map[name]?.copyOf()

    @Synchronized
    override fun write(name: String, bytes: ByteArray) {
        map[name] = bytes.copyOf()
    }

    @Synchronized
    override fun delete(name: String) {
        map.remove(name)
    }

    @Synchronized
    override fun exists(name: String) = map.containsKey(name)

    @Synchronized
    override fun names(): List<String> = map.keys.toList()
}
