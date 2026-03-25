import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

val coroutinesVersion: String by project

plugins {
  application
  id("com.gradleup.shadow")
}

dependencies {
  implementation(project(":osc-adapter-cli"))
  implementation(project(":osc-adapter-mcp"))
  implementation(project(":osc-adapter-webui"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
  testImplementation(kotlin("test"))
}

application { mainClass = "com.oscplatform.cli.MainKt" }

tasks.named<Jar>("jar") { archiveClassifier.set("thin") }

tasks.named<ShadowJar>("shadowJar") {
  archiveClassifier.set("")
  mergeServiceFiles()
  manifest { attributes["Main-Class"] = application.mainClass.get() }
}
