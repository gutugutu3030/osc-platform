package com.oscplatform.core.util

import tools.jackson.databind.JsonNode

/**
 * Jackson [JsonNode] を Kotlin のネイティブ型へ再帰的に変換するユーティリティ。
 *
 * 複数のアダプタモジュールで共通に必要となる JSON → Kotlin 変換ロジックを集約し、 重複実装を排除する。
 */
object JsonNodeConverter {
  /**
   * [JsonNode] を Kotlin のネイティブ型に再帰的に変換する。
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
   * @param node 変換対象の [JsonNode]
   * @return Kotlin のネイティブ型に変換された値。null ノードの場合は null
   */
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
