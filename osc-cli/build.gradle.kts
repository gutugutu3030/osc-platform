val coroutinesVersion: String by project

plugins {
    application
}

dependencies {
    implementation(project(":osc-adapter-cli"))
    implementation(project(":osc-adapter-mcp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

application {
    mainClass = "com.oscplatform.cli.MainKt"
}
