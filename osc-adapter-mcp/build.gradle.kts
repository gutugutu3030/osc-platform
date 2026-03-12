val coroutinesVersion: String by project
val jacksonVersion: String by project

tasks.named<ProcessResources>("processResources") {
  filesMatching("**/version.properties") { expand("projectVersion" to project.version) }
}

dependencies {
  implementation(project(":osc-core"))
  implementation(project(":osc-transport-udp"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
  implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
  implementation("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")
  testImplementation(kotlin("test"))
}
