package com.rehma.personas.data

/**
 * One app-level persona. Pure data + a tolerant line codec, so the registry logic can be
 * exercised on a desktop JVM in CI rather than only inside an emulator.
 */
data class Persona(
    val id: String,
    var name: String,
    var colorArgb: Long,
    var createdAt: Long,
    var lastUsedAt: Long,
    var pinProtected: Boolean,
    var autoLockSeconds: Int,
    var tagline: String,
) {
    val initials: String
        get() {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
            }
        }

    companion object {
        /** Extra key used by every activity that operates on one persona. */
        const val EXTRA_ID = "com.rehma.personas.extra.PERSONA_ID"
    }
}
