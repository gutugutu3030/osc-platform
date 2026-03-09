package com.oscplatform.codegen

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole

/**
 * [OscSchema] から Kotlin の型安全クラスを生成する。
 *
 * 生成規則:
 * - 1メッセージ → 1 data class
 * - `role=VALUE` のスカラーと配列がコンストラクタパラメータになる
 * - `role=LENGTH` のスカラーは配列サイズの computed property になる
 * - タプル配列の要素型は nested data class として生成する
 * - `toNamedArgs()` / `fromNamedArgs()` / `PATH` / `NAME` を生成する
 */
class KotlinCodeGenerator {

  /**
   * スキーマ全体からファイルを生成する。
   *
   * @return 相対ファイルパス → ファイル内容 のマップ
   */
  fun generate(schema: OscSchema, options: CodeGenOptions): Map<String, String> {
    return schema.messages.associate { spec ->
      val className = toClassName(spec.name)
      val packagePath = options.packageName.replace('.', '/')
      "$packagePath/$className.kt" to generateClass(spec, options.packageName)
    }
  }

  /** 単一の [OscMessageSpec] から Kotlin クラスのソースを生成する。 テストから直接呼び出せるよう internal スコープにしていない。 */
  fun generateClass(spec: OscMessageSpec, packageName: String): String {
    val className = toClassName(spec.name)

    // コンストラクタパラメータ: VALUE スカラーと全配列
    val constructorArgs =
        spec.args.filter { node ->
          when (node) {
            is ScalarArgNode -> node.role == ScalarRole.VALUE
            is ArrayArgNode -> true
          }
        }

    // computed property として出力する LENGTH スカラー
    val lengthScalars =
        spec.args.filterIsInstance<ScalarArgNode>().filter { it.role == ScalarRole.LENGTH }

    // nested data class が必要なタプル配列
    val tupleArrayArgs =
        spec.args.filterIsInstance<ArrayArgNode>().filter { it.item is ArrayItemSpec.TupleItem }

    // fromNamedArgs で必要なインポートを判定
    val hasScalarArrayArgs =
        constructorArgs.any { it is ArrayArgNode && it.item is ArrayItemSpec.ScalarItem }
    val hasTupleArrayArgs = tupleArrayArgs.isNotEmpty()

    return buildString {
      appendLine("package $packageName")
      appendLine()
      appendLine("import com.oscplatform.core.runtime.OscMessage")
      appendLine("import com.oscplatform.core.runtime.OscMessageCompanion")
      appendLine("import com.oscplatform.core.runtime.oscTyped")
      if (hasScalarArrayArgs) appendLine("import com.oscplatform.core.runtime.oscTypedList")
      if (hasTupleArrayArgs) appendLine("import com.oscplatform.core.runtime.oscTypedMapList")
      appendLine()

      // --- class header ---
      appendLine("data class $className(")
      constructorArgs.forEach { node ->
        when (node) {
          is ScalarArgNode -> appendLine("    val ${node.name}: ${oscTypeToKotlin(node.type)},")
          is ArrayArgNode ->
              appendLine("    val ${node.name}: List<${arrayElementTypeName(node)}>,")
        }
      }
      appendLine(") : OscMessage {")

      // --- computed LENGTH properties ---
      lengthScalars.forEach { lengthNode ->
        val arrayNode =
            spec.args.filterIsInstance<ArrayArgNode>().firstOrNull {
              (it.length as? LengthSpec.FromField)?.fieldName == lengthNode.name
            }
        if (arrayNode != null) {
          appendLine(
              "    val ${lengthNode.name}: ${oscTypeToKotlin(lengthNode.type)}" +
                  " get() = ${arrayNode.name}.size")
        }
      }

      // --- nested data classes for tuple items ---
      tupleArrayArgs.forEach { arrayNode ->
        val tupleItem = arrayNode.item as ArrayItemSpec.TupleItem
        val nestedName = toNestedClassName(arrayNode.name)
        appendLine()
        appendLine("    data class $nestedName(")
        tupleItem.fields.forEach { field ->
          appendLine("        val ${field.name}: ${oscTypeToKotlin(field.type)},")
        }
        appendLine("    )")
      }

      // --- toNamedArgs() ---
      appendLine()
      appendLine("    override fun toNamedArgs(): Map<String, Any?> =")
      appendLine("        mapOf(")
      spec.args.forEach { node ->
        when (node) {
          is ScalarArgNode -> appendLine("            \"${node.name}\" to ${node.name},")
          is ArrayArgNode ->
              when (val item = node.item) {
                is ArrayItemSpec.ScalarItem ->
                    appendLine("            \"${node.name}\" to ${node.name},")
                is ArrayItemSpec.TupleItem -> {
                  val mapBody = item.fields.joinToString(", ") { "\"${it.name}\" to it.${it.name}" }
                  appendLine(
                      "            \"${node.name}\" to ${node.name}" + ".map { mapOf($mapBody) },")
                }
              }
        }
      }
      appendLine("        )")

      // --- companion object ---
      appendLine()
      appendLine("    companion object : OscMessageCompanion<$className> {")
      appendLine("        override val PATH: String = \"${spec.path}\"")
      appendLine("        override val NAME: String = \"${spec.name}\"")
      appendLine()

      appendLine("        override fun fromNamedArgs(args: Map<String, Any?>): $className =")
      appendLine("            $className(")
      constructorArgs.forEach { node ->
        when (node) {
          is ScalarArgNode -> {
            val kt = oscTypeToKotlin(node.type)
            appendLine(
                "                ${node.name} = args.oscTyped<$kt>(\"${node.name}\", NAME),")
          }
          is ArrayArgNode ->
              when (val item = node.item) {
                is ArrayItemSpec.ScalarItem -> {
                  val kt = oscTypeToKotlin(item.type)
                  appendLine(
                      "                ${node.name} =" +
                          " args.oscTypedList<$kt>(\"${node.name}\", NAME),")
                }
                is ArrayItemSpec.TupleItem -> {
                  val nestedName = toNestedClassName(node.name)
                  val fieldMappings =
                      item.fields.joinToString(", ") { field ->
                        "${field.name} = m.oscTyped<${oscTypeToKotlin(field.type)}>(\"${field.name}\", NAME)"
                      }
                  appendLine(
                      "                ${node.name} =" +
                          " args.oscTypedMapList(\"${node.name}\", NAME)" +
                          ".map { m -> $nestedName($fieldMappings) },")
                }
              }
        }
      }
      appendLine("            )")
      appendLine("    }")
      appendLine("}")
    }
  }

  /** 配列要素型の Kotlin 型名を返す。タプルの場合は nested class 名を返す。 */
  private fun arrayElementTypeName(node: ArrayArgNode): String =
      when (val item = node.item) {
        is ArrayItemSpec.ScalarItem -> oscTypeToKotlin(item.type)
        is ArrayItemSpec.TupleItem -> toNestedClassName(node.name)
      }

  /**
   * メッセージ名からクラス名を導出する。
   *
   * 例: `"light.color"` → `"LightColor"`, `"mesh.dual"` → `"MeshDual"`
   */
  fun toClassName(name: String): String {
    val normalized = name.trimStart('/').replace('/', '.')
    return normalized.split('.').joinToString("") { part ->
      part.replaceFirstChar { it.uppercaseChar() }
    }
  }

  /**
   * 配列フィールド名から nested data class 名を導出する。
   *
   * 例: `"points"` → `"Point"`, `"items"` → `"Item"`, `"data"` → `"Data"`
   */
  private fun toNestedClassName(arrayFieldName: String): String {
    val cap = arrayFieldName.replaceFirstChar { it.uppercaseChar() }
    return if (cap.length > 1 && cap.endsWith("s")) cap.dropLast(1) else cap
  }

  private fun oscTypeToKotlin(type: OscType): String =
      when (type) {
        OscType.INT -> "Int"
        OscType.FLOAT -> "Float"
        OscType.STRING -> "String"
        OscType.BOOL -> "Boolean"
        OscType.BLOB -> "ByteArray"
      }
}
