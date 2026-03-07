package com.oscplatform.adapter.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

internal object CliDynamicValueParser {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun parse(raw: String): Any? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val node = try {
                mapper.readTree(trimmed)
            } catch (ex: Exception) {
                throw IllegalArgumentException("Invalid JSON argument value: $raw")
            }
            return jsonNodeToValue(node)
        }
        return raw
    }

    fun jsonNodeToValue(node: JsonNode): Any? {
        return when {
            node.isTextual -> node.asText()
            node.isInt -> node.intValue()
            node.isLong -> node.longValue()
            node.isFloat || node.isDouble || node.isBigDecimal -> node.doubleValue()
            node.isBoolean -> node.booleanValue()
            node.isArray -> node.map { child -> jsonNodeToValue(child) }
            node.isObject -> linkedMapOf<String, Any?>().also { map ->
                node.fields().forEach { (key, value) ->
                    map[key] = jsonNodeToValue(value)
                }
            }
            node.isNull -> null
            else -> node.toString()
        }
    }
}
