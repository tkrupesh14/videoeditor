package dev.videoeditor.core.ai.assistant

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaTool
import dev.videoeditor.timeline.schema.ToolKind
import dev.videoeditor.timeline.schema.ToolRegistry
import dev.videoeditor.timeline.schema.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/** The tool set offered to Claude and the conversion to strict SDK tool definitions. */
object EditTools {
    /**
     * Not offered: `import_media` (takes a file URI, which must never be sent or chosen by the model) and
     * `batch` (recursive schema, which strict mode does not support; parallel tool calls cover it).
     */
    val EXCLUDED: Set<String> = setOf("import_media", "batch")

    /** Read-only query that never exposes asset URIs; lets Claude re-read clip positions after a ripple. */
    val QUERIES: Set<String> = setOf("list_clips")

    val specs: List<ToolSpec> =
        ToolRegistry.all.filter {
            (it.kind == ToolKind.COMMAND && it.name !in EXCLUDED) || it.name in QUERIES
        }

    val names: Set<String> = specs.map { it.name }.toSet()

    /** Keywords strict tool use rejects (numeric, string and complex array constraints). */
    private val UNSUPPORTED =
        setOf(
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "uniqueItems",
        )

    /**
     * Makes a schema valid for `strict: true`: strips unsupported constraints (the timeline validates them
     * and its errors go back to Claude as `is_error` results) and sets `additionalProperties: false` on every
     * object.
     */
    fun strictSchema(schema: JsonElement): JsonElement =
        when (schema) {
            is JsonObject -> {
                val cleaned = schema.filterKeys { it !in UNSUPPORTED }.mapValues { (k, v) -> strictValue(k, v) }
                val isObject = (cleaned["type"] as? JsonPrimitive)?.content == "object" || "properties" in cleaned
                JsonObject(if (isObject) cleaned + ("additionalProperties" to JsonPrimitive(false)) else cleaned)
            }
            is JsonArray -> JsonArray(schema.map { strictSchema(it) })
            else -> schema
        }

    private fun strictValue(
        key: String,
        value: JsonElement,
    ): JsonElement =
        when (key) {
            // Maps of name -> schema.
            "properties", "\$defs", "definitions" ->
                if (value is JsonObject) JsonObject(value.mapValues { strictSchema(it.value) }) else value
            "items", "anyOf", "allOf", "oneOf", "not" -> strictSchema(value)
            else -> value
        }

    fun toBetaTool(spec: ToolSpec): BetaTool {
        val schema = strictSchema(spec.inputSchema) as JsonObject
        val properties = BetaTool.InputSchema.Properties.builder()
        (schema["properties"] as? JsonObject)?.forEach { (name, s) ->
            properties.putAdditionalProperty(name, JsonValue.from(s.toPlain()))
        }
        val required =
            (schema["required"] as? JsonArray)?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
        return BetaTool
            .builder()
            .name(spec.name)
            .description(spec.description)
            .strict(true)
            .inputSchema(
                BetaTool.InputSchema
                    .builder()
                    .properties(properties.build())
                    .required(required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            ).build()
    }

    val betaTools: List<BetaTool> by lazy { specs.map { toBetaTool(it) } }

    internal fun JsonElement.toPlain(): Any? =
        when (this) {
            JsonNull -> null
            is JsonPrimitive ->
                if (isString) {
                    content
                } else {
                    booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
                }
            is JsonObject -> mapValues { it.value.toPlain() }
            is JsonArray -> map { it.toPlain() }
        }

    internal fun fromSdk(value: JsonValue): JsonElement =
        Json.parseToJsonElement(jsonMapper().writeValueAsString(value))
}
