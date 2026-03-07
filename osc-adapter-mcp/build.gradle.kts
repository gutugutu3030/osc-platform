val coroutinesVersion: String by project
val jacksonVersion: String by project

dependencies {
    implementation(project(":osc-core"))
    implementation(project(":osc-transport-udp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    testImplementation(kotlin("test"))
}
