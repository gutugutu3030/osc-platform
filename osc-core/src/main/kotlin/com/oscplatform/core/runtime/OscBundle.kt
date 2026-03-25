package com.oscplatform.core.runtime

/** codegen が生成するバンドルクラスが実装するインターフェース。 複数メッセージをアトミック送信するための [OscRuntime.sendBundle] に渡せる。 */
interface OscBundle {
  /**
   * バンドルに含まれる全メッセージを (メッセージ参照名, 引数マップ) のペアリストに変換する。
   *
   * @return メッセージ参照名と名前付き引数マップのペアリスト
   */
  fun toMessages(): List<Pair<String, Map<String, Any?>>>
}

/** codegen が生成するバンドルクラスのコンパニオンオブジェクトが実装するインターフェース。 バンドルのスキーマ定義名を提供する。 */
interface OscBundleCompanion<T : OscBundle> {
  /** バンドルのスキーマ定義名。 */
  val NAME: String
}
