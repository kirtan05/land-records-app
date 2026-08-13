import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Built-in Kotlin disabled in gradle.properties so kapt (Room) works.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.landrecords.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.landrecords.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "0.11.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // Public update APKs ship WITHOUT the 125 MB personal data seed: build with
    // `-Pslim` (see tools/release/release.sh). The private first-install APK is built
    // without the flag and carries the seed.
    val slimBuild = providers.gradleProperty("slim").isPresent

    // Pin the signing key. Every published APK so far is signed with this debug
    // keystore, and Android only allows an in-place update when the signature matches —
    // so changing it would strand every existing install. release.sh verifies the
    // built APK's certificate against the published one before it will publish.
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // The 125 MB data seed is already-compressed PDFs — don't let aapt slow-deflate them
    // (keeps build + install time sane, restore works either way).
    androidResources {
        noCompress += "pdf"
        // `-Pslim` drops the seed directory at packaging time (aapt ignores it).
        if (slimBuild) ignoreAssetsPatterns += "seed"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    // Streaming PDF merge (no rasterization) — avoids OOM when combining big scanned records.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
