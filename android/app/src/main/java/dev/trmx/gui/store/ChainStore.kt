package dev.trmx.gui.store

/*
 * Phase 9 chain persistence (ADR-010): file-backed list of ChainDefs
 * (filesDir/chains.json), same JVM-testable pattern as RecipeStore.
 */

import dev.trmx.gui.tools.ChainDef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class ChainStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(dir, "chains.json")

    fun list(): List<ChainDef> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString(ListSerializer(ChainDef.serializer()), file.readText())
    }.getOrDefault(emptyList())

    fun save(def: ChainDef) {
        val all = list().filterNot { it.id == def.id } + def
        persist(all)
    }

    fun delete(id: String) = persist(list().filterNot { it.id == id })

    private fun persist(all: List<ChainDef>) {
        dir.mkdirs()
        val tmp = File(dir, "chains.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(ChainDef.serializer()),
                                          all.sortedBy { it.title }))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not persist chains.json" }
        }
    }
}
