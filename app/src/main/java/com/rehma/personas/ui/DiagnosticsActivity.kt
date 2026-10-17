package com.rehma.personas.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rehma.personas.PersonaApp
import com.rehma.personas.R
import com.rehma.personas.databinding.ActivityDiagnosticsBinding
import com.rehma.personas.report.AdbDiagnostics
import com.rehma.personas.report.IsolationSelfTest
import com.rehma.personas.report.Verdict
import com.rehma.personas.system.DeviceProbe

/**
 * Three evidence panels, in the order they should be read: what the app could determine on its
 * own, what only adb can determine, and whether the isolation primitive actually holds on this
 * handset. Nothing on this screen is a promise; every row is a measurement.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private var lastReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val capabilities = DeviceProbe.probe(this)
        val app = PersonaApp.from(this)
        val header = buildString {
            append("Personas · device report\n")
            append("key wrapper: ").append(app.wrapper.description)
            if (app.usingFallbackWrapper) append("   <-- weaker than advertised, see note")
            append('\n')
            append('\n')
        }
        val local = header + DeviceProbe.render(capabilities)
        binding.localReport.text = local
        binding.adbCommand.text = AdbDiagnostics.BATCH
        lastReport = local

        binding.copyCommandButton.setOnClickListener {
            copy("adb-one-line", AdbDiagnostics.BATCH)
        }
        binding.copyListButton.setOnClickListener {
            copy("adb-commands", AdbDiagnostics.COMMANDS.joinToString("\n\n") { c ->
                "# ${c.first}\n${c.second}"
            })
        }
        binding.evaluateButton.setOnClickListener { evaluate() }
        binding.selfTestButton.setOnClickListener { runSelfTest() }
        binding.copyReportButton.setOnClickListener { copy("personas-report", lastReport) }
    }

    private fun evaluate() {
        val pasted = binding.pasteField.text?.toString().orEmpty()
        if (pasted.isBlank()) {
            Toast.makeText(this, "Paste the output first", Toast.LENGTH_SHORT).show()
            return
        }
        val facts = AdbDiagnostics.parse(pasted)
        val diagnostics = AdbDiagnostics.evaluate(facts)
        binding.verdict.text = buildString {
            if (facts.isEmpty()) {
                append("Nothing recognisable in that paste. Expected lines like '@maxusers' followed " +
                    "by the value, or raw 'Maximum supported users: N' output.\n")
            } else {
                append("Parsed ${facts.size} facts:\n")
                diagnostics.forEach { d ->
                    append(d.verdict.name.padEnd(8))
                        .append(" ").append(d.label).append(" = ").append(d.value).append('\n')
                    append("         ").append(d.meaning).append('\n')
                }
                append('\n').append(AdbDiagnostics.plan(facts))
            }
            append('\n')
            append(if (diagnostics.none { it.verdict == Verdict.UNKNOWN })
                "No unknowns left: this device's constraints are fully characterised."
            else
                "Still unknown: some rows could not be read. Do not treat the summary as verified.")
        }
        lastReport = lastReport + "\n\n--- adb diagnostics ---\n" + binding.verdict.text
    }

    private fun runSelfTest() {
        binding.selfTestButton.isEnabled = false
        binding.selfTestResult.text = "Running 10 cases against the platform crypto provider…"
        Thread {
            val cases = IsolationSelfTest.run()
            val failures = cases.count { !it.passed }
            val text = cases.joinToString("\n") { c ->
                "[${c.mark}] ${c.title}\n         ${c.detail}"
            } + "\n\n${cases.size - failures}/${cases.size} passed on ${android.os.Build.MANUFACTURER} " +
                "${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}" +
                if (failures == 0) "\n\nIsolation holds as documented on this device."
                else "\n\nFAILED on this device - do not rely on the separation claims."
            runOnUiThread {
                binding.selfTestResult.text = text
                binding.selfTestButton.isEnabled = true
                lastReport = lastReport + "\n\n--- isolation self-test ---\n" + text
            }
        }.apply { isDaemon = true; start() }
    }

    private fun copy(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
