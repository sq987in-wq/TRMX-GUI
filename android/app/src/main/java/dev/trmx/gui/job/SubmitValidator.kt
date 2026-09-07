package dev.trmx.gui.job

/*
 * Client-side submit validation — mirrors the bridge's rules
 * (trmx-bridge.py submit(), TRMX-P/1 §3.2) so the user gets instant
 * feedback. The bridge re-validates and remains the authority; this is
 * UX, not security.
 */

object SubmitValidator {

    /** Mirrors the wire contract's validation caps. */
    const val MAX_ARGC = 256
    const val MAX_ARG_BYTES = 8192
    const val MAX_ARGV_TOTAL_BYTES = 65536
    const val MAX_NAME_CHARS = 200
    const val MAX_TIMEOUT_S = 2_592_000L   // 30 days, as in the bridge

    data class FieldError(val field: String, val message: String)

    fun validate(
        name: String,
        argv: List<String>,
        timeoutS: Long?,
        cwd: String,
    ): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (name.isNotEmpty() && name.length > MAX_NAME_CHARS) {
            errors += FieldError("name", "Name is limited to $MAX_NAME_CHARS characters.")
        }
        if (argv.isEmpty()) {
            errors += FieldError("argv", "At least one argument (the executable) is required.")
        }
        if (argv.size > MAX_ARGC) {
            errors += FieldError("argv", "Too many arguments (max $MAX_ARGC).")
        }
        if (argv.any { it.length > MAX_ARG_BYTES }) {
            errors += FieldError("argv", "A single argument exceeds $MAX_ARG_BYTES characters.")
        }
        if (argv.sumOf { it.length } > MAX_ARGV_TOTAL_BYTES) {
            errors += FieldError("argv", "Arguments exceed $MAX_ARGV_TOTAL_BYTES characters in total.")
        }
        if (timeoutS != null && (timeoutS < 1 || timeoutS > MAX_TIMEOUT_S)) {
            errors += FieldError("timeout_s", "Timeout must be 1..$MAX_TIMEOUT_S seconds.")
        }
        return errors
    }

    /**
     * One argument per line — deliberately NOT shell parsing. There is no
     * quoting, no escaping, no injection surface: what the user types per
     * line is one argv element, exactly (argv-first principle, ADR-001).
     */
    fun parseArgvText(text: String): List<String> =
        text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
}
