package com.rehma.personas.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rehma.personas.PersonaApp
import com.rehma.personas.R
import com.rehma.personas.data.Persona
import com.rehma.personas.databinding.ActivityMainBinding
import com.rehma.personas.databinding.ItemPersonaBinding
import com.rehma.personas.report.Verdict
import com.rehma.personas.system.DeviceProbe
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = PersonaApp.from(this)
    private var pendingOpen: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.addButton.setOnClickListener {
            startActivity(Intent(this, PersonaEditorActivity::class.java))
        }
        binding.diagnosticsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.workProfileButton.setOnClickListener {
            startActivity(Intent(this, WorkProfileActivity::class.java))
        }
        pendingOpen = intent?.getStringExtra(PersonaShortcuts.EXTRA_PERSONA)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpen = intent.getStringExtra(PersonaShortcuts.EXTRA_PERSONA)
    }

    override fun onResume() {
        super.onResume()
        renderDevice()
        renderProfiles()
        consumePendingOpen()
    }

    /** Shortcut entry point: jump straight into the profile the icon belongs to. */
    private fun consumePendingOpen() {
        val id = pendingOpen ?: return
        pendingOpen = null
        val persona = app.store.find(id) ?: return
        open(persona)
    }

    private fun renderDevice() {
        val capabilities = DeviceProbe.probe(this)
        val blocked = capabilities.count { it.verdict == Verdict.BLOCKED }
        val ceiling = capabilities.firstOrNull { it.label == "config_multiuserMaximumUsers" }
        val managed = capabilities.firstOrNull { it.label == DeviceProbe.FEATURE_MANAGED_USERS }
        binding.deviceSummary.text = buildString {
            append(
                when {
                    ceiling?.value == "1" -> "This firmware will create 1 Android user, total. "
                    ceiling?.value == "unreadable" -> "Firmware user ceiling unreadable from an app; "
                    else -> "Firmware ceiling: ${ceiling?.value} users. "
                },
            )
            append(
                if (managed?.verdict == Verdict.ENABLED)
                    "Managed (work) profiles are supported, so one real second space may still be reachable."
                else
                    "Managed profiles look unavailable, so system-level separation is off the table.",
            )
            append(" $blocked of ${capabilities.size} checks came back blocked - details below.")
        }
        binding.deviceDetail.text = DeviceProbe.render(capabilities).trim()
    }

    private fun renderProfiles() {
        val container = binding.profileList
        container.removeAllViews()
        val personas = app.store.all().sortedByDescending { it.lastUsedAt }
        binding.emptyHint.visibility = if (personas.isEmpty()) View.VISIBLE else View.GONE

        personas.forEach { persona ->
            val row = ItemPersonaBinding.inflate(layoutInflater, container, false)
            row.personaName.text = persona.name
            row.avatar.text = persona.initials
            row.avatar.background.mutate().setTint(persona.colorArgb.toInt())

            val when_ = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(persona.lastUsedAt))
            row.personaStatus.text = buildString {
                append(if (persona.pinProtected) "PIN-locked" else "no PIN")
                if (persona.tagline.isNotBlank()) append(" · ").append(persona.tagline)
                append(" · last opened ").append(when_)
            }
            val unlocked = app.session.isUnlocked(persona.id)
            row.personaState.text = when {
                unlocked -> "OPEN"
                persona.pinProtected -> "LOCKED"
                else -> "READY"
            }
            row.personaState.setTextColor(
                if (unlocked) Color.parseColor("#FF1B7F4B") else resources.getColor(R.color.ink_soft, theme),
            )
            row.root.setOnClickListener { open(persona) }
            row.root.setOnLongClickListener {
                showMenu(persona)
                true
            }
            container.addView(row.root)
        }
    }

    private fun open(persona: Persona) {
        if (persona.pinProtected && !app.session.isUnlocked(persona.id)) {
            startActivity(Intent(this, UnlockActivity::class.java).putExtra(Persona.EXTRA_ID, persona.id))
            return
        }
        app.store.touch(persona.id)
        startActivity(
            Intent(this, JournalActivity::class.java)
                .putExtra(Persona.EXTRA_ID, persona.id),
        )
    }

    private fun showMenu(persona: Persona) {
        val unlocked = app.session.isUnlocked(persona.id)
        val options = mutableListOf("Open", "Edit name, colour, PIN")
        if (!unlocked) options += "Unlock with PIN" else options += "Lock now"
        options += "Pin icon to home screen"
        if (persona.pinProtected) options += "Remove PIN"
        options += "Delete profile and its data"

        AlertDialog.Builder(this)
            .setTitle(persona.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Open" -> open(persona)
                    "Edit name, colour, PIN" -> startActivity(
                        Intent(this, PersonaEditorActivity::class.java)
                            .putExtra(Persona.EXTRA_ID, persona.id),
                    )
                    "Unlock with PIN" -> startActivity(
                        Intent(this, UnlockActivity::class.java)
                            .putExtra(Persona.EXTRA_ID, persona.id),
                    )
                    "Lock now" -> {
                        app.session.lock(persona.id)
                        renderProfiles()
                    }
                    "Pin icon to home screen" -> {
                        val ok = PersonaShortcuts.pin(this, persona)
                        Toast.makeText(this, if (ok) R.string.ok_pinned else R.string.ok_no_launcher, Toast.LENGTH_LONG).show()
                    }
                    "Remove PIN" -> startActivity(
                        Intent(this, UnlockActivity::class.java)
                            .putExtra(Persona.EXTRA_ID, persona.id)
                            .putExtra(UnlockActivity.EXTRA_MODE, UnlockActivity.MODE_REMOVE_PIN),
                    )
                    "Delete profile and its data" -> confirmDelete(persona)
                }
            }
            .show()
    }

    private fun confirmDelete(persona: Persona) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${persona.name}?")
            .setMessage("The encrypted vault for this profile is erased from app storage. There is no undo and no backup - the app deliberately opts out of backup.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                app.session.lock(persona.id)
                app.store.delete(persona.id)?.let { app.vault(it.id).wipe() }
                renderProfiles()
            }
            .show()
    }
}
