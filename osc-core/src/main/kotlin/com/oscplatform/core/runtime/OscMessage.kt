package com.oscplatform.core.runtime

/** codegen が生成するメッセージクラスが実装するインターフェース。 インスタンスが自身を namedArgs 形式にシリアライズできることを表す。 */
interface OscMessage {
  /**
   * メッセージの引数を名前付きマップ形式にシリアライズする。
   *
   * @return 引数名をキー、引数値を値とするマップ
   */
  fun toNamedArgs(): Map<String, Any?>
}

/**
 * codegen が生成するメッセージクラスのコンパニオンオブジェクトが実装するインターフェース。 メッセージのメタ情報（NAME・PATH）と namedArgs
 * からのデシリアライズを提供する。
 */
interface OscMessageCompanion<T : OscMessage> {
  /** メッセージのスキーマ定義名。 */
  val NAME: String

  /** メッセージの OSC アドレスパス。 */
  val PATH: String

  /**
   * 名前付き引数マップからメッセージインスタンスを復元する。
   *
   * @param args 引数名をキー、引数値を値とするマップ
   * @return デシリアライズされたメッセージインスタンス
   */
  fun fromNamedArgs(args: Map<String, Any?>): T
}
