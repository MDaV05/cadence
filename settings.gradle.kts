pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        val mirror = providers.gradleProperty("android.mavenMirror").orNull
        if (mirror != null) {
            maven(url = mirror)
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        val mirror = providers.gradleProperty("android.mavenMirror").orNull
        if (mirror != null) {
            maven(url = mirror)
        }
    }
}

rootProject.name = "Cadence"
include(":app")
