package com.rehma.personas.report

/**
 * Turns the output of `scripts/multiuser-check.(sh|bat)` into a verdict about what this
 * particular phone will actually allow.
 *
 * Why parse text instead of trusting an in-app API call: the Android APIs that report the
 * multi-user policy (`UserManager.getMaxSupportedUsers`, `getUserCount`, `DISALLOW_ADD_USER`)
 * are @hide and gated behind `signature|system` permissions, so a normal app cannot read the
 * answer. Only the shell can. The app therefore asks for evidence instead of guessing.
 */
enum class Verdict { ENABLED, LIMITED, BLOCKED, UNKNOWN }

data class Diagnostic(
    val label: String,
    val value: String,
    val verdict: Verdict,
    val meaning: String,
)

object AdbDiagnostics {

    /** `@tag` on its own line, value on the next. Parsed by [parse]. */
    val BATCH: String = "adb shell 'echo @model; getprop ro.product.model; " +
        "echo @release; getprop ro.build.version.release; " +
        "echo @sdk; getprop ro.build.version.sdk; " +
        "echo @buildtype; getprop ro.build.type; " +
        "echo @maxusers; pm get-max-users | tail -n 1; " +
        "echo @maxusersprop; getprop fw.max_users; " +
        "echo @multiui; getprop fw.show_multiuserui; " +
        "echo @managed; pm list features | grep -c managed_users; " +
        "echo @userlines; pm list users | grep -c UserInfo'"

    private val TAGS = setOf(
        "model", "release", "sdk", "buildtype", "maxusers",
        "maxusersprop", "multiui", "userlines", "managed",
    )

    /**
     * Accepts the tagged script output and, with fallbacks, loose terminal noise: pasting a
     * screenshot transcript of `pm get-max-users` should still move the needle.
     */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var pending: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val tagged = Regex("^@(\\w+)\\s*(.*)$").find(line)
            if (tagged != null) {
                val key = tagged.groupValues[1]
                val value = tagged.groupValues.getOrNull(2)?.trim().orEmpty()
                if (key in TAGS) {
                    if (value.isNotEmpty()) out[key] = value else pending = key
                }
                continue
            }
            if (pending != null) {
                out[pending] = line
                pending = null
                continue
            }

            when {
                "Maximum supported users" in line -> out["maxusers"] = line.substringAfter(':').trim()
                line.startsWith("UserInfo{") ->
                    out["userlines"] = ((out["userlines"]?.toIntOrNull() ?: 0) + 1).toString()
                "android.software.managed_users" in line -> out["managed"] = "1"
                Regex("^(user|userdebug|eng)$").matches(line) -> out["buildtype"] = line
                Regex("^\\d+$").matches(line) -> Unit // bare numbers are meaningless without a tag
                else -> out.putIfAbsent("model", line.take(40))
            }
        }
        return out
    }

    fun evaluate(facts: Map<String, String>): List<Diagnostic> {
        val list = ArrayList<Diagnostic>()

        val maxUsers = facts["maxusers"]?.trim()?.toIntOrNull()
        list += Diagnostic(
            "pm get-max-users",
            facts["maxusers"] ?: "not measured",
            when {
                maxUsers == null -> Verdict.UNKNOWN
                maxUsers > 1 -> Verdict.ENABLED
                else -> Verdict.BLOCKED
            },
            "How many Android users the firmware will create at all. 1 means the system server " +
                "refuses every secondary-user request, from any caller, including adb shell.",
        )

        val managed = facts["managed"]?.trim()?.toIntOrNull()
        list += Diagnostic(
            "android.software.managed_users",
            when (managed) {
                null -> "not measured"
                0 -> "absent"
                else -> "present"
            },
            when {
                managed == null -> Verdict.UNKNOWN
                managed > 0 -> Verdict.ENABLED
                else -> Verdict.BLOCKED
            },
            "Whether the firmware supports managed (work) profiles. This is the one kind of " +
                "system-enforced isolation a third-party app may legitimately drive, via the " +
                "Android Enterprise provisioning intent.",
        )

        val buildType = facts["buildtype"]
        list += Diagnostic(
            "ro.build.type",
            buildType ?: "not measured",
            when (buildType) {
                "user" -> Verdict.BLOCKED
                "userdebug", "eng" -> Verdict.ENABLED
                else -> Verdict.UNKNOWN
            },
            "On a 'user' build adbd drops root at startup, so `adb root` and `setprop` are " +
                "unavailable and the firmware limits cannot be edited from a terminal.",
        )

        val propBits = listOfNotNull(
            facts["maxusersprop"]?.let { "fw.max_users=$it" },
            facts["multiui"]?.let { "fw.show_multiuserui=$it" },
        )
        list += Diagnostic(
            "OEM kill-switches",
            propBits.ifEmpty { listOf("not measured (empty = unset, AOSP default 1 / false)") }.joinToString(", "),
            when {
                (facts["maxusersprop"]?.toIntOrNull() ?: 1) > 1 -> Verdict.ENABLED
                propBits.isEmpty() -> Verdict.UNKNOWN
                else -> Verdict.LIMITED
            },
            "fw.max_users and fw.show_multiuserui are the two properties that hide the feature. " +
                "They are read from /system/build.prop once at boot; changing them needs a " +
                "writable /system, i.e. root or a modified firmware.",
        )

        val users = facts["userlines"]?.trim()?.toIntOrNull()
        list += Diagnostic(
            "users already on the device",
            users?.toString() ?: "not measured",
            when {
                users == null -> Verdict.UNKNOWN
                users > 1 -> Verdict.ENABLED
                else -> Verdict.LIMITED
            },
            "If this is above 1 the phone already runs several users and only the switcher UI is " +
                "hidden, which is a much easier problem than a locked-down limit.",
        )

        return list
    }

    /** Plain-language summary of which route is open on this device. */
    fun plan(facts: Map<String, String>): String {
        val maxUsers = facts["maxusers"]?.trim()?.toIntOrNull()
        val managed = facts["managed"]?.trim()?.toIntOrNull()
        val sb = StringBuilder()
        when {
            maxUsers == null -> sb.append("Nothing verified yet: run the diagnostic from a " +
                "computer with adb and paste the output. Until then treat every claim below as " +
                "untested. ")
            maxUsers > 1 -> sb.append("Your firmware allows $maxUsers users, so the system can " +
                "create them; only the Settings entry is hidden. Use the system route and skip " +
                "app-level profiles. ")
            else -> sb.append("Verified limit: this firmware creates exactly 1 user. Real " +
                "secondary users cannot be enabled by any app without root, because the check " +
                "lives in the system server, not in the Settings UI. ")
        }
        sb.append(when (managed) {
            null -> "Managed-profile support is unknown, so the Work profile route is unverified."
            0 -> "Managed profiles are stripped from this firmware too, which removes the last " +
                "non-root system isolation option. Per-persona encrypted profiles in this app are " +
                "the remaining route."
            else -> "Managed profiles appear available. The Work profile route in this app can " +
                "give system-enforced app and data separation, the closest legitimate equivalent " +
                "of a second user on a stock phone."
        })
        return sb.toString()
    }
}
