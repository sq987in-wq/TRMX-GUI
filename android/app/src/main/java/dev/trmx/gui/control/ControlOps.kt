package dev.trmx.gui.control

/*
 * The control-plane operation table — the single in-app mirror of
 * docs/CONTROL-PLANE.md §3. Pure data + validation, no Android imports,
 * so it is JVM-unit-testable and cross-checked against the spec by
 * tests/spec_conformance.py (which parses SPECS below).
 *
 * argv-first: every op executes an executable directly with an argument
 * array. The one shell pipeline (INSTALL) substitutes <BASE>; the token
 * only ever travels in PAIR's argv and the HTTP Authorization header.
 */

object ControlOps {

    // ---- Termux intent constants (RUN_COMMAND intent API) ----
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"

    // ---- on-device paths ----
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TRMX_HOME = "$TERMUX_HOME/.trmx"

    /** Default install source (Phase 4; release builds may pin a tag). */
    const val DEFAULT_BASE =
        "https://raw.githubusercontent.com/sq987in-wq/TRMX-GUI/main/termux"

    data class OpSpec(
        val id: String,
        val path: String,
        val args: List<String>,
        val label: String,
        val description: String,
    )

    /** Mirrors docs/CONTROL-PLANE.md §3 — keep both in sync (tested). */
    val SPECS: List<OpSpec> = listOf(
        OpSpec(
            "INSTALL_PY", "$TERMUX_PREFIX/bin/pkg", listOf("install", "-y", "python"),
            "install python", "pkg install -y python (Termux package manager)",
        ),
        OpSpec(
            "INSTALL", "$TERMUX_PREFIX/bin/bash",
            listOf("-c", "curl -fsSL <BASE>/install.sh | sh -s -- --base <BASE>"),
            "install backend", "download + SHA256-verify the TRMX bridge installer",
        ),
        OpSpec(
            "PAIR", "$TRMX_HOME/trmx", listOf("pair", "<TOKEN>"),
            "pair", "store this app's pairing token in the bridge config",
        ),
        OpSpec(
            "START", "$TRMX_HOME/trmx", listOf("start"),
            "start backend", "start the TRMX bridge on 127.0.0.1:27342",
        ),
        OpSpec(
            "STOP", "$TRMX_HOME/trmx", listOf("stop"),
            "stop backend", "stop the TRMX bridge (graceful API stop)",
        ),
        OpSpec(
            "STATUS", "$TRMX_HOME/trmx", listOf("status"),
            "status", "print bridge status (diagnostics)",
        ),
        OpSpec(
            "ENABLE_BOOT", "$TRMX_HOME/trmx", listOf("enable-boot"),
            "enable boot", "install the Termux:Boot autostart script",
        ),
        OpSpec(
            "ENABLE_SERVICE", "$TRMX_HOME/trmx", listOf("enable-service"),
            "enable service", "install the runit service (needs termux-services)",
        ),
    )

    fun spec(id: String): OpSpec? = SPECS.firstOrNull { it.id == id }

    /**
     * Substitute runtime values (<BASE>, <TOKEN>) into an op's argv.
     * Fails loud if a placeholder cannot be resolved — an intent with a
     * literal "<TOKEN>" in it must never be sent.
     */
    fun resolveArgs(spec: OpSpec, baseUrl: String? = null, token: String? = null): List<String> =
        spec.args.map { a ->
            var out = a
            if (out.contains("<BASE>")) {
                require(!baseUrl.isNullOrBlank()) { "INSTALL op requires a base URL" }
                out = out.replace("<BASE>", baseUrl)
            }
            if (out.contains("<TOKEN>")) {
                require(!token.isNullOrBlank()) { "PAIR op requires a token" }
                out = out.replace("<TOKEN>", token)
            }
            require(!out.contains("<BASE>") && !out.contains("<TOKEN>")) {
                "unresolved placeholder in argv: $out"
            }
            out
        }

    /** Security invariant: we only ever execute inside the Termux sandbox. */
    fun pathIsInTermuxSandbox(path: String): Boolean =
        path.startsWith("$TERMUX_HOME/") || path.startsWith("$TERMUX_PREFIX/bin/")
}
