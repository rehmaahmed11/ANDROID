package com.rehma.personas.data

/**
 * A persona's journal, serialized as `epoch\ttext` lines inside the encrypted blob.
 * Pure codec, so the unit tests can prove that hostile text survives the round trip.
 */
data class Entry(val at: Long, val text: String)

object JournalCodec {

    fun encode(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.at}\t${sanitize(it.text)}" }

    private fun sanitize(text: String) = text.replace('\n', ' ').replace('\t', ' ').trim()

    fun decode(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val at = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            val body = line.substring(tab + 1)
            if (body.isEmpty()) null else Entry(at, body)
        }.toList()
    }
}
