val kotlinVersion: String by project

dependencies {
  implementation(project(":osc-core"))
  implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
  implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
  testImplementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
  testImplementation(kotlin("test"))
}
