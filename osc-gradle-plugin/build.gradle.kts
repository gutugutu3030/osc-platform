plugins { `java-gradle-plugin` }

dependencies { implementation(project(":osc-codegen")) }

gradlePlugin {
  plugins {
    create("oscSchemaCodegen") {
      id = "com.oscplatform.schema-codegen"
      implementationClass = "com.oscplatform.gradle.OscSchemaCodegenPlugin"
    }
  }
}
