package com.rehma.personas.system

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * The handle Android Enterprise needs before it will create a managed profile for this app.
 *
 * Declaring a receiver is what makes `ACTION_PROVISION_MANAGED_PROFILE` legal to send; the
 * app never asks to become *device* owner, because that route requires a factory-reset device
 * and an adb command, and would hand this app control over the whole phone.
 */
class PersonaAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Toast.makeText(context, "Managed profile ready for Personas", Toast.LENGTH_LONG).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Personas is no longer the profile owner", Toast.LENGTH_LONG).show()
    }

    companion object {
        fun component(context: Context) =
            android.content.ComponentName(context, PersonaAdminReceiver::class.java)
    }
}
