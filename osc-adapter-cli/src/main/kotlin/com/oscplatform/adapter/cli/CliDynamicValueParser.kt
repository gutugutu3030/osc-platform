package com.oscplatform.adapter.cli

import com.oscplatform.core.util.JsonNodeConverter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * CLIの動的引数値をパースするユーティリティオブジェクト。
 *
 * 文字列として受け取った引数値をJSON構造体またはプレーン文字列に変換する。
 */
internal object CliDynamicValueParser {
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /**
   * 生の文字列引数値をパースし、適切なKotlin型に変換する。
   *
   * JSON配列またはオブジェクト形式の場合はJSON構造体としてパースし、 それ以外の場合はそのまま文字列として返す。
   *
   * @param raw パースする生の文字列値
   * @return パースされた値。JSONの場合はMap/Listなど、それ以外は元の文字列
   * @throws IllegalArgumentException JSON形式の値が不正な場合
   */
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

  /**
   * JacksonのJsonNodeをKotlinのネイティブ型に再帰的に変換する。
   *
   * @param node 変換対象のJsonNode
   * @return Kotlinのネイティブ型に変換された値。nullノードの場合はnull
   */
  fun jsonNodeToValue(node: JsonNode): Any? = JsonNodeConverter.jsonNodeToValue(node)
}
