val kotlinVersion: String by project
val coroutinesVersion: String by project
val jacksonVersion: String by project
val ktorVersion: String by project

val frontendDir = layout.projectDirectory.dir("frontend")
val generatedFrontendDir = layout.buildDirectory.dir("generated/frontend")
val npmExecutable = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

sourceSets { named("main") { resources.srcDir(generatedFrontendDir) } }

val npmInstallFrontend by
    tasks.registering(Exec::class) {
      workingDir(frontendDir.asFile)
      commandLine(npmExecutable, "ci")
      inputs.file(frontendDir.file("package.json"))
      inputs.file(frontendDir.file("package-lock.json"))
      outputs.dir(frontendDir.dir("node_modules"))
    }

val buildFrontend by
    tasks.registering(Exec::class) {
      dependsOn(npmInstallFrontend)
      workingDir(frontendDir.asFile)
      commandLine(npmExecutable, "run", "build")
      inputs.file(frontendDir.file("package.json"))
      inputs.file(frontendDir.file("package-lock.json"))
      inputs.file(frontendDir.file("tsconfig.json"))
      inputs.dir(frontendDir.dir("src"))
      outputs.dir(generatedFrontendDir)
    }

val typecheckFrontend by
    tasks.registering(Exec::class) {
      dependsOn(npmInstallFrontend)
      workingDir(frontendDir.asFile)
      commandLine(npmExecutable, "run", "typecheck")
      inputs.file(frontendDir.file("package.json"))
      inputs.file(frontendDir.file("package-lock.json"))
      inputs.file(frontendDir.file("tsconfig.json"))
      inputs.dir(frontendDir.dir("src"))
    }

val testFrontendUi by
    tasks.registering(Exec::class) {
      dependsOn(npmInstallFrontend)
      workingDir(frontendDir.asFile)
      commandLine(npmExecutable, "run", "test:ui")
      inputs.file(frontendDir.file("package.json"))
      inputs.file(frontendDir.file("package-lock.json"))
      inputs.file(frontendDir.file("tsconfig.json"))
      inputs.dir(frontendDir.dir("src"))
      inputs.dir(frontendDir.dir("test"))
    }

tasks.named("processResources") { dependsOn(buildFrontend) }

tasks.named("check") {
  dependsOn(typecheckFrontend)
  dependsOn(testFrontendUi)
}

dependencies {
  implementation(project(":osc-core"))
  implementation(project(":osc-transport-udp"))
  implementation(platform("io.ktor:ktor-bom:$ktorVersion"))
  implementation("io.ktor:ktor-server-cio")
  implementation("io.ktor:ktor-server-content-negotiation")
  implementation("io.ktor:ktor-server-html-builder")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
  implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
  implementation("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")
  implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")
  testImplementation("io.ktor:ktor-client-cio")
  testImplementation("io.ktor:ktor-server-test-host")
  testImplementation(kotlin("test"))
}
