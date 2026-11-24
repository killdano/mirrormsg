import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

// Release signing — reads credentials from keystore.properties at the repo root.
// That file is gitignored and never committed; it holds the store/key passwords,
// the alias, and the keystore filename. If it's absent (e.g. a fresh clone that
// only ever builds debug), release signing is simply skipped and debug builds
// are unaffected.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        load(FileInputStream(keystorePropsFile))
    }
}

// Extra sources: the folder named by mirrormsg.media in local.properties, else src/base.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.let { f -> FileInputStream(f).use { load(it) } }
}
val extraSources = localProps.getProperty("mirrormsg.media")?.let { file(it) }?.takeIf { it.isDirectory }

android {
    namespace = "com.dano.mirrormsg"
    sourceSets {
        getByName("main") {
            // Both: AGP 9's built-in Kotlin compiles the kotlin directories,
            // not extra java ones, so a .kt file here needs the kotlin entry.
            java.srcDir(extraSources ?: file("src/base/java"))
            kotlin.srcDir(extraSources ?: file("src/base/java"))
        }
    }
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.dano.mirrormsg"
        minSdk = 26
        targetSdk = 36
        // versionCode MUST increase on every Play upload and can never be reused
        // or lowered — it is Play's only ordering signal. Continues the internal
        // sideload sequence (15) rather than restarting, so no previously-installed
        // build outranks a Play build.
        versionCode = 24
        // The public-facing string. Play users have never seen a MirrorMsg build,
        // so the first listing is 1.0 (matching the release notes) even though
        // internal sideloads had run to 1.1.2. If you'd rather keep continuity with
        // those internal numbers, make this "1.2.0" — versionName has no ordering
        // rules of its own; only versionCode matters to Play.
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // arm64 only — Go bridge is the only native code now that GeckoView is gone.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the upload key only when keystore.properties is present.
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files("libs/mirrorbridge.aar"))
    // Facebook bridge (messagix). Conditional: only linked when the AAR is
    // present, so removing app/libs/fbmessagebridge.aar cleanly drops Facebook.
    if (file("libs/fbmessagebridge.aar").exists()) {
        implementation(files("libs/fbmessagebridge.aar"))
    }
    // PebbleKit Android 2 — phone <-> watch (JitPack).
    implementation("io.rebble.pebblekit2:client:0.1.0")
    // Coroutines (sendDataToPebble is a suspend function).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ZXing core — QR-code bitmap generation for the WhatsApp linked-device
    // pairing screen (Android has no built-in QR generator). Pure-Java, tiny.
    implementation("com.google.zxing:core:3.5.3")
    // Jetpack Security — EncryptedSharedPreferences for the license code (the
    // credential is never stored in plain prefs). 1.1.0-alpha06 is the widely-used
    // build; 1.0.0 has known keystore edge-cases on some devices.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // The Feedback screen (feedback/, with mirrorapps-kit): test builds only.
    // Release builds get none of it: no Compose, no microphone permission.
    debugImplementation(project(":feedback"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

