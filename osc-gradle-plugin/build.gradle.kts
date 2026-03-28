plugins { `java-gradle-plugin` }

dependencies {
  implementation(project(":osc-codegen"))
  testImplementation(kotlin("test"))
  testImplementation(kotlin("gradle-plugin"))
  testImplementation(gradleTestKit())
}

gradlePlugin {
  plugins {
    create("oscSchemaCodegen") {
      id = "com.oscplatform.schema-codegen"
      implementationClass = "com.oscplatform.gradle.OscSchemaCodegenPlugin"
    }
  }
}
