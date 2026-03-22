pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ObjectBox
        maven { url = uri("https://raw.githubusercontent.com/objectbox/objectbox-java/master/repo/") }
        // Additional Android/Facebook libraries
        maven { url = uri("https://jcenter.bintray.com/") }
    }
}
rootProject.name = "VakilDoot"
include(":app")
