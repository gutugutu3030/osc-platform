pluginManagement {
  val sharedVersions =
      java.util.Properties().apply { file("../../gradle.properties").inputStream().use(::load) }
  val kotlinVersion =
      sharedVersions.getProperty("kotlinVersion")
          ?: error("kotlinVersion is missing in ../../gradle.properties")

  // osc-gradle-plugin を複合ビルドから解決する
  includeBuild("../..") // provides com.oscplatform.schema-codegen plugin
  plugins { kotlin("jvm") version kotlinVersion }
}

rootProject.name = "kotlin-quickstart-loopback"

// 親リポジトリ (osc-platform) をコンポジットビルドとして取り込み、
// com.oscplatform:* は親の gradle.properties に定義された版で解決する。
includeBuild("../..")
