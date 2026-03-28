package com.oscplatform.core.util

import tools.jackson.databind.JsonNode

/**
 * [JsonNode] を Kotlin のネイティブ型に再帰的に変換する拡張関数。
 *
 * 変換規則:
 * - 文字列ノード → [String]
 * - 整数ノード → [Int]
 * - 長整数ノード → [Long]
 * - 浮動小数点ノード → [Double]
 * - 真偽値ノード → [Boolean]
 * - 配列ノード → [List] (再帰変換)
 * - オブジェクトノード → [LinkedHashMap] (再帰変換、挿入順序を保持)
 * - null ノード → `null`
 * - その他 → 文字列表現
 *
 * @return Kotlin のネイティブ型に変換された値。null ノードの場合は null
 */
fun JsonNode.toKotlinValue(): Any? {
  return when {
    isString -> stringValue()!!
    isInt -> intValue()
    isLong -> longValue()
    isFloat || isDouble || isBigDecimal -> doubleValue()
    isBoolean -> booleanValue()
    isArray -> toList().map { child -> child.toKotlinValue() }
    isObject ->
        linkedMapOf<String, Any?>().also { map ->
          properties().forEach { (key, value) -> map[key] = value.toKotlinValue() }
        }
    isNull -> null
    else -> toString()
  }
}
