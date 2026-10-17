package com.rehma.personas.system

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.res.Resources
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.rehma.personas.report.Verdict

data class Capability(
    val label: String,
    val value: String,
    val verdict: Verdict,
    val note: String,
) {
    val badge: String
        get() = when (verdict) {
            Verdict.ENABLED -> "AVAILABLE"
            Verdict.LIMITED -> "LIMITED"
            Verdict.BLOCKED -> "BLOCKED"
            Verdict.UNKNOWN -> "UNKNOWN"
        }
}

/**
 * Reads whatever the platform is willing to tell an ordinary, unprivileged app about this
 * phone's multi-user policy.
 *
 * Two of these probes use APIs that are not part of the public SDK
 * (`android.os.SystemProperties`, and the framework-res overlay values behind
 * `config_multiuserMaximumUsers`). On Android 9 they are on the "unsupported but warned" list,
 * so they usually work; every single one is wrapped, and a failure is reported as UNKNOWN
 * rather than as a guess. Nothing here needs root, and nothing here can change the device.
 */
object DeviceProbe {

    fun probe(context: Context): List<Capability> = listOf(
        osBuild(),
        multiUserCeiling(),
        multiUserUiFlag(),
        propertyOverride(),
        managedUsersFeature(context),
        currentUserIdentity(context),
        addUserDefaults(context),
        privilegeCeiling(context),
        enterpriseRole(context),
    )

    private fun osBuild(): Capability = Capability(
        "Android build",
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, ${Build.TYPE})",
        if (Build.TYPE == "user") Verdict.LIMITED else Verdict.ENABLED,
        "Build type 'user' means adbd never gets root on this device, so firmware limits cannot " +
            "be edited from a terminal. Only relevant to how much you can work around, not to " +
            "whether the app installs.",
    )

    /**
     * The number the OEM burned into the framework resources. This is *the* switch that
     * "manufacturer disabled multiple users" refers to: UserManager.getMaxSupportedUsers()
     * returns 1 unless config_multiuserMaximumUsers says otherwise.
     */
    private fun multiUserCeiling(): Capability {
        val ceiling = frameworkInt("config_multiuserMaximumUsers")
        return Capability(
            "config_multiuserMaximumUsers",
            ceiling?.toString() ?: "unreadable",
            when {
                ceiling == null -> Verdict.UNKNOWN
                ceiling > 1 -> Verdict.ENABLED
                else -> Verdict.BLOCKED
            },
            "Maximum Android users the system will create, read straight from the firmware " +
                "overlay. 1 = the feature is compiled out; no app can raise it.",
        )
    }

    private fun multiUserUiFlag(): Capability {
        val enabled = frameworkBool("config_enableMultiUserUI")
        return Capability(
            "config_enableMultiUserUI",
            enabled?.toString() ?: "unreadable",
            when {
                enabled == true -> Verdict.ENABLED
                enabled == false -> Verdict.LIMITED
                else -> Verdict.UNKNOWN
            },
            "Controls only whether Settings shows the Users screens. false with a ceiling above 1 " +
                "means the feature exists and merely has no menu entry; false with a ceiling of 1 " +
                "means there is nothing to unhide.",
        )
    }

    private fun propertyOverride(): Capability {
        val value = systemProperty("fw.max_users")
        val ui = systemProperty("fw.show_multiuserui")
        val shown = systemProperty("ro.product.model") // proves the read path works at all
        return Capability(
            "fw.max_users / fw.show_multiuserui",
            when {
                shown == null -> "unreadable (hidden-API read blocked)"
                else -> "fw.max_users=${value ?: "<unset>"} · fw.show_multiuserui=${ui ?: "<unset>"}"
            },
            when {
                shown == null -> Verdict.UNKNOWN
                (value?.toIntOrNull() ?: 1) > 1 -> Verdict.ENABLED
                else -> Verdict.LIMITED
            },
            "These runtime properties override the firmware ceiling (fw.max_users wins when set). " +
                "Setting them needs a write to the property service, i.e. root - listed here only " +
                "to show the current value.",
        )
    }

    private fun managedUsersFeature(context: Context): Capability {
        val pm = context.packageManager
        val has = runCatching { pm.hasSystemFeature(FEATURE_MANAGED_USERS) }.getOrDefault(false)
        return Capability(
            "android.software.managed_users",
            if (has) "present" else "absent",
            if (has) Verdict.ENABLED else Verdict.BLOCKED,
            "Managed (work) profiles are the one form of system-enforced separation a third-party " +
                "app can legitimately trigger, through the Android Enterprise provisioning intent. " +
                "Absent means even that route is closed on this build.",
        )
    }

    private fun currentUserIdentity(context: Context): Capability {
        val um = context.getSystemService(UserManager::class.java)
        val inProfile = runCatching { um?.isManagedProfile == true }.getOrDefault(false)
        // Android assigns uid = userId * 100000 + appId, so the owning user id falls out of
        // our own uid without touching a hidden API.
        val userId = Process.myUid() / 100000
        return Capability(
            "who owns this process",
            "android user id $userId; running ${if (inProfile) "inside a managed profile" else "in the owner profile"}",
            if (inProfile) Verdict.ENABLED else Verdict.LIMITED,
            "Android already sandboxes each user's apps and data. If this app is installed for " +
                "one user only, another user simply does not see it - which is why 'install the " +
                "APK per profile' is a real step in the README and not decoration.",
        )
    }

    private fun addUserDefaults(context: Context): Capability {
        val um = context.getSystemService(UserManager::class.java)
        // Reflective on purpose: whether getUserRestrictions() is callable from a normal app
        // varies by API level, and an unresolved-reference at compile time is worse than a
        // probe that reports UNKNOWN on the one build where it is blocked.
        val restrictions = runCatching {
            val m = UserManager::class.java.getMethod("getUserRestrictions")
            @Suppress("UNCHECKED_CAST")
            (m.invoke(um) as? Bundle)
        }.getOrNull()
        val blocked = restrictions?.containsKey(UserManager.DISALLOW_ADD_USER) == true
        return Capability(
            "DISALLOW_ADD_USER",
            when {
                restrictions == null -> "unreadable (restricted API on this build)"
                blocked -> "set"
                else -> "not set"
            },
            when {
                restrictions == null -> Verdict.UNKNOWN
                blocked -> Verdict.BLOCKED
                else -> Verdict.ENABLED
            },
            "A policy layer can block user creation even when the ceiling allows it. Enterprise " +
                "MDM sets this; some OEM skins do too.",
        )
    }

    /** Why the app cannot call the APIs it would need. Shown, not hidden. */
    private fun privilegeCeiling(context: Context): Capability {
        val create = ContextCompat.checkSelfPermission(context, "android.permission.CREATE_USERS")
        val manage = ContextCompat.checkSelfPermission(context, "android.permission.MANAGE_USERS")
        val granted = create == PackageManager.PERMISSION_GRANTED || manage == PackageManager.PERMISSION_GRANTED
        return Capability(
            "CREATE_USERS / MANAGE_USERS",
            "create=${permissionName(create)} · manage=${permissionName(manage)} (signature|system)",
            if (granted) Verdict.ENABLED else Verdict.BLOCKED,
            "The permissions that guard createUser() and friends. Their protection level is " +
                "signature|system, so `adb pm grant` cannot hand them over either - they require " +
                "the platform signing key. This is the wall this project cannot get past.",
        )
    }

    private fun enterpriseRole(context: Context): Capability {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val deviceOwner = runCatching { dpm?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)
        val profileOwner = runCatching { dpm?.isProfileOwnerApp(context.packageName) == true }.getOrDefault(false)
        return Capability(
            "device-policy role of this app",
            when {
                deviceOwner -> "device owner"
                profileOwner -> "profile owner"
                else -> "none (ordinary app)"
            },
            when {
                deviceOwner -> Verdict.ENABLED
                profileOwner -> Verdict.LIMITED
                else -> Verdict.BLOCKED
            },
            "A device owner can add and remove real Android users on many builds. Becoming one " +
                "requires `adb shell dpm set-device-owner` on a factory-reset device with no " +
                "accounts, which is why the app asks for it instead of assuming it.",
        )
    }

    private fun permissionName(result: Int) = when (result) {
        PackageManager.PERMISSION_GRANTED -> "granted"
        PackageManager.PERMISSION_DENIED -> "denied"
        else -> "undefined"
    }

    private fun frameworkInt(name: String): Int? = runCatching {
        val id = Resources.getSystem().getIdentifier(name, "integer", "android")
        if (id == 0) null else Resources.getSystem().getInteger(id)
    }.getOrNull()

    private fun frameworkBool(name: String): Boolean? = runCatching {
        val id = Resources.getSystem().getIdentifier(name, "bool", "android")
        if (id == 0) null else Resources.getSystem().getBoolean(id)
    }.getOrNull()

    private fun systemProperty(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun render(capabilities: List<Capability>): String = buildString {
        capabilities.forEach { c ->
            append("[").append(c.badge).append("] ").append(c.label).append('\n')
            append("    ").append(c.value).append('\n')
            append("    ").append(c.note).append("\n\n")
        }
    }

    const val FEATURE_MANAGED_USERS = "android.software.managed_users"
}
