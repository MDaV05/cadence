plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Monotonic version code from commit count (+100 so it always exceeds the
// historical hand-set 1). Needs full git history — CI checks out with
// fetch-depth: 0; falls back on shallow/missing git.
val commitCount = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "com.cadence.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cadence.music"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount + 100
        // Tag builds (v1.2.3) take the version name from the tag; local builds get the default.
        val ciTag = System.getenv("GITHUB_REF_NAME")?.takeIf { it.matches(Regex("v\\d+\\.\\d+.*")) }
        versionName = ciTag?.removePrefix("v") ?: "0.2.0"
    }

    signingConfigs {
        val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
        val ksPass = System.getenv("SIGNING_KEYSTORE_PASSWORD")
        val alias = System.getenv("SIGNING_KEY_ALIAS")
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")
        if (ksPath != null && ksPass != null && alias != null && keyPass != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coroutines.guava)
    implementation(libs.work.runtime.ktx)
    implementation(libs.palette.ktx)

    testImplementation(libs.junit)
}
