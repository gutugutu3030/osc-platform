plugins {
  kotlin("jvm")
  application
}

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

dependencies {
  implementation("com.oscplatform:osc-core:0.3.0")
  implementation("com.oscplatform:osc-transport-udp:0.3.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

// Java の App クラスをエントリポイントに指定
application { mainClass = "com.example.App" }
