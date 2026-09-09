package dev.trmx.gui.tools

/*
 * Phase 9 chains (ADR-010): linear pipelines of tool jobs, orchestrated
 * APP-SIDE over the existing §3/§7 wire — the bridge knows nothing about
 * chains. Pure planning logic; AppViewModel drives the runs.
 *
 * v1 semantics (deliberately honest, no magic):
 * - A step may reference "$PREV_FILE" inside any string/path arg value; it
 *   resolves to the PREVIOUS step's output file at run time.
 * - A step exposes an output iff its schema has an arg named "output" of
 *   type path/path_kind=file (ffmpeg-style). Tools that write
 *   server-named files into a directory (yt-dlp) cannot be chained FROM —
 *   the app cannot know the filename without parsing tool output.
 * - If the bridge restarts mid-chain, the run PAUSES with the failing
 *   step; "resume" re-submits from that step. No hidden state on the phone.
 */

import dev.trmx.gui.model.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

const val PREV_FILE = "$PREV_FILE"

@Serializable
data class ChainStep(
    val toolId: String,
    val title: String = "",
    val args: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class ChainDef(
    val id: String,
    val title: String,
    val steps: List<ChainStep> = emptyList(),
    val createdAt: Long = 0,
)

/** Live state of one chain run (kept in memory; chains are re-run, not resumed across app deaths). */
data class ChainRunState(
    val def: ChainDef,
    val currentStep: Int,                     // index of the running/paused step
    val stepJobIds: Map<Int, String> = emptyMap(),
    val status: String = "RUNNING",           // RUNNING|COMPLETED|FAILED|PAUSED
    val error: String? = null,
)

object ChainPlanner {

    /** The arg a chain can tap as "the file this step produces", if any. */
    fun outputArg(schema: ToolSchema): dev.trmx.gui.model.ToolArg? =
        schema.args.firstOrNull { it.name == "output" && it.type == "path" && it.path_kind == "file" }

    fun stepHasOutput(schema: ToolSchema): Boolean = outputArg(schema) != null

    private fun argString(step: ChainStep, name: String): String? =
        step.args[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    /** The literal (unresolved) output value of a step, or null. */
    fun stepOutput(step: ChainStep): String? = argString(step, "output")

    /** Replace $PREV_FILE in every string arg value with the previous output. */
    fun resolveArgs(step: ChainStep, prevOutput: String?): Map<String, JsonElement> =
        step.args.mapValues { (_, v) ->
            val s = runCatching { v.jsonPrimitive.content }.getOrNull()
            if (s != null && s.contains(PREV_FILE)) {
                kotlinx.serialization.json.JsonPrimitive(s.replace(PREV_FILE, prevOutput ?: ""))
            } else {
                v
            }
        }

    /**
     * Planning errors, in human words. `schemasById` = the registry snapshot
     * the run will use (a saved chain may reference a tool that vanished).
     */
    fun validate(def: ChainDef, schemasById: Map<String, ToolSchema>): List<String> {
        val errs = mutableListOf<String>()
        if (def.steps.isEmpty()) errs += "a chain needs at least one step"
        def.steps.forEachIndexed { i, step ->
            val schema = schemasById[step.toolId]
            if (schema == null) {
                errs += "step ${i + 1}: unknown tool '${step.toolId}'"
                return@forEachIndexed
            }
            if (!step.title.isBlank() && step.title.length > 80) {
                errs += "step ${i + 1}: title too long"
            }
            val usesRef = step.args.values.any { v ->
                runCatching { v.jsonPrimitive.content.contains(PREV_FILE) }.getOrDefault(false)
            }
            if (usesRef) {
                if (i == 0) {
                    errs += "step 1 cannot use $PREV_FILE (nothing runs before it)"
                } else {
                    val prev = def.steps[i - 1]
                    val prevSchema = schemasById[prev.toolId] ?: return@forEachIndexed
                    if (!stepHasOutput(prevSchema)) {
                        errs += "step ${i + 1} uses $PREV_FILE but step ${i} " +
                            "('${prev.toolId}') produces no known output file"
                    }
                }
            }
        }
        return errs
    }

    /**
     * Resolved outputs of steps 0..idx-1, walking refs forward (used to
     * resume a chain mid-way: each step's output may itself contain a
     * $PREV_FILE reference to the step before it).
     */
    fun outputsUpTo(def: ChainDef, schemasById: Map<String, ToolSchema>,
                    idx: Int): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        var prev: String? = null
        for (i in 0 until minOf(idx, def.steps.size)) {
            val resolved = resolveArgs(def.steps[i], prev)
            prev = resolved["output"]?.let { a ->
                runCatching { a.jsonPrimitive.content }.getOrNull()
            }
            if (prev != null) out[i] = prev!!
        }
        return out
    }

    /** Default display title for a step ("yt-dlp", "ffmpeg — Compress"). */
    fun stepTitle(step: ChainStep, schema: ToolSchema?): String =
        step.title.ifBlank { schema?.name ?: step.toolId }
}
