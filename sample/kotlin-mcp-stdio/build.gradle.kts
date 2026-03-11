plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

dependencies {
  // MCP アダプタ (osc-adapter-mcp) を経由して osc-core / osc-transport-udp も解決される
  testImplementation("com.oscplatform:osc-adapter-mcp:0.4.0")

  // runBlocking に必要（osc-adapter-mcp の implementation 依存はコンパイルパスに来ない）
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

  // Jackson 3.x — JSON-RPC レスポンスのパースに使用
  testImplementation("tools.jackson.core:jackson-databind:3.1.0")
  testImplementation("tools.jackson.module:jackson-module-kotlin:3.1.0")

  testImplementation(kotlin("test"))
}

tasks.withType<Test> { useJUnitPlatform() }
