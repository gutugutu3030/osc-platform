package com.oscplatform.codegen

/**
 * コード生成のオプション。
 *
 * @property packageName 生成コードのパッケージ名
 * @property language 生成対象の言語（デフォルト: "kotlin"）
 */
data class CodeGenOptions(
    val packageName: String,
    val language: String = "kotlin",
)
