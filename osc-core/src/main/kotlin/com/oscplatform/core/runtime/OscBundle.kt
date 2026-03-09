package com.oscplatform.core.runtime

/**
 * codegen が生成するバンドルクラスが実装するインターフェース。
 * 複数メッセージをアトミック送信するための [OscRuntime.sendBundle] に渡せる。
 */
interface OscBundle {
  fun toMessages(): List<Pair<String, Map<String, Any?>>>
}

/**
 * codegen が生成するバンドルクラスのコンパニオンオブジェクトが実装するインターフェース。
 * バンドルのスキーマ定義名を提供する。
 */
interface OscBundleCompanion<T : OscBundle> {
  val NAME: String
}
