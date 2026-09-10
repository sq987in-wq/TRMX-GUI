package dev.trmx.gui.tools

/*
 * Phase 9.5 artifact derivation (pure, JVM-testable).
 *
 * An artifact is a file a finished tool job produced. Sources, in order:
 *  1. EXACT outputs: args the schema marks as outputs — arg name "output"
 *     (type path, path_kind file) or x_trmx.artifact = true. The app knows
 *     these values because it submitted the form (ToolJobMeta).
 *  2. DETECTED files: everything new in the job's outdir (the "outdir" arg,
 *     or the schema default) with mtime >= job.started_at — an honest
 *     heuristic, labeled "detected" in the UI.
 *
 * The bridge stays out of it (v1): no protocol change, §6.2 listing only.
 */

import dev.trmx.gui.model.FileEntry
import dev.trmx.gui.model.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/** What the app remembers about a submitted tool job (in-memory, session scope). */
data class ToolJobMeta(
    val toolId: String,
    val outputs: List<String> = emptyList(),   // wire paths (~/…) of exact outputs
    val outdir: String? = null,                // wire path of the output dir, if any
)

object Artifacts {

    /** Args whose value is a job output (exact artifacts). */
    fun outputArgs(schema: ToolSchema): List<String> =
        schema.args.filter { a ->
            (a.name == "output" && a.type == "path" && a.path_kind == "file")
                || (a.x_trmx?.artifact == true && a.type == "path")
        }.map { it.name }

    /** Derive the meta to remember at submit time from the sent args. */
    fun metaFor(schema: ToolSchema, args: Map<String, JsonElement>): ToolJobMeta {
        fun str(name: String): String? =
            args[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val outputs = outputArgs(schema).mapNotNull { str(it) }
        val outdir = str("outdir")
            ?: schema.args.firstOrNull { it.name == "outdir" }?.default?.let { d ->
                runCatching { d.jsonPrimitive.content }.getOrNull()
            }
        return ToolJobMeta(schema.id, outputs, outdir)
    }

    /**
     * ISO-8601 Z-timestamps compare lexicographically ONLY at equal
     * precision; bridge mtimes are second-precision while started_at carries
     * millis. Compare on the common prefix (seconds) — good enough for a
     * "detected, not guaranteed" heuristic.
     */
    fun mtimeAtLeastAfter(mtime: String?, startedAt: String?): Boolean {
        if (mtime == null || startedAt == null) return false
        fun trunk(s: String) = s.take(19)   // yyyy-MM-ddTHH:MM:SS
        return trunk(mtime) >= trunk(startedAt)
    }

    /**
     * Merge exact outputs with newly-detected files in outdir.
     * [listing] is the §6.2 listing of outdir (may be for a different path —
     * callers pass the right one). Exact outputs are shown even when their
     * stat fails (labelled by path); detected files dedupe against them.
     */
    fun collect(
        meta: ToolJobMeta,
        startedAt: String?,
        listing: List<FileEntry>?,
        listingPath: String?,
    ): List<Artifact> {
        val exact = meta.outputs.map { p ->
            Artifact(name = p.substringAfterLast('/'), path = p, exact = true,
                     size = null, mtime = null)
        }
        val exactNames = exact.map { it.name }.toSet()
        val detected = (listing ?: emptyList())
            .filter { it.type == "file" }
            .filter { mtimeAtLeastAfter(it.mtime, startedAt) }
            .filter { it.name !in exactNames }
            .map { Artifact(it.name, wireChildPath(listingPath, it.name), false,
                            it.size, it.mtime) }
        return exact + detected
    }

    private fun wireChildPath(dirWire: String?, name: String): String =
        if (dirWire == null) name else dirWire.trimEnd('/') + "/" + name
}

/** One derived artifact (wire path, ~-prefixed). */
data class Artifact(
    val name: String,
    val path: String,          // wire path for open/share
    val exact: Boolean,        // schema-declared output vs detected heuristic
    val size: Long?,
    val mtime: String?,
)
