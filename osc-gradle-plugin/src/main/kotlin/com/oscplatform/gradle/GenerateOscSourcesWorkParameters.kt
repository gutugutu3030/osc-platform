package com.oscplatform.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters

/** [GenerateOscSourcesWorkAction] に渡すパラメータ。 */
interface GenerateOscSourcesWorkParameters : WorkParameters {
  val schemaFile: RegularFileProperty
  val packageName: Property<String>
  val language: Property<String>
  val outputDirectory: DirectoryProperty
}
