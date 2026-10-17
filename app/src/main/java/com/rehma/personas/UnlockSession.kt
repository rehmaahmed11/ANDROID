package com.rehma.personas

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rehma.personas.crypto.KeyWrapper
import com.rehma.personas.crypto.PersonaVault
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory holder of unlocked persona keys.
 *
 * Keys never touch the disk: the only things written are ciphertext and a salt. A persona stays
 * unlocked for `autoLockSeconds` after the app leaves the foreground, then the key is dropped
 * from memory - so leaving the phone face-up does not leave a vault open until the process is
 * eventually killed.
 */
class UnlockSession {

    private data class Entry(val key: ByteArray, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var lockRunnable: Runnable? = null

    lateinit var deviceWrapper: KeyWrapper

    fun unlock(personaId: String, key: ByteArray, forSeconds: Int) {
        entries[personaId] = Entry(
            key.copyOf(),
            // A zero or negative timeout still keeps the session alive for this foreground
            // visit; the background timer below is what closes it.
            if (forSeconds <= 0) SystemClock.elapsedRealtime() + LONG_ENOUGH
            else SystemClock.elapsedRealtime() + forSeconds * 1000L,
        )
    }

    fun keyFor(personaId: String): ByteArray? {
        val entry = entries[personaId] ?: return null
        if (entry.expiresAt > SystemClock.elapsedRealtime()) return entry.key
        entries.remove(personaId)
        return null
    }

    fun isUnlocked(personaId: String) = keyFor(personaId) != null

    fun lock(personaId: String) {
        entries.remove(personaId)
    }

    fun lockAll() {
        entries.keys.toList().forEach { entries.remove(it) }
    }

    /**
     * @return a usable key, or null when the persona is PIN-locked and not yet unlocked.
     * A persona without a PIN is opened silently using its wrapped device key.
     */
    fun resolve(vault: PersonaVault, personaId: String, pinProtected: Boolean, autoLockSeconds: Int): ByteArray? {
        keyFor(personaId)?.let { return it }
        if (pinProtected) return null
        return vault.deviceKey(deviceWrapper).also { unlock(personaId, it, autoLockSeconds) }
    }

    fun beginBackgroundLock(seconds: Int) {
        cancelBackgroundLock()
        val delay = if (seconds <= 0) LONG_ENOUGH else seconds * 1000L
        lockRunnable = Runnable { lockAll() }.also { handler.postDelayed(it, delay) }
    }

    fun cancelBackgroundLock() {
        lockRunnable?.let(handler::removeCallbacks)
        lockRunnable = null
    }

    private companion object {
        /** Effectively "until the process dies", used when the user disables auto-lock. */
        const val LONG_ENOUGH = 24L * 60 * 60 * 1000
    }
}
