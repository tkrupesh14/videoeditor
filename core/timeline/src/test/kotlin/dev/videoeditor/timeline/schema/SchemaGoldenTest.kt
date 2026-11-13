package dev.videoeditor.timeline.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SchemaGoldenTest {
    private val pretty = Json { prettyPrint = true }
    private val dir = File(System.getProperty("schemaDir") ?: "src/test/resources/schemas")
    private val update = System.getProperty("updateSchemas") == "true"

    @Test
    fun `golden schema files match the generator`() {
        dir.mkdirs()
        val drift = mutableListOf<String>()
        for (tool in ToolRegistry.all) {
            val text = pretty.encodeToString(JsonObject.serializer(), tool.inputSchema) + "\n"
            val file = File(dir, "${tool.name}.json")
            if (update) {
                file.writeText(text)
            } else if (!file.exists() || file.readText() != text) {
                drift += tool.name
            }
        }
        val expected = ToolRegistry.all.map { "${it.name}.json" }.toSet()
        val extra =
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.endsWith(".json") && it !in expected }
        if (update) extra.forEach { File(dir, it).delete() }
        assertTrue(
            update || (drift.isEmpty() && extra.isEmpty()),
            "JSON Schema drift for $drift (stale files: $extra). If the tool surface change is intended, " +
                "regenerate the golden files with `./gradlew :core:timeline:test -PupdateSchemas` " +
                "and review the diff of " +
                "core/timeline/src/test/resources/schemas/.",
        )
    }

    @Test
    fun `every schema is an object root, closed, and fully described`() {
        for (tool in ToolRegistry.all) {
            val s = tool.inputSchema
            assertEquals(JsonPrimitive("object"), s["type"], tool.name)
            assertTrue(tool.description.isNotBlank())
            checkObject(tool.name, s)
        }
    }

    private fun checkObject(
        path: String,
        s: JsonObject,
    ) {
        if (s["type"] == JsonPrimitive("object")) {
            val open = path.endsWith("commands[]")
            assertEquals(JsonPrimitive(open), s["additionalProperties"], "$path additionalProperties")
            val props = s["properties"]?.jsonObject ?: JsonObject(emptyMap())
            if (!open) {
                for ((name, p) in props) {
                    val po = p.jsonObject
                    val desc = (po["description"] as? JsonPrimitive)?.content
                    assertTrue(!desc.isNullOrBlank(), "$path.$name has no description")
                    if (name.endsWith("Us")) assertTrue("microseconds" in desc!!, "$path.$name must state microseconds")
                }
            }
            props.forEach { (n, p) -> checkElement("$path.$n", p) }
            val required = s["required"]?.jsonArray?.map { (it as JsonPrimitive).content }.orEmpty()
            assertTrue(props.keys.containsAll(required), path)
        }
    }

    private fun checkElement(
        path: String,
        e: JsonElement,
    ) {
        val o = e.jsonObject
        if (o["type"] == JsonPrimitive("array")) checkElement("$path[]", o.getValue("items"))
        if (o["type"] == JsonPrimitive("object")) checkObject(path, o)
    }

    @Test
    fun `required lists exactly the non-default properties and names are valid`() {
        val split = ToolRegistry.find("split")!!.inputSchema
        assertEquals(JsonArray(listOf(JsonPrimitive("clipIds"), JsonPrimitive("atUs"))), split["required"])
        assertEquals(JsonPrimitive(0), split["properties"]!!.jsonObject["atUs"]!!.jsonObject["minimum"])
        val trim = ToolRegistry.find("trim")!!.inputSchema
        assertEquals(
            listOf("clipId", "edge", "toUs"),
            trim["required"]!!.jsonArray.map { (it as JsonPrimitive).content },
        )
        assertEquals(10, ToolRegistry.mcpV1.size)
        assertEquals(19, ToolRegistry.all.size)
        val re = Regex("^[a-zA-Z0-9_-]{1,64}$")
        ToolRegistry.all.forEach { assertTrue(re.matches(it.name), it.name) }
        assertEquals(
            ToolRegistry.all.size,
            ToolRegistry.all
                .map { it.name }
                .toSet()
                .size,
        )
    }
}
