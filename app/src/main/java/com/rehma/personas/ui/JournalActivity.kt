package com.rehma.personas.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.rehma.personas.PersonaApp
import com.rehma.personas.R
import com.rehma.personas.data.Entry
import com.rehma.personas.data.JournalCodec
import com.rehma.personas.data.Persona
import com.rehma.personas.databinding.ActivityJournalBinding
import com.rehma.personas.databinding.ItemEntryBinding
import java.text.DateFormat
import java.util.Date

/**
 * The payload screen: everything typed here is encrypted under this persona's key and stored
 * in one file. Another persona on the same phone has its own file and its own key.
 */
class JournalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJournalBinding
    private val app get() = PersonaApp.from(this)
    private var persona: Persona? = null
    private var key: ByteArray? = null
    private val entries = ArrayList<Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJournalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val target = app.store.find(intent.getStringExtra(Persona.EXTRA_ID))
        if (target == null) {
            finish()
            return
        }
        persona = target
        val vault = app.vault(target.id)
        val resolved = app.session.resolve(vault, target.id, target.pinProtected, target.autoLockSeconds)
        if (resolved == null) {
            // No usable key in memory: send the user back through the PIN gate.
            startActivity(
                Intent(this, UnlockActivity::class.java).putExtra(Persona.EXTRA_ID, target.id),
            )
            finish()
            return
        }
        key = resolved
        val opened = vault.open(resolved)
        if (opened == null && vault.hasData) {
            binding.emptyJournal.setText(R.string.locked_vault)
        } else {
            entries += JournalCodec.decode(opened)
        }

        binding.journalTitle.text = target.name
        binding.journalSubtitle.text = getString(
            R.string.journal_subtitle,
            if (target.pinProtected) "PIN-derived key" else "device key",
            app.wrapper.description,
        )
        binding.addEntryButton.setOnClickListener { addEntry() }
        render()
    }

    override fun onPause() {
        super.onPause()
        persist()
        // Leaving the app starts the auto-lock countdown; keys are dropped when it expires.
        app.session.beginBackgroundLock(persona?.autoLockSeconds ?: 60)
    }

    override fun onResume() {
        super.onResume()
        app.session.cancelBackgroundLock()
        val target = persona ?: return
        val current = app.session.keyFor(target.id)
        if (current != null) {
            entries.clear()
            entries += JournalCodec.decode(app.vault(target.id).open(current))
            render()
        }
    }

    private fun addEntry() {
        val text = binding.entryField.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        entries.add(0, Entry(System.currentTimeMillis(), text))
        binding.entryField.setText("")
        persist()
        render()
    }

    private fun persist() {
        val target = persona ?: return
        val k = key ?: return
        app.vault(target.id).save(JournalCodec.encode(entries), k)
    }

    private fun render() {
        val container = binding.entryList
        container.removeAllViews()
        binding.emptyJournal.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        entries.forEachIndexed { index, entry ->
            val row = ItemEntryBinding.inflate(layoutInflater, container, false)
            row.entryWhen.text = df.format(Date(entry.at)).uppercase()
            row.entryText.text = entry.text
            row.entryDelete.setOnClickListener {
                entries.removeAt(index)
                persist()
                render()
            }
            container.addView(row.root)
        }
    }
}
