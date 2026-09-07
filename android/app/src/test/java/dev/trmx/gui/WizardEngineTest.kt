package dev.trmx.gui

import dev.trmx.gui.wizard.WizardEngine
import dev.trmx.gui.wizard.WizardEvent
import dev.trmx.gui.wizard.WizardState
import dev.trmx.gui.wizard.WizardStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardEngineTest {

    private val engine = WizardEngine()

    @Test
    fun `begin is refused without all three consents`() {
        val twoOfThree = WizardState(
            consentTermux = true, consentRunCommand = true, consentAllowExternal = false)
        val s = engine.reduce(twoOfThree, WizardEvent.Begin("t".repeat(43)))
        assertEquals(WizardStep.WELCOME, s.step)
        assertTrue(s.failure!!.contains("consents", ignoreCase = true))
    }

    @Test
    fun `begin with consents starts the install sequence busy`() {
        val ready = WizardState(
            consentTermux = true, consentRunCommand = true, consentAllowExternal = true,
            baseUrl = "https://example.com/t")
        val s = engine.reduce(ready, WizardEvent.Begin("token-of-at-least-20-chars"))
        assertEquals(WizardStep.INSTALL_PY, s.step)
        assertTrue(s.busy)
        assertTrue(s.skippable)
        assertEquals("https://example.com/t", s.baseUrl)
        assertEquals("token-of-at-least-20-chars", s.token)
    }

    @Test
    fun `begin refuses a too-short token`() {
        val ready = WizardState(
            consentTermux = true, consentRunCommand = true, consentAllowExternal = true)
        val s = engine.reduce(ready, WizardEvent.Begin("short"))
        assertEquals(WizardStep.WELCOME, s.step)
        assertTrue(s.failure != null)
    }

    @Test
    fun `the happy path walks all five steps to DONE`() {
        var s = engine.reduce(ready(), WizardEvent.Begin("token-of-at-least-20-chars"))
        listOf(WizardStep.INSTALL, WizardStep.PAIR, WizardStep.START, WizardStep.HANDSHAKE).forEach { next ->
            s = engine.reduce(s, WizardEvent.StepSucceeded(next))
            assertEquals(next, s.step)
            assertTrue(s.busy)
            assertNull(s.failure)
        }
        s = engine.reduce(s, WizardEvent.StepSucceeded(WizardStep.DONE))
        assertEquals(WizardStep.DONE, s.step)
        assertFalse(s.busy)
    }

    @Test
    fun `failure parks the wizard with a reason and retry re-busies the step`() {
        var s = engine.reduce(ready(), WizardEvent.Begin("token-of-at-least-20-chars"))
        s = engine.reduce(s, WizardEvent.StepFailed("Termux did not accept the command"))
        assertFalse(s.busy)
        assertEquals("Termux did not accept the command", s.failure)

        s = engine.reduce(s, WizardEvent.Retry)
        assertEquals(WizardStep.INSTALL_PY, s.step)
        assertTrue(s.busy)
        assertNull(s.failure)
    }

    @Test
    fun `skip wait clears the skippable flag only`() {
        var s = engine.reduce(ready(), WizardEvent.Begin("token-of-at-least-20-chars"))
        s = engine.reduce(s, WizardEvent.SkipWait)
        assertFalse(s.busy)
        assertFalse(s.skippable)
        assertEquals(WizardStep.INSTALL_PY, s.step)
    }

    @Test
    fun `reset returns to WELCOME with the base URL preserved`() {
        var s = engine.reduce(
            ready().copy(baseUrl = "https://example.com/branch"),
            WizardEvent.Begin("token-of-at-least-20-chars"))
        s = engine.reduce(s, WizardEvent.StepFailed("boom"))
        s = engine.reduce(s, WizardEvent.Reset)
        assertEquals(WizardStep.WELCOME, s.step)
        assertEquals("https://example.com/branch", s.baseUrl)
        assertFalse(s.busy)
        assertNull(s.failure)
    }

    private fun ready() = WizardState(
        consentTermux = true, consentRunCommand = true, consentAllowExternal = true)
}
