pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ObjectBox
        maven { url = uri("https://raw.githubusercontent.com/objectbox/objectbox-java/master/repo/") }
    }
}
rootProject.name = "VakilDoot"
include(":app")
