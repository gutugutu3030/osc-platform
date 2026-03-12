package com.oscplatform.core.runtime

/**
 * codegen が生成する [OscMessage.fromNamedArgs] 内で使う型安全キャストヘルパー。
 *
 * namedArgs の値を [OscType] に対応する Kotlin 型で取り出す。 通常の `as T` キャストと違い、以下の情報を含む詳細なエラーを早期に投げる:
 * - どのメッセージの (`messageName`)
 * - どの引数が (`key`)
 * - 期待する型は何か (`expected ...`)
 * - 実際に入っていた型は何か (`got ...`)
 */

/**
 * スカラー引数を安全に取り出す。
 *
 * @throws IllegalArgumentException キーが存在しない、または型が一致しない場合
 */
inline fun <reified T : Any> Map<String, Any?>.oscTyped(key: String, messageName: String): T {
  val value =
      this[key]
          ?: throw IllegalArgumentException(
              "[$messageName] Required arg '$key' is missing from namedArgs",
          )
  return value as? T
      ?: throw IllegalArgumentException(
          "[$messageName] Type mismatch for '$key':" +
              " expected ${T::class.simpleName}, got ${value::class.simpleName} (value=$value)",
      )
}

/**
 * スカラー要素の配列引数を安全に取り出す。
 *
 * JVM の型消去により要素型の完全な検証はできないが、先頭要素で型チェックを行い 不正な要素を早期に検出する。
 *
 * @throws IllegalArgumentException キーが存在しない、List でない、または先頭要素の型が一致しない場合
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> Map<String, Any?>.oscTypedList(
    key: String,
    messageName: String,
): List<T> {
  val value =
      this[key]
          ?: throw IllegalArgumentException(
              "[$messageName] Required arg '$key' is missing from namedArgs",
          )
  if (value !is List<*>) {
    throw IllegalArgumentException(
        "[$messageName] Expected List for '$key', got ${value::class.simpleName}",
    )
  }
  value.firstOrNull()?.let { first ->
    if (first !is T) {
      throw IllegalArgumentException(
          "[$messageName] Element type mismatch in list '$key':" +
              " expected ${T::class.simpleName}, got ${first::class.simpleName}",
      )
    }
  }
  return value as List<T>
}

/**
 * タプル配列引数 (`List<Map<String, Any?>>`) を安全に取り出す。
 *
 * 全要素が [Map] であることを検証してから返す。
 *
 * @throws IllegalArgumentException キーが存在しない、List でない、または要素が Map でない場合
 */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.oscTypedMapList(
    key: String,
    messageName: String,
): List<Map<String, Any?>> {
  val value =
      this[key]
          ?: throw IllegalArgumentException(
              "[$messageName] Required arg '$key' is missing from namedArgs",
          )
  if (value !is List<*>) {
    throw IllegalArgumentException(
        "[$messageName] Expected List for '$key', got ${value::class.simpleName}",
    )
  }
  value.forEachIndexed { i, elem ->
    if (elem !is Map<*, *>) {
      throw IllegalArgumentException(
          "[$messageName] Element at index $i in list '$key' is not a Map:" +
              " got ${elem?.let { it::class.simpleName } ?: "null"}",
      )
    }
  }
  return value as List<Map<String, Any?>>
}
