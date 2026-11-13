package dev.videoeditor.timeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Minimal validator for the JSON Schema subset the generator emits. Returns violation messages. */
object TestSchemaValidator {
    fun validate(
        schema: JsonObject,
        value: JsonElement,
        path: String = "$",
    ): List<String> {
        val out = mutableListOf<String>()
        when (val type = (schema["type"] as? JsonPrimitive)?.content) {
            "object" -> {
                if (value !is JsonObject) return listOf("$path: expected object")
                val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                (schema["required"] as? JsonArray)?.forEach {
                    val k = (it as JsonPrimitive).content
                    if (k !in value) out += "$path: missing required '$k'"
                }
                val closed = (schema["additionalProperties"] as? JsonPrimitive)?.content == "false"
                for ((k, v) in value) {
                    val sub = props[k] as? JsonObject
                    if (sub == null) {
                        if (closed) out += "$path: unknown property '$k'"
                    } else {
                        out += validate(sub, v, "$path.$k")
                    }
                }
            }
            "array" -> {
                if (value !is JsonArray) return listOf("$path: expected array")
                val items = schema["items"] as? JsonObject
                if (items != null) value.forEachIndexed { i, v -> out += validate(items, v, "$path[$i]") }
            }
            "string" -> if (value !is JsonPrimitive || !value.isString) out += "$path: expected string"
            "boolean" ->
                if (value !is JsonPrimitive || value.isString || value.content !in listOf("true", "false")) {
                    out += "$path: expected boolean"
                }
            "integer" ->
                if (value !is JsonPrimitive || value.isString || value.longOrNull == null) {
                    out += "$path: expected integer"
                }
            "number" ->
                if (value !is JsonPrimitive || value.isString || value.doubleOrNull == null) {
                    out += "$path: expected number"
                }
            else -> out += "$path: unsupported schema type $type"
        }
        if (value is JsonNull) out += "$path: null not allowed"
        (schema["enum"] as? JsonArray)?.let { e -> if (value !in e) out += "$path: $value not in enum" }
        (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { min ->
            val v = (value as? JsonPrimitive)?.doubleOrNull
            if (v != null && v < min) out += "$path: $v below minimum $min"
        }
        return out
    }
}
