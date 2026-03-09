pluginManagement {
  // osc-gradle-plugin を複合ビルドから解決する
  includeBuild("../..")
  plugins { kotlin("jvm") version "2.1.20" }
}

rootProject.name = "kotlin-structured-bundle"

includeBuild("../..")
