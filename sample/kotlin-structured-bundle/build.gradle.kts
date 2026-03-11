plugins {
  kotlin("jvm")
  id("com.oscplatform.schema-codegen")
  application
}

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

dependencies {
  implementation("com.oscplatform:osc-core:0.4.0")
  implementation("com.oscplatform:osc-transport-udp:0.4.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application { mainClass = "com.example.MainKt" }

oscSchemaCodegen {
  schema.set(layout.projectDirectory.file("schema.yaml"))
  packageName.set("com.example.osc.generated")
}
