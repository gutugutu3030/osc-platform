package com.oscplatform.core.schema

/**
 * OSCメッセージの引数ノードを表すシールドインターフェース。
 *
 * スカラー引数 ([ScalarArgNode]) または配列引数 ([ArrayArgNode]) のいずれかとして具体化される。
 */
sealed interface OscArgNode {
  /** 引数の名前。スキーマ内で一意でなければならない。 */
  val name: String
}

/**
 * 単一のスカラー値を持つ引数ノード。
 *
 * @property name 引数の名前
 * @property type OSC型
 * @property role スカラーの役割（値またはLength参照）
 */
data class ScalarArgNode(
    override val name: String,
    val type: OscType,
    val role: ScalarRole = ScalarRole.VALUE,
) : OscArgNode

/** スカラー引数の役割を示す列挙型。 */
enum class ScalarRole {
  /** 通常の値フィールド。 */
  VALUE,

  /** 配列の長さを示すフィールド。 */
  LENGTH,
}

/**
 * 配列型の引数ノード。
 *
 * @property name 引数の名前
 * @property length 配列の長さ指定
 * @property item 配列要素の仕様
 */
data class ArrayArgNode(
    override val name: String,
    val length: LengthSpec,
    val item: ArrayItemSpec,
) : OscArgNode

/**
 * 配列の長さ指定を表すシールドインターフェース。
 *
 * 固定長 ([Fixed]) または他フィールド参照 ([FromField]) のいずれかで指定される。
 */
sealed interface LengthSpec {
  /**
   * 固定長の配列。
   *
   * @property size 配列の要素数
   */
  data class Fixed(
      val size: Int,
  ) : LengthSpec

  /**
   * 先行するスカラーフィールドの値を長さとして参照する配列。
   *
   * @property fieldName 参照先のフィールド名
   */
  data class FromField(
      val fieldName: String,
  ) : LengthSpec
}

/**
 * 配列要素の仕様を表すシールドインターフェース。
 *
 * スカラー要素 ([ScalarItem]) またはタプル要素 ([TupleItem]) のいずれかで構成される。
 */
sealed interface ArrayItemSpec {
  /**
   * 単一型のスカラー配列要素。
   *
   * @property type 要素のOSC型
   */
  data class ScalarItem(
      val type: OscType,
  ) : ArrayItemSpec

  /**
   * 名前付きフィールドを持つタプル配列要素。
   *
   * @property fields タプルのフィールド仕様リスト
   */
  data class TupleItem(
      val fields: List<TupleFieldSpec>,
  ) : ArrayItemSpec
}

/**
 * タプル要素内のフィールド仕様。
 *
 * @property name フィールド名
 * @property type フィールドのOSC型
 */
data class TupleFieldSpec(
    val name: String,
    val type: OscType,
)

/** OSC引数ノードのバリデーションを行うオブジェクト。 */
internal object OscArgNodeValidator {
  /**
   * 引数リストの整合性を検証する。
   *
   * 名前の重複、空白名、配列長の制約、タプルフィールドの重複などを検証する。
   *
   * @param path 検証対象のOSCメッセージパス
   * @param args 検証する引数ノードのリスト
   * @throws IllegalArgumentException バリデーションに失敗した場合
   */
  fun validate(path: String, args: List<OscArgNode>) {
    val duplicateNames = args.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicateNames.isEmpty()) {
      "Duplicate args for '$path': ${duplicateNames.joinToString()}"
    }

    args.forEachIndexed { index, node ->
      require(node.name.isNotBlank()) { "Arg name cannot be blank in '$path'" }

      when (node) {
        is ScalarArgNode -> {
          // No extra constraints for scalar value fields.
        }

        is ArrayArgNode -> {
          when (val length = node.length) {
            is LengthSpec.Fixed -> {
              require(length.size >= 0) {
                "Array length must be >= 0 for '${node.name}' in '$path'"
              }
            }

            is LengthSpec.FromField -> {
              require(length.fieldName.isNotBlank()) {
                "lengthFrom cannot be blank for '${node.name}' in '$path'"
              }

              val referenced =
                  args.take(index).firstOrNull { it.name == length.fieldName }
                      ?: throw IllegalArgumentException(
                          "lengthFrom '${length.fieldName}' for '${node.name}' must reference a previous scalar LENGTH field in '$path'",
                      )

              require(
                  referenced is ScalarArgNode &&
                      referenced.role == ScalarRole.LENGTH &&
                      referenced.type == OscType.INT) {
                    "lengthFrom '${length.fieldName}' for '${node.name}' must reference INT scalar with role=LENGTH in '$path'"
                  }
            }
          }

          when (val item = node.item) {
            is ArrayItemSpec.ScalarItem -> {
              // Scalar array items are fully specified by type.
            }

            is ArrayItemSpec.TupleItem -> {
              require(item.fields.isNotEmpty()) {
                "Tuple fields cannot be empty for '${node.name}' in '$path'"
              }
              val duplicateTupleNames =
                  item.fields.groupBy { it.name }.filterValues { it.size > 1 }.keys
              require(duplicateTupleNames.isEmpty()) {
                "Duplicate tuple field names for '${node.name}' in '$path': ${duplicateTupleNames.joinToString()}"
              }
              item.fields.forEach { field ->
                require(field.name.isNotBlank()) {
                  "Tuple field name cannot be blank for '${node.name}' in '$path'"
                }
              }
            }
          }
        }
      }
    }
  }
}
