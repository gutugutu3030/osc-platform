val coroutinesVersion: String by project
val jacksonVersion: String by project
val ktorVersion: String by project
val mcpKotlinVersion: String by project

tasks.named<ProcessResources>("processResources") {
  filesMatching("**/version.properties") { expand("projectVersion" to project.version) }
}

dependencies {
  implementation(project(":osc-core"))
  implementation(project(":osc-transport-udp"))
  implementation(project(":osc-adapter-webui"))
  implementation(platform("io.ktor:ktor-bom:$ktorVersion"))
  implementation("io.ktor:ktor-server-cio")
  implementation("io.ktor:ktor-server-content-negotiation")
  implementation("io.ktor:ktor-server-cors")
  implementation("io.ktor:ktor-serialization-kotlinx-json")
  implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpKotlinVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
  implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
  implementation("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")
  testImplementation("io.ktor:ktor-client-cio")
  testImplementation("io.ktor:ktor-sse")
  testImplementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpKotlinVersion")
  testImplementation(kotlin("test"))
}
