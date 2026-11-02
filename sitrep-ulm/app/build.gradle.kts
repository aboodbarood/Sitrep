plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sitrep.ulm"
    compileSdk = 34

    defaultConfig {
        // Separate id from the Munich build (com.sitrep.app) so both can be installed side by side.
        applicationId = "com.sitrep.ulm"
        minSdk = 26
        // Stays at 34 on purpose, even though the Fairphone 6 ships Android 15:
        // targeting 35 caps dataSync foreground services at 6h per 24h, which
        // would kill the always-on lock-screen summary every afternoon.
        targetSdk = 34
        versionCode = 2
        versionName = "1.1-ulm"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
