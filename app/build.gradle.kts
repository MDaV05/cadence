import java.net.URI
import java.net.HttpURLConnection
import java.security.MessageDigest
import org.gradle.api.GradleException

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
        versionName = ciTag?.removePrefix("v") ?: "0.14.4"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
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
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_UPDATER", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_UPDATER", "false")
        }
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
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coroutines.guava)
    implementation(libs.work.runtime.ktx)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    testImplementation(libs.paging.testing)
    implementation(libs.palette.ktx)

    testImplementation(libs.junit)
    // JVM real org.json so unit tests can exercise toJson/fromJson (android.jar stubs throw).
    testImplementation(libs.json)

    implementation(files("libs/tdlib-0.1.0.aar"))
}

// SHA-256 of the vendored tdlib-0.1.0.aar. The AAR becomes libtdjni.so in
// release-signed artifacts, so it is never trusted on fetch alone. Update this
// digest deliberately when rotating the dependency.
val tdlibAarSha256 = "5a5ad7fa346a29f3f09a31eaf4a742dbab864f25dc74f5373f7d2708e2a27638"

fun sha256(f: java.io.File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(f.readBytes())
        .joinToString("") { "%02x".format(it) }

val ensureTdlib by tasks.registering {
    val aar = file("libs/tdlib-0.1.0.aar")
    // No declared outputs: the task must re-run (and re-verify) every build,
    // so a later-tampered cached AAR can never slip past an up-to-date check.
    doLast {
        // Verify the already-present cached file first (R3-11): a poisoned
        // artifact must fail even when no download happens.
        if (aar.exists() && sha256(aar) != tdlibAarSha256) {
            aar.delete()
            throw GradleException("TDLib AAR checksum mismatch on cached libs/tdlib-0.1.0.aar")
        }
        if (!aar.exists()) {
            aar.parentFile.mkdirs()
            logger.lifecycle("Downloading TDLib AAR...")
            var url = URI("https://github.com/AkashPriyadarshii/tdlib-android/releases/download/v0.1.0/core-release.aar").toURL()
            var conn = url.openConnection() as HttpURLConnection
            // Manual https-only redirect follow; a cross-host hop must stay on GitHub.
            conn.instanceFollowRedirects = false
            var redirectCount = 0
            while (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location")
                    ?: throw GradleException("TDLib download: redirect without Location")
                if (redirectCount++ >= 5) throw GradleException("TDLib download: too many redirects")
                conn.disconnect()
                val next = url.toURI().resolve(loc).toURL()
                if (next.protocol != "https") throw GradleException("TDLib download: redirect to non-https: $next")
                val host = next.host.lowercase()
                val githubHost = host == "github.com" || host.endsWith(".github.com") ||
                    host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")
                if (!githubHost) throw GradleException("TDLib download: redirect to untrusted host: $host")
                url = next
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
            }
            val tmp = file("libs/tdlib-0.1.0.aar.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            tmp.renameTo(aar)
            if (sha256(aar) != tdlibAarSha256) {
                aar.delete()
                throw GradleException("TDLib AAR checksum mismatch on downloaded artifact")
            }
            logger.lifecycle("TDLib AAR downloaded (${aar.length()} bytes)")
        }
    }
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") || it.name == "preBuild" }.configureEach {
    dependsOn(ensureTdlib)
}

