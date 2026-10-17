package com.rehma.personas.ui

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rehma.personas.PersonaApp
import com.rehma.personas.R
import com.rehma.personas.databinding.ActivityWorkProfileBinding
import com.rehma.personas.report.AdbDiagnostics
import com.rehma.personas.report.Verdict
import com.rehma.personas.system.DeviceProbe
import com.rehma.personas.system.PersonaAdminReceiver

/**
 * The one route that can produce genuine, system-enforced separation without root: a managed
 * (work) profile, created through Android Enterprise provisioning.
 *
 * This screen reports whether the platform will allow it and then hands the user to the
 * system's own provisioning UI. Personas never becomes device owner on purpose: that would
 * need a factory-reset device and would hand this app control of the whole phone.
 */
class WorkProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.wpStatus.text = readiness()
        binding.provisionButton.setOnClickListener { provision() }
        binding.openUsersSettingsButton.setOnClickListener { openUsersSettings() }
        binding.copyAdbButton.setOnClickListener { copyAdb() }
    }

    private fun dpm(): DevicePolicyManager? = getSystemService(DevicePolicyManager::class.java)

    private fun readiness(): String {
        val managedFeature = runCatching {
            packageManager.hasSystemFeature(DeviceProbe.FEATURE_MANAGED_USERS)
        }.getOrDefault(false)
        val provisioningAllowed = runCatching {
            dpm()?.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        }.getOrNull()
        val alreadyOwner = runCatching { dpm()?.isDeviceOwnerApp(packageName) == true }.getOrDefault(false)
        val profileOwner = runCatching { dpm()?.isProfileOwnerApp(packageName) == true }.getOrDefault(false)
        val capabilities = DeviceProbe.probe(this)
        val ceiling = capabilities.firstOrNull { it.label == "config_multiuserMaximumUsers" }?.value ?: "?"
        val hasAdmin = runCatching {
            packageManager.getReceiverInfo(
                PersonaAdminReceiver.component(this), PackageManager.GET_META_DATA,
            ) != null
        }.getOrDefault(false)

        return buildString {
            line(this, "firmware supports managed profiles", managedFeature,
                "android.software.managed_users")
            line(this, "platform will accept provisioning right now", provisioningAllowed,
                "DevicePolicyManager.isProvisioningAllowed(ACTION_PROVISION_MANAGED_PROFILE)")
            line(this, "device-admin receiver is declared and enabled", hasAdmin, "PersonaAdminReceiver")
            append(if (profileOwner) "READY    this app is already a profile owner; a second profile is not permitted per parent user\n"
            else "n/a      app is ${if (alreadyOwner) "device owner" else "not a device/profile owner"}\n")
            append("\nFramework user ceiling: ").append(ceiling)
            append("\n\nIf any line above says NO, that verdict comes from the platform, not from this app, " +
                "and no setting inside Personas can flip it. A managed profile is a work profile: the " +
                "OS will badge its icons and offer to wipe it as a unit. Deleting it deletes everything " +
                "inside, which is a real downside if you were hoping to use it as a second personal user.")
        }
    }

    private fun line(sb: StringBuilder, label: String, value: Boolean?, how: String) {
        val mark = when (value) {
            true -> "YES"
            false -> "NO "
            null -> "?  "
        }
        sb.append(mark).append("  ").append(label).append("  (").append(how).append(")\n")
    }

    private fun provision() {
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                PersonaAdminReceiver.component(this),
            )
        val component = intent.resolveActivity(packageManager)
        if (component == null) {
            Toast.makeText(this, R.string.wp_no_provisioner, Toast.LENGTH_LONG).show()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PROVISION)
        } catch (e: Exception) {
            Toast.makeText(this, "Provisioning refused: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROVISION) {
            binding.wpStatus.text = readiness() + "\n\nProvisioning dialog returned " +
                (if (resultCode == RESULT_OK) "RESULT_OK - check Settings ▸ Users, or the app list " +
                    "in the new profile, and reinstall Personas there via adb."
                else "a non-success code - the platform refused, most likely because the firmware " +
                    "policy does not permit a third-party app to create a profile.")
        }
    }

    /**
     * Tries the OEM's Users screen. Many Android 9 skins have no launchable entry for it - the
     * whole point of the request that started this project - so failure here is a result, not a
     * bug, and it is reported as one.
     */
    private fun openUsersSettings() {
        val candidates = listOf(
            ComponentName("com.android.settings", "com.android.settings.Settings\$UserSettingsActivity"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$DeviceAdminSettingsActivity"),
            ComponentName("com.android.settings", "com.android.settings.Settings"),
        )
        for (candidate in candidates) {
            val intent = Intent(Intent.ACTION_MAIN).setComponent(candidate)
            val ok = runCatching {
                packageManager.getActivityInfo(candidate, 0)
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return
        }
        startActivity(Intent(Intent.ACTION_SETTINGS))
        Toast.makeText(this, R.string.wp_no_users_screen, Toast.LENGTH_LONG).show()
    }

    private fun copyAdb() {
        val text = buildString {
            append("# what a technician would run; none of it can be done from inside an app\n")
            append(AdbDiagnostics.BATCH)
            append("\n\n# only works when 'pm get-max-users' above reports more than 1\n")
            append("adb shell pm create-user \"Second person\"\n")
            append("adb shell pm list users\n")
            append("adb shell am switch-user <id printed above>\n")
            append("\n# after switching, install this APK for that user too\n")
            append("adb shell pm install-existing --user <id> com.rehma.personas\n")
            append("\n# on a build whose ceiling is 1, the only user Android still creates is a guest\n")
            append("adb shell pm create-user --guest Guest\n")
            append("\n# this app will NOT ask to be device owner; shown so you can see the boundary\n")
            append("adb shell dpm set-device-owner com.rehma.personas/.system.PersonaAdminReceiver\n")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("personas-adb", text))
        Toast.makeText(this, R.string.wp_copied_adb, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_PROVISION = 0x51
    }
}
