pluginManagement {
    repositories {
        // Set -Pandroid.mavenMirror=... to fetch Google Maven through a mirror
        // (e.g. http://127.0.0.1:8888/android/maven2 via a local proxy).
        val mirror = providers.gradleProperty("android.mavenMirror").orNull
        if (mirror != null) {
            mavenCentral()
            gradlePluginPortal()
            maven(url = mirror)
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val mirror = providers.gradleProperty("android.mavenMirror").orNull
        if (mirror != null) {
            mavenCentral()
            maven(url = mirror)
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "Cadence"
include(":app")
