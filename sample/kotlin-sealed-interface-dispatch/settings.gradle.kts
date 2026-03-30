pluginManagement {
  val sharedVersions =
      java.util.Properties().apply { file("../../gradle.properties").inputStream().use(::load) }
  val kotlinVersion =
      sharedVersions.getProperty("kotlinVersion")
          ?: error("kotlinVersion is missing in ../../gradle.properties")

  includeBuild("../..")
  plugins { kotlin("jvm") version kotlinVersion }
}

rootProject.name = "kotlin-sealed-interface-dispatch"

includeBuild("../..")
