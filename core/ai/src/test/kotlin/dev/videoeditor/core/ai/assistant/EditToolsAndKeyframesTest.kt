package dev.videoeditor.core.ai.assistant

import com.anthropic.core.jsonMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class EditToolsTest {
    @Test
    fun `offers edit tools only, never import_media or batch`() {
        assertTrue("split" in EditTools.names && "apply_cuts" in EditTools.names && "trim" in EditTools.names)
        assertFalse("import_media" in EditTools.names)
        assertFalse("batch" in EditTools.names)
        assertFalse("export" in EditTools.names)
        assertFalse("get_project" in EditTools.names, "full project JSON would include asset URIs")
    }

    @Test
    fun `every tool is strict with a strict-compatible schema`() {
        for (tool in EditTools.betaTools) {
            val json = Json.parseToJsonElement(jsonMapper().writeValueAsString(tool)).jsonObject
            assertEquals("true", json["strict"]!!.jsonPrimitive.content)
            val schema = json["input_schema"]!!
            assertEquals("object", schema.jsonObject["type"]!!.jsonPrimitive.content)
            walk(schema)
        }
    }

    private fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                for (k in listOf(
                    "minimum",
                    "maximum",
                    "minLength",
                    "maxLength",
                    "multipleOf",
                )) {
                    assertFalse(k in e, "$k in $e")
                }
                if (e["type"]?.jsonPrimitive?.content == "object") {
                    assertEquals(
                        "false",
                        e["additionalProperties"]?.jsonPrimitive?.content,
                        "additionalProperties in $e",
                    )
                }
                e.values.forEach { walk(it) }
            }
            is JsonArray -> e.forEach { walk(it) }
            else -> Unit
        }
    }
}

class KeyframesTest {
    private fun jpeg(
        w: Int,
        h: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out)
        return out.toByteArray()
    }

    @Test
    fun `reads JPEG size and enforces the 512 px long edge and the count cap`() {
        assertEquals(320 to 180, Keyframes.jpegSize(jpeg(320, 180)))
        assertNull(Keyframes.jpegSize(byteArrayOf(1, 2, 3, 4)))
        val small = Keyframe(0, jpeg(512, 288))
        val big = Keyframe(1, jpeg(640, 360))
        val png = Keyframe(2, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
        assertEquals(listOf(small), Keyframes.accept(listOf(big, small, png), max = 8))
        assertEquals(8, Keyframes.accept(List(12) { small }, max = 20).size)
        assertEquals(2, Keyframes.accept(List(12) { small }, max = 2).size)
    }
}
