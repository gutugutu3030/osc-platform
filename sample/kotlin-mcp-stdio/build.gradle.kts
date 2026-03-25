import java.util.Properties

val sharedVersions =
    Properties().apply { file("../../gradle.properties").inputStream().use(::load) }

fun sharedVersion(name: String): String =
    sharedVersions.getProperty(name) ?: error("$name is missing in ../../gradle.properties")

val jvmToolchainVersion = sharedVersion("jvmToolchainVersion").toInt()
val oscPlatformVersion = sharedVersion("projectVersion")
val coroutinesVersion = sharedVersion("coroutinesVersion")
val jacksonVersion = sharedVersion("jacksonVersion")

plugins { kotlin("jvm") }

kotlin { jvmToolchain(jvmToolchainVersion) }

repositories { mavenCentral() }

dependencies {
  // MCP アダプタ (osc-adapter-mcp) を経由して osc-core / osc-transport-udp も解決される
  testImplementation("com.oscplatform:osc-adapter-mcp:$oscPlatformVersion")

  // runBlocking に必要（osc-adapter-mcp の implementation 依存はコンパイルパスに来ない）
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

  // Jackson 3.x — JSON-RPC レスポンスのパースに使用
  testImplementation("tools.jackson.core:jackson-databind:$jacksonVersion")
  testImplementation("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")

  testImplementation(kotlin("test"))
}

tasks.withType<Test> { useJUnitPlatform() }
