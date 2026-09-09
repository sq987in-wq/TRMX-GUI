package dev.trmx.gui.store

/*
 * Phase 9 recipes (ADR-010): saved tool-form states, reusable and
 * shareable as JSON. File-backed (filesDir/recipes.json) and deliberately
 * constructor-injected with a directory so the store is JVM-testable
 * without Android. Also feeds home-screen dynamic shortcuts.
 */

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

@Serializable
data class Recipe(
    val id: String,                       // uuid at creation
    val title: String,
    val toolId: String,
    val args: Map<String, JsonElement> = emptyMap(),
    val createdAt: Long = 0,
)

class RecipeStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(dir, "recipes.json")

    fun list(): List<Recipe> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString(ListSerializer(Recipe.serializer()), file.readText())
    }.getOrDefault(emptyList())

    fun byId(id: String): Recipe? = list().firstOrNull { it.id == id }

    fun add(recipe: Recipe) {
        val all = list().filterNot { it.id == recipe.id } + recipe
        persist(all)
    }

    fun remove(id: String) = persist(list().filterNot { it.id == id })

    private fun persist(all: List<Recipe>) {
        dir.mkdirs()
        val tmp = File(dir, "recipes.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Recipe.serializer()), all.sortedBy { it.title }))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not persist recipes.json" }
        }
    }

    companion object {
        /** One recipe as shareable JSON text. */
        fun export(r: Recipe): String =
            Json { prettyPrint = true }.encodeToString(Recipe.serializer(), r)

        /** Parse imported recipe text; null when malformed (caller shows an honest error). */
        fun import(text: String): Recipe? = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(Recipe.serializer(), text)
        }.getOrNull()

        fun newId(): String =
            java.util.UUID.randomUUID().toString().replace("-", "").take(20)
    }
}
