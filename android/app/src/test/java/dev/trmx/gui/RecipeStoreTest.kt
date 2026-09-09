package dev.trmx.gui

import dev.trmx.gui.store.Recipe
import dev.trmx.gui.store.RecipeStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Phase 9 recipes: persistence, round-trip export/import, malformed input. */
class RecipeStoreTest {

    private fun store() = RecipeStore(Files.createTempDirectory("trmx-recipes").toFile())

    @Test
    fun `add list remove persist across instances`() {
        val dir = Files.createTempDirectory("trmx-recipes").toFile()
        val a = RecipeStore(dir)
        a.add(Recipe("r1", "Cats", "yt-dlp",
                     mapOf("url" to JsonPrimitive("https://x.example/a")), 1))
        a.add(Recipe("r2", "News", "yt-dlp",
                     mapOf("url" to JsonPrimitive("https://x.example/b")), 2))
        // same id -> replace, not duplicate
        a.add(Recipe("r1", "Cats v2", "yt-dlp",
                     mapOf("url" to JsonPrimitive("https://x.example/c")), 3))
        val names = RecipeStore(dir).list().map { it.title }   // fresh instance = from disk
        assertEquals(listOf("Cats v2", "News"), names)
        RecipeStore(dir).remove("r1")
        assertEquals(listOf("News"), RecipeStore(dir).list().map { it.title })
    }

    @Test
    fun `export import round-trip`() {
        val r = Recipe("r9", "Lecture", "yt-dlp",
                       mapOf("url" to JsonPrimitive("https://x.example/z"),
                             "audio" to JsonPrimitive(true)), 42)
        val text = RecipeStore.export(r)
        val back = RecipeStore.import(text)!!
        assertEquals(r, back)
    }

    @Test
    fun `malformed imports return null instead of throwing`() {
        assertNull(RecipeStore.import("not json at all"))
        assertNull(RecipeStore.import("{\"id\": 1}"))   // wrong types
        assertTrue(RecipeStore.newId() != RecipeStore.newId())
    }

    @Test
    fun `byId finds recipes`() {
        val s = store()
        s.add(Recipe("rx", "X", "ffmpeg", emptyMap(), 0))
        assertEquals("X", s.byId("rx")!!.title)
        assertNull(s.byId("nope"))
    }
}
