package com.oscplatform.core.schema.dsl

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscBundleSpec
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscNaming
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.schema.TupleFieldSpec

/**
 * OSC スキーマ DSL のスコープ制御用マーカーアノテーション。
 *
 * このアノテーションが付与されたビルダークラスは、ネストされたラムダ内で 外側のレシーバーのメンバーへ暗黙的にアクセスすることを禁止する。 外側のスコープにアクセスする場合はラベル付き
 * `this` (`this@OuterBuilder`) を 明示的に指定する必要がある。
 *
 * これにより、IDE の補完候補が現在のスコープのメンバーのみに絞り込まれ、 DSL の誤用（例: `message {}` ブロック内で `bundle()` を呼ぶなど）を
 * コンパイル時に検出できる。
 */
@DslMarker @Target(AnnotationTarget.CLASS) annotation class OscSchemaDslMarker

/** DSLで使用する [OscType.INT] のエイリアス。 */
val INT: OscType = OscType.INT

/** DSLで使用する [OscType.FLOAT] のエイリアス。 */
val FLOAT: OscType = OscType.FLOAT

/** DSLで使用する [OscType.STRING] のエイリアス。 */
val STRING: OscType = OscType.STRING

/** DSLで使用する [OscType.BOOL] のエイリアス。 */
val BOOL: OscType = OscType.BOOL

/** DSLで使用する [OscType.BLOB] のエイリアス。 */
val BLOB: OscType = OscType.BLOB

/** DSLで使用する [ScalarRole.VALUE] のエイリアス。 */
val VALUE: ScalarRole = ScalarRole.VALUE

/** DSLで使用する [ScalarRole.LENGTH] のエイリアス。 */
val LENGTH: ScalarRole = ScalarRole.LENGTH

/** OSCスキーマをDSLで構築するためのビルダークラス。 */
@OscSchemaDslMarker
class OscSchemaBuilder {
  private val messages = mutableListOf<OscMessageSpec>()
  private val bundles = mutableListOf<OscBundleSpec>()

  /**
   * メッセージ定義を追加する。
   *
   * @param path OSCアドレスパス
   * @param block メッセージビルダーに対する設定ブロック
   */
  fun message(path: String, block: OscMessageBuilder.() -> Unit) {
    val builder = OscMessageBuilder(path)
    builder.block()
    messages += builder.build()
  }

  /**
   * バンドル定義を追加する。
   *
   * @param name バンドル名
   * @param block バンドルビルダーに対する設定ブロック
   */
  fun bundle(name: String, block: OscBundleBuilder.() -> Unit) {
    val builder = OscBundleBuilder(name)
    builder.block()
    bundles += builder.build()
  }

  /**
   * 定義されたメッセージとバンドルから [OscSchema] を構築する。
   *
   * @return 構築された [OscSchema]
   */
  internal fun build(): OscSchema =
      OscSchema(messages = messages.toList(), bundles = bundles.toList())
}

/**
 * OSCメッセージをDSLで構築するためのビルダークラス。
 *
 * @param rawPath 未正規化のOSCアドレスパス
 */
@OscSchemaDslMarker
class OscMessageBuilder(
    private val rawPath: String,
) {
  private var explicitName: String? = null
  private var textDescription: String? = null
  private val args = mutableListOf<OscArgNode>()

  /**
   * メッセージの論理名を設定する。
   *
   * @param value メッセージ名
   */
  fun name(value: String) {
    explicitName = value.trim()
  }

  /**
   * メッセージの説明を設定する。
   *
   * @param value 説明文
   */
  fun description(value: String) {
    textDescription = value.trim()
  }

  /**
   * スカラー引数を追加する。
   *
   * @param name 引数名
   * @param type OSC型
   * @param role スカラーの役割（デフォルトは [ScalarRole.VALUE]）
   */
  fun scalar(
      name: String,
      type: OscType,
      role: ScalarRole = ScalarRole.VALUE,
  ) {
    args += ScalarArgNode(name = name.trim(), type = type, role = role)
  }

  /**
   * スカラー引数を追加する（[scalar] のエイリアス）。
   *
   * @param name 引数名
   * @param type OSC型
   */
  fun arg(name: String, type: OscType) {
    scalar(name = name, type = type)
  }

  /**
   * 配列引数を追加する。
   *
   * [length] または [lengthFrom] のいずれか一方を指定する必要がある。
   *
   * @param name 引数名
   * @param length 固定長（省略可能）
   * @param lengthFrom 長さを参照する先行フィールド名（省略可能）
   * @param block 配列要素ビルダーに対する設定ブロック
   * @throws IllegalArgumentException [length] と [lengthFrom] の両方が指定された場合、または両方とも未指定の場合
   */
  fun array(
      name: String,
      length: Int? = null,
      lengthFrom: String? = null,
      block: ArrayItemBuilder.() -> Unit,
  ) {
    require(!(length != null && !lengthFrom.isNullOrBlank())) {
      "array('$name') cannot define both length and lengthFrom"
    }

    val resolvedLength =
        when {
          length != null -> LengthSpec.Fixed(length)
          !lengthFrom.isNullOrBlank() -> LengthSpec.FromField(lengthFrom.trim())
          else ->
              throw IllegalArgumentException(
                  "array('$name') must define either length or lengthFrom")
        }

    val item = ArrayItemBuilder().apply(block).build()
    args += ArrayArgNode(name = name.trim(), length = resolvedLength, item = item)
  }

  /**
   * 設定された内容から [OscMessageSpec] を構築する。
   *
   * @return 構築された [OscMessageSpec]
   * @throws IllegalArgumentException 引数名に重複がある場合
   */
  internal fun build(): OscMessageSpec {
    val normalizedPath = OscSchema.normalizePath(rawPath)
    val resolvedName =
        explicitName?.takeIf { it.isNotBlank() } ?: OscNaming.defaultMessageName(normalizedPath)
    val duplicateArgs = args.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicateArgs.isEmpty()) {
      "Duplicate args for '$normalizedPath': ${duplicateArgs.joinToString()}"
    }

    return OscMessageSpec(
        path = normalizedPath,
        name = resolvedName,
        description = textDescription,
        args = args.toList(),
    )
  }
}

/** 配列要素をDSLで構築するためのビルダークラス。 */
@OscSchemaDslMarker
class ArrayItemBuilder {
  private var item: ArrayItemSpec? = null

  /**
   * スカラー型の配列要素を定義する。
   *
   * @param type 要素のOSC型
   * @throws IllegalArgumentException 既に要素が定義されている場合
   */
  fun scalar(type: OscType) {
    require(item == null) { "Array item is already defined" }
    item = ArrayItemSpec.ScalarItem(type)
  }

  /**
   * タプル型の配列要素を定義する。
   *
   * @param block タプルフィールドビルダーに対する設定ブロック
   * @throws IllegalArgumentException 既に要素が定義されている場合
   */
  fun tuple(block: TupleFieldBuilder.() -> Unit) {
    require(item == null) { "Array item is already defined" }
    val fields = TupleFieldBuilder().apply(block).build()
    item = ArrayItemSpec.TupleItem(fields = fields)
  }

  /**
   * 設定された内容から [ArrayItemSpec] を構築する。
   *
   * @return 構築された [ArrayItemSpec]
   * @throws IllegalArgumentException 要素が定義されていない場合
   */
  internal fun build(): ArrayItemSpec {
    return item ?: throw IllegalArgumentException("Array item definition is required")
  }
}

/** タプルフィールドをDSLで構築するためのビルダークラス。 */
@OscSchemaDslMarker
class TupleFieldBuilder {
  private val fields = mutableListOf<TupleFieldSpec>()

  /**
   * タプルにフィールドを追加する。
   *
   * @param name フィールド名
   * @param type フィールドのOSC型
   */
  fun field(name: String, type: OscType) {
    fields += TupleFieldSpec(name = name.trim(), type = type)
  }

  /**
   * 設定されたフィールドからフィールド仕様リストを構築する。
   *
   * @return [TupleFieldSpec] のリスト
   */
  internal fun build(): List<TupleFieldSpec> = fields.toList()
}

/**
 * OSCスキーマをDSLで定義するためのトップレベル関数。
 *
 * @param block スキーマビルダーに対する設定ブロック
 * @return 構築された [OscSchema]
 */
fun oscSchema(block: OscSchemaBuilder.() -> Unit): OscSchema {
  val builder = OscSchemaBuilder()
  builder.block()
  return builder.build()
}

/**
 * OSCバンドルをDSLで構築するためのビルダークラス。
 *
 * @param rawName 未整形のバンドル名
 */
@OscSchemaDslMarker
class OscBundleBuilder(private val rawName: String) {
  private var textDescription: String? = null
  private val refs = mutableListOf<String>()

  /**
   * バンドルの説明を設定する。
   *
   * @param value 説明文
   */
  fun description(value: String) {
    textDescription = value.trim()
  }

  /**
   * バンドルにメッセージ参照を追加する。
   *
   * @param ref メッセージへの参照（パスまたは名前）
   */
  fun message(ref: String) {
    refs += ref.trim()
  }

  /**
   * 設定された内容から [OscBundleSpec] を構築する。
   *
   * @return 構築された [OscBundleSpec]
   */
  internal fun build(): OscBundleSpec =
      OscBundleSpec(
          name = rawName.trim(),
          description = textDescription,
          messageRefs = refs.toList(),
      )
}
