package com.rehma.personas

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.rehma.personas.crypto.FileVaultStorage
import com.rehma.personas.crypto.KeyWrapper
import com.rehma.personas.crypto.KeyWrappers
import com.rehma.personas.crypto.PersonaVault
import com.rehma.personas.data.PersonaStore
import java.io.File

class PersonaApp : Application() {

    lateinit var storage: FileVaultStorage
        private set
    lateinit var store: PersonaStore
        private set
    lateinit var wrapper: KeyWrapper
        private set
    lateinit var session: UnlockSession
        private set

    /** True when the platform keystore was unusable and the sandbox-only fallback won. */
    var usingFallbackWrapper: Boolean = false
        private set

    var foregroundActivities: Int = 0
        private set

    override fun onCreate() {
        super.onCreate()
        val root = File(filesDir, "personas")
        storage = FileVaultStorage(root)
        store = PersonaStore(storage)
        val (chosen, note) = KeyWrappers.best(filesDir)
        wrapper = chosen
        usingFallbackWrapper = note.startsWith("no usable")
        session = UnlockSession().apply { deviceWrapper = chosen }

        // One place that decides when a persona is locked again: the last visible activity
        // disappearing, not the process dying (which Android may never do).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                foregroundActivities++
                session.cancelBackgroundLock()
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                if (foregroundActivities > 0) foregroundActivities--
                if (foregroundActivities == 0) {
                    val delay = store.all().maxOfOrNull { it.autoLockSeconds } ?: 60
                    session.beginBackgroundLock(delay)
                }
            }

            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    fun vault(personaId: String): PersonaVault = PersonaVault(storage, personaId)

    companion object {
        fun from(context: Context): PersonaApp = context.applicationContext as PersonaApp
    }
}
