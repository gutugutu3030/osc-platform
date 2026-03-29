package com.oscplatform.scripting

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * `schema.kts` 専用の Kotlin script template。
 *
 * `fileExtension` は汎用 `.kts` を維持しつつ、`filePathPattern` で `schema.kts` のみを この script definition
 * に束縛する。
 */
@Suppress("unused")
@KotlinScript(
    fileExtension = "kts",
    filePathPattern = "(.*/)?schema\\.kts",
    compilationConfiguration = OscSchemaScriptCompilationConfiguration::class,
)
abstract class OscSchemaScript

/**
 * `schema.kts` 用のコンパイル設定。
 *
 * OSC DSL の default imports と、`osc-core` を含む classpath を定義する。
 */
object OscSchemaScriptCompilationConfiguration :
    ScriptCompilationConfiguration({
      defaultImports("com.oscplatform.core.schema.dsl.*")

      // IDE と scripting host の両方で DSL 型を解決できるよう、
      // script definition 自身の classpath から osc-core を含む依存を引き渡す。
      jvm { dependenciesFromClassContext(OscSchemaScript::class, wholeClasspath = true) }

      ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
    })
