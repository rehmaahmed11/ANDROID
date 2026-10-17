package com.rehma.personas.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.rehma.personas.R
import com.rehma.personas.data.Persona

/**
 * Puts one launcher icon per persona on the home screen, each opening straight into that
 * profile's vault.
 *
 * This is the closest legitimate stand-in for a second app icon with a second identity: it
 * needs no permission, but it is a shortcut, not a separate install - notifications and the
 * app list stay shared, which is exactly the boundary Android draws for one app in one user.
 */
object PersonaShortcuts {

    const val ACTION_OPEN_PERSONA = "com.rehma.personas.action.OPEN_PERSONA"
    const val EXTRA_PERSONA = "com.rehma.personas.extra.PERSONA"

    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun pin(context: Context, persona: Persona): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val intent = Intent(ACTION_OPEN_PERSONA)
            .setPackage(context.packageName)
            .setClass(context, MainActivity::class.java)
            .putExtra(EXTRA_PERSONA, persona.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val info = ShortcutInfoCompat.Builder(context, "persona-${persona.id}")
            .setShortLabel(persona.name.take(10))
            .setIcon(IconCompat.createWithResource(context, R.drawable.shortcut_persona))
            .setIntent(intent)
            .build()

        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

        val callback = PendingIntent.getBroadcast(
            context,
            persona.id.hashCode() and 0xFFFF,
            Intent(ACTION_OPEN_PERSONA).setPackage(context.packageName),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        return ShortcutManagerCompat.requestPinShortcut(context, info, callback.intentSender)
    }
}
