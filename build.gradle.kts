plugins {
  kotlin("jvm") apply false
  id("com.diffplug.spotless")
}

allprojects {
  group = "com.oscplatform"
  version = "0.5.0"

  repositories { mavenCentral() }
}

subprojects {
  apply(plugin = "org.jetbrains.kotlin.jvm")

  extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(21)
  }

  tasks.withType<Test> { useJUnitPlatform() }
}

spotless {
  kotlin {
    target("**/*.kt")
    ktfmt("0.54")
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    ktfmt("0.54")
  }
}
