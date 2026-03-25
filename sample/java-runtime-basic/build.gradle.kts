import java.util.Properties

val sharedVersions =
    Properties().apply { file("../../gradle.properties").inputStream().use(::load) }

fun sharedVersion(name: String): String =
    sharedVersions.getProperty(name) ?: error("$name is missing in ../../gradle.properties")

val jvmToolchainVersion = sharedVersion("jvmToolchainVersion").toInt()
val oscPlatformVersion = sharedVersion("projectVersion")
val coroutinesVersion = sharedVersion("coroutinesVersion")

plugins {
  kotlin("jvm")
  application
}

kotlin { jvmToolchain(jvmToolchainVersion) }

repositories { mavenCentral() }

dependencies {
  implementation("com.oscplatform:osc-core:$oscPlatformVersion")
  implementation("com.oscplatform:osc-transport-udp:$oscPlatformVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

// Java の App クラスをエントリポイントに指定
application { mainClass = "com.example.App" }
