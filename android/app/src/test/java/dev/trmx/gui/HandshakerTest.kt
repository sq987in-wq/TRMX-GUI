package dev.trmx.gui

import dev.trmx.gui.model.SystemInfo
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.Handshaker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakerTest {

    private val info = SystemInfo(bridge_version = "0.2.0")

    @Test
    fun `succeeds once the bridge answers`() = runTest {
        var attempts = 0
        val h = Handshaker(
            probe = { if (++attempts == 3) BridgeResult.Success(info) else BridgeResult.NetworkError(RuntimeException("refused")) },
            pollIntervalMs = 100, maxAttempts = 10)
        val r = h.awaitHandshake(delayFn = { })   // virtual time — no real sleeping
        assertTrue(r is BridgeResult.Success)
        assertTrue(attempts == 3)
    }

    @Test
    fun `auth errors surface immediately instead of burning the deadline`() = runTest {
        var attempts = 0
        val h = Handshaker(
            probe = { attempts++; BridgeResult.HttpError(401, "AUTHENTICATION_FAILED", "bad token") },
            pollIntervalMs = 100, maxAttempts = 10)
        val r = h.awaitHandshake(delayFn = { })
        assertTrue(r is BridgeResult.HttpError)
        assertTrue(attempts == 1)
    }

    @Test
    fun `deadline exhausted reports the last network error`() = runTest {
        val h = Handshaker(
            probe = { BridgeResult.NetworkError(RuntimeException("refused")) },
            pollIntervalMs = 10, maxAttempts = 5)
        val r = h.awaitHandshake(delayFn = { })
        assertTrue(r is BridgeResult.NetworkError)
    }
}
