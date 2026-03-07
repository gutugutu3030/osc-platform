dependencies {
    implementation(project(":osc-core"))
    implementation(project(":osc-transport-udp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    testImplementation(kotlin("test"))
}
