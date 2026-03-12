package com.oscplatform.core.runtime

/** codegen が生成するメッセージクラスが実装するインターフェース。 インスタンスが自身を namedArgs 形式にシリアライズできることを表す。 */
interface OscMessage {
  fun toNamedArgs(): Map<String, Any?>
}

/**
 * codegen が生成するメッセージクラスのコンパニオンオブジェクトが実装するインターフェース。 メッセージのメタ情報（NAME・PATH）と namedArgs
 * からのデシリアライズを提供する。
 */
interface OscMessageCompanion<T : OscMessage> {
  val NAME: String
  val PATH: String

  fun fromNamedArgs(args: Map<String, Any?>): T
}
