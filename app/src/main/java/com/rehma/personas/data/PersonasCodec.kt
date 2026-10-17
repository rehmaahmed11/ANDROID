package com.rehma.personas.data

/**
 * Registry format: a `personas.v1` header line, then one record per persona, records separated
 * by a bare `-` line and fields written as `key=percent-encoded-value`.
 *
 * Deliberately not JSON: this file must survive a hand-edit, a power cut mid-write, and a future
 * field addition, and a codec this small does that more honestly than a parser I would have to
 * trust. Values are percent-encoded so that a persona legitimately named "-" or "personas.v1"
 * cannot forge a record boundary.
 */
object PersonasCodec {

    private const val HEADER = "personas.v1"

    fun encode(personas: List<Persona>): String {
        val sb = StringBuilder(HEADER)
        personas.forEach { p ->
            sb.append('\n').append('-').append('\n')
            sb.append("id=").append(encodeValue(p.id)).append('\n')
            sb.append("name=").append(encodeValue(p.name)).append('\n')
            sb.append("color=").append(p.colorArgb.toString(16)).append('\n')
            sb.append("created=").append(p.createdAt).append('\n')
            sb.append("lastUsed=").append(p.lastUsedAt).append('\n')
            sb.append("pin=").append(if (p.pinProtected) 1 else 0).append('\n')
            sb.append("autolock=").append(p.autoLockSeconds).append('\n')
            sb.append("tagline=").append(encodeValue(p.tagline))
        }
        return sb.toString()
    }

    /** Never throws: a corrupt line is skipped, a corrupt file yields an empty registry. */
    fun decode(text: String): List<Persona> {
        if (text.isBlank() || !text.lineSequence().first().startsWith("personas.")) return emptyList()
        val out = ArrayList<Persona>()
        var current: MutableMap<String, String>? = null
        for (raw in text.lineSequence().drop(1)) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line == "-" -> {
                    current?.let { parse(it)?.let(out::add) }
                    current = HashMap()
                }
                current != null -> {
                    val eq = line.indexOf('=')
                    if (eq > 0) current[line.substring(0, eq)] = line.substring(eq + 1)
                }
            }
        }
        current?.let { parse(it)?.let(out::add) }
        return out
    }

    private fun parse(fields: Map<String, String>): Persona? {
        val id = fields["id"]?.let { decodeValue(it) } ?: return null
        if (id.isEmpty()) return null
        return Persona(
            id = id,
            name = fields["name"]?.let { decodeValue(it) } ?: "Unnamed",
            colorArgb = fields["color"]?.toLongOrNull(16) ?: 0xFF3D5AFE,
            createdAt = fields["created"]?.toLongOrNull() ?: 0L,
            lastUsedAt = fields["lastUsed"]?.toLongOrNull() ?: 0L,
            pinProtected = fields["pin"] == "1",
            autoLockSeconds = fields["autolock"]?.toIntOrNull() ?: 60,
            tagline = fields["tagline"]?.let { decodeValue(it) } ?: "",
        )
    }

    private const val SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    internal fun encodeValue(value: String): String {
        val sb = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (SAFE.indexOf(c) >= 0) sb.append(c) else sb.append('%').append("%02X".format(b))
        }
        return sb.toString()
    }

    internal fun decodeValue(value: String): String {
        // ByteArrayOutputStream rather than ArrayList<Byte>: a ByteArray is not a
        // Collection<Byte>, so there is nothing sensible to addAll() into here.
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length -> {
                    val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte == null) {
                        // Not an escape after all: keep the remainder verbatim rather than
                        // inventing a replacement character for a hand-edited file.
                        out.write(value.substring(i).toByteArray(Charsets.UTF_8))
                        return out.toString("UTF-8")
                    }
                    out.write(byte)
                    i += 3
                }
                else -> {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
        }
        return out.toString("UTF-8")
    }
}
