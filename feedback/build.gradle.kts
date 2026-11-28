// The Feedback screen for test (debug) builds: the shared Feedback tab from
// mirrorapps-kit (kit/, a git submodule), MirrorMsg's colours, and the two
// settings that belong to it (owner code, logs in bug reports). The app
// takes this module as debugImplementation only, so the Play build has none
// of it. Compose lives here, not in :app.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dano.mirrormsg.feedback"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
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
    // KitHost, KitLog, BundleContributor and LogsMode are part of this
    // module's API: the app's debug glue implements them.
    api(project(":kit"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
