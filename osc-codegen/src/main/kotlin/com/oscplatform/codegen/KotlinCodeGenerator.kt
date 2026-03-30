package com.oscplatform.codegen

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscBundleSpec
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
   * @param schema 生成元の OSC スキーマ
   * @param options コード生成オプション
   * @return 相対ファイルパス → ファイル内容 のマップ
   */
  fun generate(schema: OscSchema, options: CodeGenOptions): Map<String, String> {
    val packagePath = options.packageName.replace('.', '/')
    val messageFiles =
        schema.messages.associate { spec ->
          "$packagePath/${toClassName(spec.name)}.kt" to
              generateClass(spec, options.packageName, options.sealedInterfaceName)
        }
    val bundleFiles =
        schema.bundles.associate { spec ->
          "$packagePath/${toBundleClassName(spec.name)}.kt" to
              generateBundle(spec, schema, options.packageName)
        }
    // sealed interface が有効な場合だけ、型定義とランタイム helper をまとめて生成する。
    val sealedFiles =
        options.sealedInterfaceName?.let { name ->
          val classNames = schema.messages.map { toClassName(it.name) }
          mapOf(
              "$packagePath/$name.kt" to
                  generateSealedInterface(name, classNames, options.packageName),
              "$packagePath/${name}RuntimeExtensions.kt" to
                  generateSealedRuntimeExtensions(name, classNames, options.packageName),
          )
        } ?: emptyMap()
    return messageFiles + bundleFiles + sealedFiles
  }

  /**
   * 単一の [OscMessageSpec] から Kotlin クラスのソースを生成する。
   *
   * テストから直接呼び出せるよう internal スコープにしていない。
   *
   * @param spec 生成元のメッセージ仕様
   * @param packageName 生成コードのパッケージ名
   * @param sealedInterfaceName sealed interface の名前。指定時は OscMessage の代わりにこの型を実装する
   * @return Kotlin ソースコード文字列
   */
  fun generateClass(
      spec: OscMessageSpec,
      packageName: String,
      sealedInterfaceName: String? = null,
  ): String {
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
      // sealed interface 指定時は OscMessage の import を省略（sealed interface 経由で継承）
      if (sealedInterfaceName == null) {
        appendLine("import com.oscplatform.core.runtime.OscMessage")
      }
      appendLine("import com.oscplatform.core.runtime.OscMessageCompanion")
      appendLine("import com.oscplatform.core.runtime.oscTyped")
      if (hasScalarArrayArgs) appendLine("import com.oscplatform.core.runtime.oscTypedList")
      if (hasTupleArrayArgs) appendLine("import com.oscplatform.core.runtime.oscTypedMapList")
      appendLine()

      // --- class header ---
      // sealed interface 指定時は sealed interface 名を使用し、未指定時は OscMessage を直接実装
      val superType = sealedInterfaceName ?: "OscMessage"
      appendLine("data class $className(")
      constructorArgs.forEach { node ->
        when (node) {
          is ScalarArgNode -> appendLine("    val ${node.name}: ${oscTypeToKotlin(node.type)},")
          is ArrayArgNode ->
              appendLine("    val ${node.name}: List<${arrayElementTypeName(node)}>,")
        }
      }
      appendLine(") : $superType {")

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
            appendLine("                ${node.name} = args.oscTyped<$kt>(\"${node.name}\", NAME),")
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

  /**
   * スキーマ内の全メッセージクラスを束ねる sealed interface のソースを生成する。
   *
   * 生成される sealed interface は [OscMessage] を継承し、各メッセージ data class がこの sealed interface
   * を実装することで、Kotlin の `when` 網羅性チェックを活用できる。
   *
   * @param interfaceName 生成する sealed interface の名前
   * @param messageClassNames スキーマ内の全メッセージクラス名リスト（KDoc コメント用）
   * @param packageName 生成コードのパッケージ名
   * @return Kotlin ソースコード文字列
   */
  fun generateSealedInterface(
      interfaceName: String,
      messageClassNames: List<String>,
      packageName: String,
  ): String = buildString {
    appendLine("package $packageName")
    appendLine()
    appendLine("import com.oscplatform.core.runtime.OscMessage")
    appendLine()
    // KDoc: sealed interface の説明と実装クラス一覧
    appendLine("/**")
    appendLine(" * スキーマで定義された全メッセージを表す sealed interface。")
    appendLine(" *")
    appendLine(" * [OscMessage] を継承しており、既存の `OscRuntime.on` / `send` API との互換性を保つ。")
    appendLine(" * Kotlin の `when` 式で網羅性チェックを活用できる。")
    appendLine(" *")
    appendLine(" * 実装クラス:")
    messageClassNames.forEach { name -> appendLine(" * - [$name]") }
    appendLine(" */")
    appendLine("sealed interface $interfaceName : OscMessage")
  }

  /**
   * sealed interface 向けの受信 helper 拡張関数を生成する。
   *
   * 生成される拡張関数は `OscRuntime.on<OscMessages> { ... }` という構文を提供し、 既存の `OscRuntime.on(companion,
   * handler)` を束ねて複数メッセージの受信登録を簡潔にする。
   *
   * @param interfaceName 生成済み sealed interface の名前
   * @param messageClassNames スキーマ内の全メッセージクラス名リスト
   * @param packageName 生成コードのパッケージ名
   * @return Kotlin ソースコード文字列
   */
  fun generateSealedRuntimeExtensions(
      interfaceName: String,
      messageClassNames: List<String>,
      packageName: String,
  ): String = buildString {
    appendLine("package $packageName")
    appendLine()
    appendLine("import com.oscplatform.core.runtime.OscRuntime")
    appendLine()
    appendLine("/**")
    appendLine(" * generated sealed interface を対象に型安全な受信ハンドラを登録する。")
    appendLine(" *")
    appendLine(" * `T` に [$interfaceName] を指定するとスキーマ内の全メッセージが登録され、")
    appendLine(" * 個別メッセージ型を指定するとその型だけを登録する。")
    appendLine(" *")
    appendLine(" * @param T 登録対象の generated メッセージ型または sealed interface")
    appendLine(" * @param handler 受信時に呼び出されるコールバック")
    appendLine(" */")
    appendLine("@Suppress(\"UNCHECKED_CAST\")")
    appendLine(
        "inline fun <reified T : $interfaceName> OscRuntime.on(noinline handler: suspend (T) -> Unit) {")
    appendLine("    when (T::class) {")
    appendLine("        $interfaceName::class -> {")
    messageClassNames.forEach { className ->
      appendLine("            on($className) { msg -> handler(msg as T) }")
    }
    appendLine("        }")
    messageClassNames.forEach { className ->
      appendLine("        $className::class -> on($className) { msg -> handler(msg as T) }")
    }
    appendLine("        else ->")
    appendLine(
        "            error(\"Unsupported generated OSC message type: ${'$'}{T::class.qualifiedName}\")")
    appendLine("    }")
    appendLine("}")
  }

  /**
   * 配列要素型の Kotlin 型名を返す。タプルの場合は nested class 名を返す。
   *
   * @param node 対象の配列引数ノード
   * @return Kotlin の型名文字列
   */
  private fun arrayElementTypeName(node: ArrayArgNode): String =
      when (val item = node.item) {
        is ArrayItemSpec.ScalarItem -> oscTypeToKotlin(item.type)
        is ArrayItemSpec.TupleItem -> toNestedClassName(node.name)
      }

  /**
   * メッセージ名からクラス名を導出する。
   *
   * 例: `"light.color"` → `"LightColor"`, `"mesh.dual"` → `"MeshDual"`
   *
   * @param name メッセージ名
   * @return PascalCase のクラス名
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
   *
   * @param arrayFieldName 配列フィールド名
   * @return nested class の PascalCase 名
   */
  private fun toNestedClassName(arrayFieldName: String): String {
    val cap = arrayFieldName.replaceFirstChar { it.uppercaseChar() }
    return if (cap.length > 1 && cap.endsWith("s")) cap.dropLast(1) else cap
  }

  /**
   * [OscType] を対応する Kotlin 型名に変換する。
   *
   * @param type 変換対象の OSC 型
   * @return Kotlin の型名文字列
   */
  private fun oscTypeToKotlin(type: OscType): String =
      when (type) {
        OscType.INT -> "Int"
        OscType.FLOAT -> "Float"
        OscType.STRING -> "String"
        OscType.BOOL -> "Boolean"
        OscType.BLOB -> "ByteArray"
      }

  /**
   * [OscBundleSpec] から Bundle ファサード data class のソースを生成する。
   *
   * 生成規則:
   * - バンドル名をアンダースコア/ドット区切りで分解し PascalCase に変換、末尾に `Bundle` を付与
   * - 各 messageRef に対応する生成メッセージクラスをコンストラクタパラメータとして列挙
   * - [OscBundle] を実装し `toMessages()` で `List<Pair<String, Map<String, Any?>>>` を構成
   * - companion object が [OscBundleCompanion] を実装し `NAME` を提供
   *
   * @param spec バンドル仕様
   * @param schema メッセージ解決に使用するスキーマ
   * @param packageName 生成コードのパッケージ名
   * @return Kotlin ソースコード文字列
   */
  fun generateBundle(spec: OscBundleSpec, schema: OscSchema, packageName: String): String {
    val className = toBundleClassName(spec.name)
    val params =
        spec.messageRefs.map { ref ->
          val msgSpec =
              schema.resolveMessage(ref)
                  ?: error("Bundle '${spec.name}' references unknown message '$ref'")
          val msgClass = toClassName(msgSpec.name)
          val paramName = toParamName(msgClass)
          Pair(paramName, msgClass)
        }

    return buildString {
      appendLine("package $packageName")
      appendLine()
      appendLine("import com.oscplatform.core.runtime.OscBundle")
      appendLine("import com.oscplatform.core.runtime.OscBundleCompanion")
      appendLine()
      appendLine("data class $className(")
      params.forEach { (paramName, msgClass) -> appendLine("    val $paramName: $msgClass,") }
      appendLine(") : OscBundle {")
      appendLine()
      appendLine("    override fun toMessages(): List<Pair<String, Map<String, Any?>>> = listOf(")
      params.forEach { (paramName, msgClass) ->
        appendLine("        $msgClass.NAME to $paramName.toNamedArgs(),")
      }
      appendLine("    )")
      appendLine()
      appendLine("    companion object : OscBundleCompanion<$className> {")
      appendLine("        override val NAME: String = \"${spec.name}\"")
      appendLine("    }")
      appendLine("}")
    }
  }

  /**
   * バンドル名から Bundle クラス名を導出する。
   *
   * 例: `"set_scene"` → `"SetSceneBundle"`, `"light.setup"` → `"LightSetupBundle"`
   *
   * @param name バンドル名
   * @return PascalCase の Bundle クラス名
   */
  fun toBundleClassName(name: String): String =
      name.split(Regex("[._\\-]")).joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
      } + "Bundle"

  /**
   * クラス名をコンストラクタパラメータ名（先頭小文字化）に変換する。
   *
   * @param className 変換対象のクラス名
   * @return 先頭が小文字のパラメータ名
   */
  private fun toParamName(className: String): String =
      className.replaceFirstChar { it.lowercaseChar() }
}
