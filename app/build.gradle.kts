plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Single source of truth for the app version. Bump this before tagging
// vX.Y.Z — the release workflow refuses tags that don't match it.
val appVersion = "1.1.2"

android {
    namespace = "io.github.gdepass.twspeedtrap"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.gdepass.twspeedtrap"
        minSdk = 29
        targetSdk = 37
        // Derived so versionCode can never lag behind the released version
        // (1.1.0 -> 10100). Android refuses updates whose code isn't higher.
        versionCode =
            appVersion.split(".").let { (major, minor, patch) ->
                major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
            }
        versionName = appVersion
    }

    // Signing comes exclusively from the environment (CI secrets or a local
    // export) so key material can never land in git. Without the variables,
    // release builds are simply unsigned.
    val keystorePath = System.getenv("KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":detection"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.location)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.osmdroid)

    testImplementation(libs.junit4)
}
