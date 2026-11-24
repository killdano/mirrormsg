// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // For feedback/ and kit/ (Compose). The app module itself has no Compose.
    alias(libs.plugins.kotlin.compose) apply false
}