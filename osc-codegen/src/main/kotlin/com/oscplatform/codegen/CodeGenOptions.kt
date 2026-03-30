package com.oscplatform.codegen

/**
 * コード生成のオプション。
 *
 * @property packageName 生成コードのパッケージ名
 * @property language 生成対象の言語（デフォルト: "kotlin"）
 * @property sealedInterfaceName 生成する sealed interface の名前。`null` の場合は sealed interface
 *   を生成しない（デフォルト: `null`）
 */
data class CodeGenOptions(
    val packageName: String,
    val language: String = "kotlin",
    val sealedInterfaceName: String? = null,
)
