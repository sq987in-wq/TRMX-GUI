package dev.trmx.gui

import dev.trmx.gui.control.ControlOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Control-plane op table — the in-app mirror of docs/CONTROL-PLANE.md §3.
 * (tests/spec_conformance.py cross-checks this table against the doc itself.)
 */
class ControlOpsTest {

    @Test
    fun `all eight operations are defined`() {
        val ids = ControlOps.SPECS.map { it.id }
        assertEquals(
            listOf("INSTALL_PY", "INSTALL", "PAIR", "START",
                   "STOP", "STATUS", "ENABLE_BOOT", "ENABLE_SERVICE"),
            ids)
    }

    @Test
    fun `every path stays inside the Termux sandbox`() {
        ControlOps.SPECS.forEach { spec ->
            assertTrue("path escapes sandbox: ${spec.path}",
                       ControlOps.pathIsInTermuxSandbox(spec.path))
        }
    }

    @Test
    fun `argv-first - no static op embeds the token or a shell string`() {
        // the token must only ever be substituted at PAIR call time;
        // everything except INSTALL is a plain argv array
        ControlOps.SPECS.filter { it.id != "INSTALL" }.forEach { spec ->
            assertTrue("unexpected shell flag in ${spec.id}",
                       spec.args.none { it == "-c" || it == "-lc" })
        }
        assertTrue("static argv must not contain a real-looking token",
                   ControlOps.SPECS.none { spec -> spec.args.any { it.length > 60 } })
    }

    @Test
    fun `INSTALL resolves the base URL into the pipeline`() {
        val spec = ControlOps.spec("INSTALL")!!
        val args = ControlOps.resolveArgs(spec, baseUrl = "https://example.com/t", token = null)
        assertEquals(
            listOf("-c", "curl -fsSL https://example.com/t/install.sh | sh -s -- --base https://example.com/t"),
            args)
    }

    @Test
    fun `PAIR resolves the token into argv position 2`() {
        val args = ControlOps.resolveArgs(
            ControlOps.spec("PAIR")!!, baseUrl = null, token = "abc123-def456_ghi789XYZ01")
        assertEquals(listOf("pair", "abc123-def456_ghi789XYZ01"), args)
    }

    @Test
    fun `unresolved placeholders fail loud`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlOps.resolveArgs(ControlOps.spec("INSTALL")!!,
                                   baseUrl = null, token = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlOps.resolveArgs(ControlOps.spec("PAIR")!!,
                                   baseUrl = null, token = "")
        }
    }

    @Test
    fun `START is exactly argv start`() {
        val spec = ControlOps.spec("START")!!
        assertEquals(listOf("start"), spec.args)
        assertEquals("/data/data/com.termux/files/home/.trmx/trmx", spec.path)
    }

    @Test
    fun `spec lookup is total for the doc op set`() {
        listOf("INSTALL_PY", "INSTALL", "PAIR", "START", "STOP", "STATUS",
               "ENABLE_BOOT", "ENABLE_SERVICE").forEach {
            assertTrue(ControlOps.spec(it) != null)
        }
        assertFalse(ControlOps.spec("RANSOMWARE") != null)
    }
}
