plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tidyo"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tidyo"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // A fixed, public keystore so every CI build is signed the same way and
    // new APKs install over old ones. Make your own for a Play Store release.
    signingConfigs {
        create("shared") {
            storeFile = file("tidyo-shared.jks")
            storePassword = "tidyo-shared"
            keyAlias = "tidyo"
            keyPassword = "tidyo-shared"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
