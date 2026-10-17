package com.rehma.personas.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rehma.personas.PersonaApp
import com.rehma.personas.R
import com.rehma.personas.crypto.VaultCorruptException
import com.rehma.personas.data.Persona
import com.rehma.personas.databinding.ActivityPersonaEditorBinding

/**
 * Create or edit a persona: name, label, colour, optional PIN, auto-lock interval.
 *
 * The PIN is the only field with real consequences - enrolling it re-encrypts the persona's
 * vault under a key derived from the passphrase and deletes the previous key material, so a
 * lost PIN means lost data. Everything else is a label.
 */
class PersonaEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonaEditorBinding
    private val app get() = PersonaApp.from(this)
    private var persona: Persona? = null
    private var colour = COLOURS[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPersonaEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        persona = app.store.find(intent.getStringExtra(Persona.EXTRA_ID))
        val editing = persona != null
        binding.title.text = if (editing) "Edit profile" else "New profile"
        binding.deleteButton.visibility = if (editing) View.VISIBLE else View.GONE

        persona?.let { p ->
            binding.nameField.setText(p.name)
            binding.taglineField.setText(p.tagline)
            binding.autoLockField.setText(p.autoLockSeconds.toString())
            colour = COLOURS.firstOrNull { it == p.colorArgb.toInt() } ?: COLOURS[0]
            binding.pinField.hint = if (p.pinProtected) "leave empty to keep the current PIN" else "leave empty for no PIN"
        }

        buildSwatches()
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

    private fun buildSwatches() {
        val row = binding.swatches
        row.removeAllViews()
        COLOURS.forEach { c ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(if (c == colour) dp(3) else 0, 0xFFFFFFFF.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(10)
                    gravity = Gravity.CENTER_VERTICAL
                }
                isClickable = true
                setOnClickListener {
                    colour = c
                    buildSwatches()
                }
            }
            row.addView(swatch)
        }
    }

    private fun save() {
        val name = binding.nameField.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toast(R.string.err_name)
            return
        }
        if (app.store.isFull() && persona == null) {
            toast(R.string.err_full)
            return
        }
        val autoLock = binding.autoLockField.text?.toString()?.toIntOrNull()?.coerceIn(0, 3600) ?: 60
        val pin = binding.pinField.text?.toString().orEmpty()

        val saved = if (persona == null) {
            app.store.create(name, colour.toLong() and 0xFFFFFFFFL, binding.taglineField.text?.toString().orEmpty())
                .also { it.autoLockSeconds = autoLock }
        } else {
            persona!!.copy(
                name = name,
                colorArgb = colour.toLong() and 0xFFFFFFFFL,
                tagline = binding.taglineField.text?.toString().orEmpty(),
                autoLockSeconds = autoLock,
            ).also { app.store.update(it) }
        }
        app.store.update(saved)

        if (pin.isNotEmpty()) {
            if (pin.length < 4) {
                toast(R.string.err_short_pin)
                return
            }
            val vault = app.vault(saved.id)
            if (vault.hasData && !app.session.isUnlocked(saved.id) && saved.pinProtected) {
                toast(R.string.err_pin_first)
                return
            }
            try {
                vault.enrollPin(pin, app.session.keyFor(saved.id))
                app.session.unlock(saved.id, vault.unlockWithPin(pin), autoLock)
                app.store.update(saved.copy(pinProtected = true))
            } catch (e: Exception) {
                toast(R.string.err_corrupt)
                return
            }
        }

        setResult(RESULT_OK)
        finish()
    }

    private fun confirmDelete() {
        val target = persona ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete ${target.name}?")
            .setMessage("Removes the profile and its encrypted vault. Not recoverable.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                app.session.lock(target.id)
                app.store.delete(target.id)
                app.vault(target.id).wipe()
                finish()
            }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private companion object {
        val COLOURS = intArrayOf(
            0xFF3D5AFE.toInt(), 0xFF00897B.toInt(), 0xFFC2185B.toInt(), 0xFFF9A825.toInt(),
        )
    }
}
