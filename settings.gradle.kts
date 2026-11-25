// CI sets MAVEN_CENTRAL_MIRROR because repo.maven.apache.org rate-limits (HTTP 429) the shared
// GitHub runners. The mirror is tried first; Gradle falls back to Maven Central on a 404.
val mavenCentralMirror: String? = System.getenv("MAVEN_CENTRAL_MIRROR")?.takeIf { it.isNotBlank() }

pluginManagement {
  val mirror = System.getenv("MAVEN_CENTRAL_MIRROR")?.takeIf { it.isNotBlank() }
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    if (mirror != null) maven(mirror)
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    if (mavenCentralMirror != null) maven(mavenCentralMirror)
    mavenCentral()
  }
}

rootProject.name = "TaskFlow"

include(":app")
