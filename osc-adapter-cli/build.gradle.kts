val coroutinesVersion: String by project
val jacksonVersion: String by project

dependencies {
  implementation(project(":osc-core"))
  implementation(project(":osc-transport-udp"))
  implementation(project(":osc-codegen"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
  implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
  implementation("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")
  testImplementation(kotlin("test"))
}
