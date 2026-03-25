package com.oscplatform.core.schema

/**
 * OSCメッセージの仕様を表すデータクラス。
 *
 * @property path OSCアドレスパス（例: `/synth/volume`）
 * @property name メッセージの論理名
 * @property description メッセージの説明（省略可能）
 * @property args 引数ノードのリスト
 */
data class OscMessageSpec(
    val path: String,
    val name: String,
    val description: String?,
    val args: List<OscArgNode>,
)

/**
 * OSCバンドルの仕様を表すデータクラス。
 *
 * 複数のメッセージをまとめて送信するためのグループ定義。
 *
 * @property name バンドルの論理名
 * @property description バンドルの説明（省略可能）
 * @property messageRefs 含まれるメッセージへの参照リスト（パスまたは名前）
 */
data class OscBundleSpec(
    val name: String,
    val description: String?,
    val messageRefs: List<String>,
)

/**
 * OSCスキーマ全体を表すデータクラス。
 *
 * メッセージ仕様とバンドル仕様を保持し、パスや名前による検索機能を提供する。
 *
 * @property messages メッセージ仕様のリスト
 * @property bundles バンドル仕様のリスト
 */
data class OscSchema(
    val messages: List<OscMessageSpec>,
    val bundles: List<OscBundleSpec> = emptyList(),
) {
  private val byPath: Map<String, OscMessageSpec> = messages.associateBy { normalizePath(it.path) }
  private val byName: Map<String, OscMessageSpec> = messages.associateBy { it.name }
  private val bundleByName: Map<String, OscBundleSpec> = bundles.associateBy { it.name }

  init {
    require(messages.isNotEmpty()) { "Schema must define at least one message" }

    val duplicatePaths =
        messages.groupBy { normalizePath(it.path) }.filterValues { it.size > 1 }.keys
    require(duplicatePaths.isEmpty()) { "Duplicate OSC paths: ${duplicatePaths.joinToString()}" }

    val duplicateNames = messages.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicateNames.isEmpty()) {
      "Duplicate message names: ${duplicateNames.joinToString()}"
    }

    messages.forEach { message ->
      OscArgNodeValidator.validate(normalizePath(message.path), message.args)
    }

    val duplicateBundleNames = bundles.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicateBundleNames.isEmpty()) {
      "Duplicate bundle names: ${duplicateBundleNames.joinToString()}"
    }

    bundles.forEach { bundle ->
      require(bundle.name.isNotBlank()) { "Bundle name cannot be blank" }
      require(bundle.messageRefs.isNotEmpty()) {
        "Bundle '${bundle.name}' must reference at least one message"
      }

      val seenArgNames = mutableSetOf<String>()
      bundle.messageRefs.forEach { ref ->
        val spec =
            resolveMessage(ref)
                ?: throw IllegalArgumentException(
                    "Bundle '${bundle.name}' references unknown message '$ref'",
                )
        spec.args.forEach { argNode ->
          require(seenArgNames.add(argNode.name)) {
            "Bundle '${bundle.name}': arg name collision '${argNode.name}' across messages"
          }
        }
      }
    }
  }

  /**
   * パスまたは名前でメッセージ仕様を解決する。
   *
   * `/` で始まる参照はパスとして、それ以外は名前として検索される。
   *
   * @param ref メッセージへの参照（パスまたは名前）
   * @return 一致する [OscMessageSpec]。見つからない場合は `null`
   */
  fun resolveMessage(ref: String): OscMessageSpec? {
    val normalizedRef = ref.trim()
    return if (normalizedRef.startsWith("/")) {
      byPath[normalizePath(normalizedRef)]
    } else {
      byName[normalizedRef]
    }
  }

  /**
   * 正規化されたパスでメッセージ仕様を検索する。
   *
   * @param path OSCアドレスパス
   * @return 一致する [OscMessageSpec]。見つからない場合は `null`
   */
  fun findByPath(path: String): OscMessageSpec? = byPath[normalizePath(path)]

  /**
   * 名前でバンドル仕様を検索する。
   *
   * @param name バンドル名
   * @return 一致する [OscBundleSpec]。見つからない場合は `null`
   */
  fun findBundle(name: String): OscBundleSpec? = bundleByName[name]

  /** パス正規化などのユーティリティメソッドを提供するコンパニオンオブジェクト。 */
  companion object {
    /**
     * OSCパスを正規化する。
     *
     * 前後の空白を除去し、末尾のスラッシュを削除し、先頭にスラッシュを付与する。
     *
     * @param path 正規化対象のOSCパス
     * @return 正規化されたパス文字列
     */
    fun normalizePath(path: String): String {
      val cleaned = path.trim().trimEnd('/').ifEmpty { "/" }
      return if (cleaned.startsWith("/")) cleaned else "/$cleaned"
    }
  }
}

/** OSCパスからメッセージ名やツール名を生成する命名ユーティリティ。 */
object OscNaming {
  /**
   * OSCパスからデフォルトのメッセージ名を生成する。
   *
   * パスセグメントをドットで結合した名前を返す（例: `/synth/volume` → `synth.volume`）。
   *
   * @param path OSCアドレスパス
   * @return 生成されたメッセージ名
   * @throws IllegalArgumentException パスが `"/"` のみの場合
   */
  fun defaultMessageName(path: String): String {
    val normalized = OscSchema.normalizePath(path)
    val body = normalized.removePrefix("/")
    require(body.isNotBlank()) { "OSC path '/' cannot be converted into a message name" }
    return body.split('/').filter { it.isNotBlank() }.joinToString(".")
  }

  /**
   * OSCパスからMCPツール名を生成する。
   *
   * `set_` プレフィックスを付与し、パスセグメントをアンダースコアで結合する（例: `/synth/volume` → `set_synth_volume`）。
   *
   * @param path OSCアドレスパス
   * @return 生成されたMCPツール名
   * @throws IllegalArgumentException パスが `"/"` のみの場合
   */
  fun mcpToolName(path: String): String {
    val normalized = OscSchema.normalizePath(path)
    val body = normalized.removePrefix("/")
    require(body.isNotBlank()) { "OSC path '/' cannot be converted into an MCP tool name" }
    return "set_" + body.split('/').filter { it.isNotBlank() }.joinToString("_")
  }

  /**
   * バンドル名からMCPツール名を生成する。
   *
   * `bundle_` プレフィックスを付与し、英数字・アンダースコア以外をアンダースコアに変換する。
   *
   * @param name バンドル名
   * @return 生成されたバンドルツール名
   * @throws IllegalArgumentException 名前が空白の場合
   */
  fun bundleToolName(name: String): String {
    require(name.isNotBlank()) { "Bundle name cannot be blank" }
    return "bundle_" + name.trim().replace(Regex("[^a-zA-Z0-9_]"), "_")
  }
}
