package com.oscplatform.adapter.cli

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

internal object CliDynamicValueParser {
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  fun parse(raw: String): Any? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      val node =
          try {
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
      node.isString -> node.stringValue()!!
      node.isInt -> node.intValue()
      node.isLong -> node.longValue()
      node.isFloat || node.isDouble || node.isBigDecimal -> node.doubleValue()
      node.isBoolean -> node.booleanValue()
      node.isArray -> node.toList().map { child -> jsonNodeToValue(child) }
      node.isObject ->
          linkedMapOf<String, Any?>().also { map ->
            node.properties().forEach { (key, value) -> map[key] = jsonNodeToValue(value) }
          }
      node.isNull -> null
      else -> node.toString()
    }
  }
}
