package dev.trmx.gui.tools

/*
 * Phase 9 form engine (pure Kotlin, JVM-testable): turns a ToolSchema into
 * form state — initial values from defaults, per-field validation, a live
 * argv preview, and the args payload for a tool submit.
 *
 * The BRIDGE re-validates everything (PROTOCOL §7.2: bridge-enforced at
 * submit); this engine is UX, not security. It mirrors the bridge rules so
 * the user usually sees problems before the round-trip.
 */

import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

data class FieldValue(
    val text: String = "",          // string|int|float|enum|path|url
    val bool: Boolean = false,      // bool
) {
    val isBlank: Boolean get() = !bool && text.isBlank()
}

object FormEngine {

    // ---- initial state ----------------------------------------------------

    fun initialValues(schema: ToolSchema): Map<String, FieldValue> =
        schema.args.associate { a -> a.name to initialValue(a) }

    private fun initialValue(a: ToolArg): FieldValue = when (a.type) {
        "bool" -> FieldValue(bool = a.default?.jsonPrimitive?.booleanOrNull
            ?: a.default?.let { runCatching { it.jsonPrimitive.content == "true" }.getOrNull() }
            ?: false)
        else -> FieldValue(text = a.default?.let { d ->
            runCatching { d.jsonPrimitive.content }.getOrNull() ?: ""
        } ?: "")
    }

    // ---- validation (mirrors §7.2; bridge is authoritative) ---------------

    fun validate(a: ToolArg, v: FieldValue): String? {
        if (v.isBlank) {
            return if (a.required) "required" else null
        }
        return when (a.type) {
            "bool" -> null
            "int" -> v.text.toIntOrNull()?.let { n ->
                rangeError(n.toDouble(), a)
            } ?: "must be an integer"
            "float" -> v.text.toDoubleOrNull()?.let { n ->
                rangeError(n, a)
            } ?: "must be a number"
            "enum" -> if (a.enum != null && v.text in a.enum!!) null
                      else "must be one of ${a.enum ?: emptyList()}"
            "url" -> {
                val ok = runCatching {
                    val u = java.net.URI(v.text)
                    (u.scheme == "http" || u.scheme == "https") &&
                        !u.host.isNullOrBlank()
                }.getOrDefault(false)
                if (ok) patternError(a, v) else "must be an http(s):// URL"
            }
            else -> patternError(a, v)   // string, path
        }
    }

    private fun rangeError(n: Double, a: ToolArg): String? = when {
        a.min != null && n < a.min -> "must be ≥ ${a.min.toLong()}"
        a.max != null && n > a.max -> "must be ≤ ${a.max.toLong()}"
        else -> null
    }

    private fun patternError(a: ToolArg, v: FieldValue): String? {
        val p = a.pattern ?: return null
        return if (runCatching { Regex(p).matches(v.text) }.getOrDefault(false)) null
               else "must match ${p}"
    }

    fun isSubmittable(schema: ToolSchema, values: Map<String, FieldValue>): Boolean =
        schema.args.all { a ->
            val v = values[a.name] ?: return@all false
            validate(a, v) == null
        }

    /**
     * Error shown to the user: only after the field was touched (or the user
     * attempted to run — the caller marks everything touched then). An empty
     * required field shows nothing until touched; a touched+bad field shows
     * its reason (Phase 9.5: no premature red labels).
     */
    fun visibleError(a: ToolArg, v: FieldValue, touched: Boolean): String? =
        if (!touched) null else validate(a, v)

    /** Which widget should render this arg (x_trmx.widget > type defaults). */
    fun widgetFor(a: ToolArg): String {
        val hint = a.x_trmx?.widget
        if (hint != null) return hint
        return when {
            a.type == "enum" && (a.enum?.size ?: 0) <= 4 -> "segmented"
            a.type == "enum" -> "chips"
            a.type == "bool" -> "toggle"
            (a.type == "int" || a.type == "float") && a.min != null && a.max != null
                && (a.max - a.min) <= 200.0 -> "slider"
            else -> "field"
        }
    }

    // ---- live argv preview (§7.2 synthesis, mirrored) ----------------------

    fun previewArgv(schema: ToolSchema, values: Map<String, FieldValue>): List<String> {
        val tokens = mutableListOf<String>()
        for (a in schema.args) {
            val v = values[a.name] ?: continue
            if (v.isBlank) continue
            val tmpl = a.argv
            when {
                a.type == "bool" -> if (v.bool) tokens.addAll(tmpl ?: emptyList())
                tmpl == null -> tokens.add(v.text)               // positional
                else -> tokens.addAll(tmpl.map { it.replace("{value}", v.text) })
            }
        }
        return listOf(schema.binary) + schema.fixed_argv + tokens
    }

    // ---- submit payload ----------------------------------------------------

    /**
     * Only fields with effective values are sent (§7.2 wire shape: the
     * tool fixture carries just url/format/outdir). Bool false ≡ absent
     * (both contribute nothing bridge-side), so only `true` bools are sent.
     */
    fun argsPayload(schema: ToolSchema, values: Map<String, FieldValue>): Map<String, JsonElement> {
        val out = mutableMapOf<String, JsonElement>()
        for (a in schema.args) {
            val v = values[a.name] ?: continue
            if (a.type == "bool") {
                if (v.bool) out[a.name] = JsonPrimitive(true)
            } else if (!v.text.isBlank()) {
                out[a.name] = JsonPrimitive(v.text)
            }
        }
        return out
    }

    /** A human one-liner for the run button state ("2 fields need attention"). */
    fun problems(schema: ToolSchema, values: Map<String, FieldValue>): List<String> =
        schema.args.mapNotNull { a ->
            validate(a, values[a.name] ?: FieldValue())?.let { "${a.label.ifEmpty { a.name }}: $it" }
        }
}
