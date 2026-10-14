plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.trucknav.app"
    compileSdk = 35

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.trucknav.app"
        minSdk = 26
        targetSdk = 35
        // Release tags use v8, v9, ...; CI passes the numeric code through the environment.
        versionCode = (System.getenv("TRUCKNAV_VERSION_CODE") ?: "7").toInt()
        versionName = System.getenv("TRUCKNAV_VERSION_NAME") ?: "0.7.0"
    }

    signingConfigs {
        create("trucknavRelease") {
            storeFile = file(System.getenv("TRUCKNAV_KEYSTORE_FILE") ?: "missing-keystore.jks")
            storePassword = System.getenv("TRUCKNAV_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("TRUCKNAV_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("TRUCKNAV_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("trucknavRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
