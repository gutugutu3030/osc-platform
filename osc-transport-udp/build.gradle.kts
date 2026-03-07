val coroutinesVersion: String by project

dependencies {
    implementation(project(":osc-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    testImplementation(kotlin("test"))
}
