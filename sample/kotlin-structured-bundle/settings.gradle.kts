pluginManagement {
  val sharedVersions =
      java.util.Properties().apply { file("../../gradle.properties").inputStream().use(::load) }
  val kotlinVersion =
      sharedVersions.getProperty("kotlinVersion")
          ?: error("kotlinVersion is missing in ../../gradle.properties")

  // osc-gradle-plugin を複合ビルドから解決する
  includeBuild("../..")
  plugins { kotlin("jvm") version kotlinVersion }
}

rootProject.name = "kotlin-structured-bundle"

includeBuild("../..")
