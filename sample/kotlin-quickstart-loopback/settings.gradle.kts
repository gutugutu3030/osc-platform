pluginManagement {
  // osc-gradle-plugin を複合ビルドから解決する
  includeBuild("../..") // provides com.oscplatform.schema-codegen plugin
  plugins { kotlin("jvm") version "2.1.20" }
}

rootProject.name = "kotlin-quickstart-loopback"

// 親リポジトリ (osc-platform) をコンポジットビルドとして取り込み、
// com.oscplatform:*:0.3.0 をローカルビルド成果物で解決する。
includeBuild("../..")
