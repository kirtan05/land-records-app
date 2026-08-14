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
        versionCode = 25
        versionName = "0.12.1"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Every target device is arm64; keep other ABIs out of the APK.
        ndk { abiFilters += "arm64-v8a" }
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

    // AnyRoR captcha CNN runs as a pure-Kotlin forward pass (captcha/CaptchaCnn.kt)
    // over assets/anyror_cnn_real.weights — no ONNX Runtime, no native libs.

    // Plain JVM unit tests. The identity layer (data/identity/Identity.kt) is pure Kotlin
    // with no Android dependency, so it is held to tools/identity/vectors.json here rather
    // than on a device — the same fixture the desktop's `node tools/identity/test.mjs` reads.
    // org.json ships only as a throwing stub in the android.jar used for unit tests, so the
    // real implementation is put on the test classpath explicitly.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * tools/identity/vectors.json is the shared cross-language fixture (see
 * data/identity/Identity.kt). It lives outside this module, so Gradle cannot infer that the
 * unit tests depend on it — without this, editing only the fixture leaves the test task
 * UP-TO-DATE and the identity contract silently unverified.
 */
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("../tools/identity/vectors.json"))
        .withPropertyName("identityVectors")
        .withPathSensitivity(PathSensitivity.NONE)
}
