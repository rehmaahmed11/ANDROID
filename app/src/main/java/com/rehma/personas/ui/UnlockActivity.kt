package com.rehma.personas.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.rehma.personas.PersonaApp
import com.rehma.personas.R
import com.rehma.personas.crypto.WrongPassphraseException
import com.rehma.personas.data.Persona
import com.rehma.personas.databinding.ActivityUnlockBinding

/**
 * Asks for a PIN and, on success, puts the derived key in memory only.
 *
 * There is no "forgot PIN". The key is derived from the passphrase and never stored, so a
 * recovery option would mean a backdoor everyone else could use too.
 */
class UnlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_OPEN = "open"
        const val MODE_REMOVE_PIN = "remove_pin"
    }

    private lateinit var binding: ActivityUnlockBinding
    private val app get() = PersonaApp.from(this)
    private var persona: Persona? = null
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(Persona.EXTRA_ID)
        val target = app.store.find(id)
        if (target == null) {
            toast(R.string.err_unknown)
            finish()
            return
        }
        persona = target
        binding.unlockName.text =
            if (mode() == MODE_REMOVE_PIN) "Confirm the current PIN to remove it" else target.name
        binding.avatar.text = target.initials
        binding.avatar.background.mutate().setTint(target.colorArgb.toInt())

        binding.unlockButton.setOnClickListener { submit() }
        binding.pinField.requestFocus()
    }

    private fun mode() = intent.getStringExtra(EXTRA_MODE) ?: MODE_OPEN

    private fun submit() {
        val target = persona ?: return
        val pin = binding.pinField.text?.toString().orEmpty()
        if (pin.length < 4) {
            fail(R.string.err_short_pin)
            return
        }
        val vault = app.vault(target.id)
        try {
            val key = vault.unlockWithPin(pin)
            if (mode() == MODE_REMOVE_PIN) {
                val afterRemoval = vault.removePin(pin, app.wrapper)
                app.store.update(target.copy(pinProtected = false))
                app.session.unlock(target.id, afterRemoval, target.autoLockSeconds)
                toast(R.string.ok)
                finish()
                return
            }
            app.session.unlock(target.id, key, target.autoLockSeconds)
            startActivity(
                Intent(this, JournalActivity::class.java).putExtra(Persona.EXTRA_ID, target.id),
            )
            finish()
        } catch (e: WrongPassphraseException) {
            attempts++
            // No counter-based lockout: this app has no server to reset it, and bricking a
            // family member out of their own journal is a worse outcome than a slow attack.
            fail(if (attempts >= 3) R.string.err_slow_down else R.string.err_wrong_pin)
        } catch (e: Exception) {
            fail(R.string.err_no_pin)
        }
    }

    private fun fail(resId: Int) {
        binding.unlockError.visibility = View.VISIBLE
        binding.unlockError.setText(resId)
        binding.pinField.setText("")
    }

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
}
