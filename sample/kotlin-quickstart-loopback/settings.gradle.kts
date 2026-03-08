pluginManagement { plugins { kotlin("jvm") version "2.1.20" } }

rootProject.name = "kotlin-quickstart-loopback"

// 親リポジトリ (osc-platform) をコンポジットビルドとして取り込み、
// com.oscplatform:*:0.2.0 をローカルビルド成果物で解決する。
includeBuild("../..")
