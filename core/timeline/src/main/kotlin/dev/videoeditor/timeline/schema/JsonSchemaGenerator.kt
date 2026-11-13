@file:OptIn(ExperimentalSerializationApi::class)

package dev.videoeditor.timeline.schema

import dev.videoeditor.timeline.command.CommandTypes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generates JSON Schemas from serial descriptors (design section 6.4), restricted to `type`, `properties`,
 * `required`, `enum`, `items`, `description`, `minimum`, plus `additionalProperties: false` on every
 * closed object schema (orchestrator override of the design, so strict validators reject unknown keys
 * exactly as the decoder does).
 */
object JsonSchemaGenerator {
    /** Object schema for a serializable class. `omit` lists top-level properties to leave out. */
    fun schemaFor(
        descriptor: SerialDescriptor,
        omit: Set<String> = emptySet(),
    ): JsonObject = objectSchema(descriptor, classDescription(descriptor), omit)

    private fun classDescription(d: SerialDescriptor): String? =
        d.annotations
            .filterIsInstance<Desc>()
            .firstOrNull()
            ?.text

    private fun objectSchema(
        d: SerialDescriptor,
        description: String?,
        omit: Set<String>,
    ): JsonObject {
        require(d.kind == StructureKind.CLASS || d.kind == StructureKind.OBJECT) {
            "${d.serialName} is not a class (${d.kind})"
        }
        val props = LinkedHashMap<String, JsonElement>()
        val required = mutableListOf<String>()
        for (i in 0 until d.elementsCount) {
            val name = d.getElementName(i)
            if (name in omit) continue
            props[name] = elementSchema(d.getElementDescriptor(i), d.getElementAnnotations(i))
            if (!d.isElementOptional(i)) required += name
        }
        return buildObject(
            "type" to JsonPrimitive("object"),
            "description" to description?.let { JsonPrimitive(it) },
            "properties" to JsonObject(props),
            "required" to required.takeIf { it.isNotEmpty() }?.let { r -> JsonArray(r.map { JsonPrimitive(it) }) },
            "additionalProperties" to JsonPrimitive(false),
        )
    }

    private fun elementSchema(
        d: SerialDescriptor,
        annotations: List<Annotation>,
    ): JsonObject {
        val desc = annotations.filterIsInstance<Desc>().firstOrNull()?.text
        val min = annotations.filterIsInstance<Min>().firstOrNull()?.value
        val base: JsonObject =
            when (val kind = d.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> buildObject("type" to JsonPrimitive("string"))
                PrimitiveKind.LONG, PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                    buildObject("type" to JsonPrimitive("integer"))
                PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> buildObject("type" to JsonPrimitive("number"))
                PrimitiveKind.BOOLEAN -> buildObject("type" to JsonPrimitive("boolean"))
                SerialKind.ENUM ->
                    buildObject(
                        "type" to JsonPrimitive("string"),
                        "enum" to JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) }),
                    )
                StructureKind.LIST ->
                    buildObject(
                        "type" to JsonPrimitive("array"),
                        "items" to elementSchema(d.getElementDescriptor(0), emptyList()),
                    )
                StructureKind.CLASS, StructureKind.OBJECT ->
                    return objectSchema(d, desc ?: classDescription(d), emptySet())
                PolymorphicKind.SEALED, PolymorphicKind.OPEN -> commandItemSchema()
                else -> error("unsupported kind $kind in ${d.serialName}")
            }
        return buildObject(
            *base.entries.map { it.key to it.value }.toTypedArray(),
            "minimum" to min?.let { JsonPrimitive(it) },
            "description" to desc?.let { JsonPrimitive(it) },
        )
    }

    /**
     * Item schema of `Batch.commands`: an object naming a command type. It is the one open object schema
     * (`additionalProperties: true`), because each command's own fields are allowed next to `type`.
     */
    private fun commandItemSchema(): JsonObject =
        buildObject(
            "type" to JsonPrimitive("object"),
            "properties" to
                JsonObject(
                    mapOf(
                        "type" to
                            buildObject(
                                "type" to JsonPrimitive("string"),
                                "enum" to
                                    JsonArray(
                                        CommandTypes.ALL.filter { it != "batch" }.map { JsonPrimitive(it) },
                                    ),
                            ),
                    ),
                ),
            "required" to JsonArray(listOf(JsonPrimitive("type"))),
            "additionalProperties" to JsonPrimitive(true),
        )

    private fun buildObject(vararg entries: Pair<String, JsonElement?>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in entries) if (v != null) m[k] = v
        return JsonObject(m)
    }
}
