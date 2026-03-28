package com.oscplatform.adapter.cli

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole

/**
 * スキーマドキュメントレンダラー共通の拡張関数群。
 *
 * [SchemaHtmlDocRenderer] と [SchemaMarkdownDocRenderer] で共有される 引数シグネチャ・型・制約のフォーマット処理を集約する。
 */

/**
 * 引数リストからシグネチャ文字列をフォーマットする。
 *
 * @return フォーマットされたシグネチャ文字列。引数がない場合は"-"
 */
internal fun List<OscArgNode>.formatArgSignature(): String {
  if (isEmpty()) {
    return "-"
  }
  return joinToString(", ") { arg -> "${arg.name}:${arg.typeLabel()}" }
}

/**
 * 引数ノードの種別を文字列で返す。
 *
 * @return "scalar"または"array"
 */
internal fun OscArgNode.kindLabel(): String {
  return when (this) {
    is ScalarArgNode -> "scalar"
    is ArrayArgNode -> "array"
  }
}

/**
 * 引数ノードの型を表示用文字列に変換する。
 *
 * @return 型の表示用文字列（例: "int", "array&lt;float&gt;"）
 */
internal fun OscArgNode.typeLabel(): String {
  return when (this) {
    is ScalarArgNode -> type.token()
    is ArrayArgNode -> "array<${item.typeLabel()}>"
  }
}

/**
 * 配列要素の型を表示用文字列に変換する。
 *
 * @return 要素型の表示用文字列（例: "int", "tuple{x:float, y:float}"）
 */
internal fun ArrayItemSpec.typeLabel(): String {
  return when (this) {
    is ArrayItemSpec.ScalarItem -> type.token()
    is ArrayItemSpec.TupleItem -> {
      val body = fields.joinToString(", ") { field -> "${field.name}:${field.type.token()}" }
      "tuple{$body}"
    }
  }
}

/**
 * 引数ノードの制約情報を表示用文字列に変換する。
 *
 * @return 制約の表示用文字列（例: "role=length", "length=10", "-"）
 */
internal fun OscArgNode.constraintsLabel(): String {
  return when (this) {
    is ScalarArgNode -> {
      if (role == ScalarRole.LENGTH) {
        "role=length"
      } else {
        "-"
      }
    }

    is ArrayArgNode -> {
      when (val length = length) {
        is LengthSpec.Fixed -> "length=${length.size}"
        is LengthSpec.FromField -> "lengthFrom=${length.fieldName}"
      }
    }
  }
}

/**
 * OSC型を表示用のトークン文字列に変換する。
 *
 * @return 型のトークン文字列（例: "int", "float", "string"）
 */
internal fun OscType.token(): String {
  return when (this) {
    OscType.INT -> "int"
    OscType.FLOAT -> "float"
    OscType.STRING -> "string"
    OscType.BOOL -> "bool"
    OscType.BLOB -> "blob"
  }
}
