pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
    }
}

rootProject.name = "osc-platform"

include(
    "osc-core",
    "osc-transport-udp",
    "osc-adapter-cli",
    "osc-adapter-mcp",
    "osc-cli",
)
