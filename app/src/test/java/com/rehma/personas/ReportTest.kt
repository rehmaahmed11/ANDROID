package com.rehma.personas

import com.rehma.personas.report.AdbDiagnostics
import com.rehma.personas.report.IsolationSelfTest
import com.rehma.personas.report.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {

    /**
     * The on-device self-test is the thing that makes the isolation claim checkable by the
     * user, so it has to pass on the JVM too - same classes, same provider family.
     */
    @Test
    fun isolationSelfTestPassesCleanly() {
        val cases = IsolationSelfTest.run()
        val failures = cases.filterNot { it.passed }
        assertEquals("failing cases: $failures", 0, failures.size)
        assertTrue("the self-test must actually run cases", cases.size >= 10)
    }

    @Test
    fun taggedScriptOutputIsParsed() {
        val output = """
            @model
            SM-A750FN
            @release
            9
            @sdk
            28
            @buildtype
            user
            @maxusers
            Maximum supported users: 1
            @maxusersprop

            @multiui

            @managed
            0
            @userlines
            1
        """.trimIndent()
        val facts = AdbDiagnostics.parse(output)
        assertEquals("SM-A750FN", facts["model"])
        assertEquals("28", facts["sdk"])
        assertEquals("user", facts["buildtype"])
        assertEquals("1", facts["maxusers"])
        assertEquals("0", facts["managed"])
        // Empty values after a tag must not steal the next tag's value.
        assertFalse("blank line must not be captured", facts.containsKey("multiui"))
    }

    @Test
    fun inlineTagFormAlsoParses() {
        val facts = AdbDiagnostics.parse("@maxusers 4\n@managed 1")
        assertEquals("4", facts["maxusers"])
        assertEquals("1", facts["managed"])
    }

    @Test
    fun rawTerminalOutputAlsoParses() {
        val facts = AdbDiagnostics.parse(
            """
            $ adb shell pm get-max-users
            Maximum supported users: 1
            $ adb shell pm list users
            Users:
            ${'\t'}UserInfo{0:Owner:13} running
            ${'\t'}UserInfo{10:Guest:404} partial
            $ adb shell pm list features
            feature:android.software.managed_users
            """.trimIndent(),
        )
        assertEquals("1", facts["maxusers"])
        assertEquals("2", facts["userlines"])
        assertEquals("1", facts["managed"])
    }

    @Test
    fun lockedDownDeviceIsReportedBlocked() {
        val facts = mapOf("maxusers" to "1", "managed" to "0", "buildtype" to "user", "userlines" to "1")
        val diagnostics = AdbDiagnostics.evaluate(facts)
        assertEquals(Verdict.BLOCKED, diagnostics.first { it.label == "pm get-max-users" }.verdict)
        assertEquals(Verdict.BLOCKED, diagnostics.first { it.label == "android.software.managed_users" }.verdict)
        assertEquals(Verdict.BLOCKED, diagnostics.first { it.label == "ro.build.type" }.verdict)
        val plan = AdbDiagnostics.plan(facts)
        assertTrue(plan, plan.contains("exactly 1 user"))
        assertTrue(plan, plan.contains("stripped"))
    }

    @Test
    fun ceilingAboveOneIsReportedEnabled() {
        val facts = mapOf("maxusers" to "4", "managed" to "1", "buildtype" to "user", "userlines" to "1")
        val diagnostics = AdbDiagnostics.evaluate(facts)
        assertEquals(Verdict.ENABLED, diagnostics.first { it.label == "pm get-max-users" }.verdict)
        assertEquals(Verdict.ENABLED, diagnostics.first { it.label == "android.software.managed_users" }.verdict)
        val plan = AdbDiagnostics.plan(facts)
        assertTrue(plan, plan.contains("allows 4 users"))
    }

    @Test
    fun nothingPastedStaysUnknownAndSaysSo() {
        val diagnostics = AdbDiagnostics.evaluate(emptyMap())
        assertTrue(
            "every row must be UNKNOWN before any measurement exists",
            diagnostics.all { it.verdict == Verdict.UNKNOWN },
        )
        val plan = AdbDiagnostics.plan(emptyMap())
        assertTrue(plan, plan.contains("Nothing verified yet"))
        assertFalse(plan, plan.contains("verified limit"))
    }

    @Test
    fun unparsableValueIsNotSilentlyTreatedAsBlocked() {
        val facts = mapOf("maxusers" to "Maximum supported users: ?")
        assertEquals(Verdict.UNKNOWN, AdbDiagnostics.evaluate(facts).first { it.label == "pm get-max-users" }.verdict)
    }
}
