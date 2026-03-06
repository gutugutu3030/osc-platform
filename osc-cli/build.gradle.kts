plugins {
    application
}

dependencies {
    implementation(project(":osc-adapter-cli"))
    implementation(project(":osc-adapter-mcp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
    mainClass = "com.oscplatform.cli.MainKt"
}
