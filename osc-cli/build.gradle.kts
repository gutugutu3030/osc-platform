import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

val coroutinesVersion: String by project

plugins {
  application
  id("com.gradleup.shadow") version "9.3.2"
}

dependencies {
  implementation(project(":osc-adapter-cli"))
  implementation(project(":osc-adapter-mcp"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

application { mainClass = "com.oscplatform.cli.MainKt" }

tasks.named<Jar>("jar") { archiveClassifier.set("thin") }

tasks.named<ShadowJar>("shadowJar") {
  archiveClassifier.set("")
  mergeServiceFiles()
  manifest { attributes["Main-Class"] = application.mainClass.get() }
}
