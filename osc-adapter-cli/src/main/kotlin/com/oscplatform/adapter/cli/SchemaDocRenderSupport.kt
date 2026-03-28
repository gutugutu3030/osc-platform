package com.oscplatform.adapter.cli

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole

/**
 * スキーマドキュメントレンダラー共通のヘルパー関数を提供するユーティリティオブジェクト。
 *
 * [SchemaHtmlDocRenderer] と [SchemaMarkdownDocRenderer] で共有される引数シグネチャ・型・制約のフォーマット処理を集約する。
 */
internal object SchemaDocRenderSupport {
  /**
   * 引数リストからシグネチャ文字列をフォーマットする。
   *
   * @param args フォーマット対象の引数ノードリスト
   * @return フォーマットされたシグネチャ文字列。引数がない場合は"-"
   */
  fun formatArgSignature(args: List<OscArgNode>): String {
    if (args.isEmpty()) {
      return "-"
    }
    return args.joinToString(", ") { arg -> "${arg.name}:${typeOf(arg)}" }
  }

  /**
   * 引数ノードの種別を文字列で返す。
   *
   * @param arg 種別を判定する引数ノード
   * @return "scalar"または"array"
   */
  fun kindOf(arg: OscArgNode): String {
    return when (arg) {
      is ScalarArgNode -> "scalar"
      is ArrayArgNode -> "array"
    }
  }

  /**
   * 引数ノードの型を表示用文字列に変換する。
   *
   * @param arg 型情報を取得する引数ノード
   * @return 型の表示用文字列（例: "int", "array&lt;float&gt;"）
   */
  fun typeOf(arg: OscArgNode): String {
    return when (arg) {
      is ScalarArgNode -> tokenOf(arg.type)
      is ArrayArgNode -> "array<${arrayItemTypeOf(arg.item)}>"
    }
  }

  /**
   * 配列要素の型を表示用文字列に変換する。
   *
   * @param item 配列要素の仕様
   * @return 要素型の表示用文字列（例: "int", "tuple{x:float, y:float}"）
   */
  fun arrayItemTypeOf(item: ArrayItemSpec): String {
    return when (item) {
      is ArrayItemSpec.ScalarItem -> tokenOf(item.type)
      is ArrayItemSpec.TupleItem -> {
        val body =
            item.fields.joinToString(", ") { field -> "${field.name}:${tokenOf(field.type)}" }
        "tuple{$body}"
      }
    }
  }

  /**
   * 引数ノードの制約情報を表示用文字列に変換する。
   *
   * @param arg 制約情報を取得する引数ノード
   * @return 制約の表示用文字列（例: "role=length", "length=10", "-"）
   */
  fun constraintsOf(arg: OscArgNode): String {
    return when (arg) {
      is ScalarArgNode -> {
        if (arg.role == ScalarRole.LENGTH) {
          "role=length"
        } else {
          "-"
        }
      }

      is ArrayArgNode -> {
        when (val length = arg.length) {
          is LengthSpec.Fixed -> "length=${length.size}"
          is LengthSpec.FromField -> "lengthFrom=${length.fieldName}"
        }
      }
    }
  }

  /**
   * OSC型を表示用のトークン文字列に変換する。
   *
   * @param type 変換対象のOSC型
   * @return 型のトークン文字列（例: "int", "float", "string"）
   */
  fun tokenOf(type: OscType): String {
    return when (type) {
      OscType.INT -> "int"
      OscType.FLOAT -> "float"
      OscType.STRING -> "string"
      OscType.BOOL -> "bool"
      OscType.BLOB -> "blob"
    }
  }
}
