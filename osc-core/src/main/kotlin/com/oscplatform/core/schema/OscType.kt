package com.oscplatform.core.schema

/** OSCプロトコルでサポートされるデータ型を表す列挙型。 */
enum class OscType {
  /** 32ビット整数型。 */
  INT,

  /** 32ビット浮動小数点数型。 */
  FLOAT,

  /** 文字列型。 */
  STRING,

  /** 真偽値型。 */
  BOOL,

  /** バイナリデータ型。 */
  BLOB,
  ;

  /** ファクトリメソッドを提供するコンパニオンオブジェクト。 */
  companion object {
    /**
     * トークン文字列からOSC型を解決する。
     *
     * `"int"`, `"integer"`, `"i"` → [INT]、`"float"`, `"f"` → [FLOAT] など、 複数のエイリアスをサポートする。
     *
     * @param token 型を表すトークン文字列
     * @return 対応する [OscType]
     * @throws IllegalStateException サポートされていないトークンの場合
     */
    fun fromToken(token: String): OscType {
      return when (token.trim().lowercase()) {
        "int",
        "integer",
        "i" -> INT
        "float",
        "f" -> FLOAT
        "string",
        "str",
        "s" -> STRING
        "bool",
        "boolean" -> BOOL
        "blob",
        "bytes" -> BLOB
        else -> error("Unsupported OSC type: $token")
      }
    }
  }
}
