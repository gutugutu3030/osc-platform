plugins {
  kotlin("jvm") apply false
  id("com.diffplug.spotless")
}

val coroutinesVersion: String by project
val jacksonVersion: String by project
val jvmToolchainVersion: String by project
val kotlinVersion: String by project
val projectVersion: String by project

allprojects {
  group = "com.oscplatform"
  version = projectVersion

  repositories { mavenCentral() }
}

subprojects {
  apply(plugin = "org.jetbrains.kotlin.jvm")

  extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(jvmToolchainVersion.toInt())
  }

  tasks.withType<Test> { useJUnitPlatform() }
}

tasks.register("syncVersionReferences") {
  group = "documentation"
  description = "Sync versioned Markdown snippets from gradle.properties."

  doLast {
    fun managedBlock(name: String, content: String): String = buildString {
      appendLine("<!-- version-sync:$name:start -->")
      appendLine(content.trimEnd())
      append("<!-- version-sync:$name:end -->")
    }

    fun syncManagedBlock(relativePath: String, name: String, content: String) {
      val file = layout.projectDirectory.file(relativePath).asFile
      val existing = file.readText()
      val pattern =
          Regex(
              "<!-- version-sync:${Regex.escape(name)}:start -->.*?<!-- version-sync:${Regex.escape(name)}:end -->",
              setOf(RegexOption.DOT_MATCHES_ALL),
          )
      check(pattern.containsMatchIn(existing)) {
        "Managed block '$name' was not found in $relativePath"
      }
      file.writeText(existing.replace(pattern, managedBlock(name, content)))
    }

    val externalGradleUsage =
        """
        ```kotlin
        plugins {
            kotlin("jvm") version "$kotlinVersion"
            id("com.oscplatform.schema-codegen") version "$projectVersion"
        }

        dependencies {
            implementation("com.oscplatform:osc-core:$projectVersion")
            implementation("com.oscplatform:osc-transport-udp:$projectVersion")
        }

        oscSchemaCodegen {
            schema.set(layout.projectDirectory.file("schema.yaml"))
            packageName.set("com.example.osc.generated")
            language.set("kotlin")   // default: "kotlin"
            // outputDir は省略可 (default: build/generated/sources/osc/main/kotlin)
        }
        ```
        """
            .trimIndent()

    val readmeTechStack =
        """
        - Kotlin `$kotlinVersion`
        - JVM Toolchain `$jvmToolchainVersion`
        - Coroutines: `kotlinx-coroutines-core:$coroutinesVersion`
        - YAML/JSON: Jackson
          - `jackson-databind:$jacksonVersion`
          - `jackson-module-kotlin:$jacksonVersion`
          - `jackson-dataformat-yaml:$jacksonVersion`
        - KTS loader: `kotlin-scripting-jsr223:$kotlinVersion`
        - Build: Gradle Kotlin DSL
        """
            .trimIndent()

    val quickstartSupportNote =
        """
        **v$projectVersion 以降:** `osc-gradle-plugin` による Kotlin クラス自動生成に対応しました。
        """
            .trimIndent()

    syncManagedBlock("README.md", "tech-stack", readmeTechStack)
    syncManagedBlock("README.md", "external-gradle-usage", externalGradleUsage)
    syncManagedBlock("feature.md", "external-gradle-usage", externalGradleUsage)
    syncManagedBlock(
        "sample/kotlin-quickstart-loopback/README.md",
        "plugin-support-note",
        quickstartSupportNote,
    )
  }
}

tasks.register("syncVersion") {
  group = "documentation"
  description = "Sync version-managed files after projectVersion changes."
  dependsOn("syncVersionReferences")
}

spotless {
  kotlin {
    target("**/*.kt")
    targetExclude("**/node_modules/**/*.kt")
    ktfmt("0.54")
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    targetExclude("**/node_modules/**/*.gradle.kts")
    ktfmt("0.54")
  }
}
