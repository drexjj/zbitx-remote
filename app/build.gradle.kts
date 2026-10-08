plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sbitx.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zbitx.remote"
        minSdk = 26
        targetSdk = 34
        // CI injects these: -PverCode=<run number> -PrelVersion=<git describe>.
        // versionCode must go up with every build or Android blocks the update.
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("relVersion") as String?) ?: "dev"
    }

    // One fixed signing key for every build, so a newer APK installs over the
    // old one as an upgrade. (Previously each CI run signed with a throwaway
    // debug key, and Android refuses to update an app whose signature changed.)
    // The key in the repo can be overridden with private ones via environment
    // variables (e.g. from GitHub secrets) - but switching keys again means one
    // more uninstall, so pick one and keep it.
    signingConfigs {
        create("zbitx") {
            storeFile = file(System.getenv("ZBITX_KEYSTORE") ?: "zbitx-remote.keystore")
            storePassword = System.getenv("ZBITX_KEYSTORE_PASSWORD") ?: "zbitxremote"
            keyAlias = System.getenv("ZBITX_KEY_ALIAS") ?: "zbitx"
            keyPassword = System.getenv("ZBITX_KEY_PASSWORD") ?: "zbitxremote"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("zbitx")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zbitx")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
