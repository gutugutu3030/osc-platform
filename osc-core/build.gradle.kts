val kotlinVersion: String by project
val coroutinesVersion: String by project
val jacksonVersion: String by project

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
  implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
  implementation("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")
  implementation("tools.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
  implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")
  testImplementation(kotlin("test"))
}
