plugins {
    // Built-in Kotlin disabled (see gradle.properties) — use the classic Kotlin plugins so kapt works.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}
