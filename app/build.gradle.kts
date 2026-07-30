import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.eikarna.bluetoothjammer"
    compileSdk = 35

    // Release signing is driven by CI secrets / Gradle properties; when they're absent
    // (local builds, PRs without secrets) the release build falls back to debug signing so the
    // build still succeeds. No keystore is committed to the repo.
    val releaseStoreFile = ((project.findProperty("RELEASE_STORE_FILE") as String?)
        ?: System.getenv("RELEASE_STORE_FILE"))?.takeIf { it.isNotBlank() }

    defaultConfig {
        applicationId = "com.eikarna.bluetoothjammer"
        // The attack path is annotated @RequiresApi(Q) and the log view uses API-29 text APIs,
        // so 24 was a latent crash on Android 7-9. 29 makes the floor honest.
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real signing when secrets are present, else debug so CI/PR builds still succeed.
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
