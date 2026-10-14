plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.trucknav.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trucknav.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        manifestPlaceholders["MAPS_API_KEY"] = providers.gradleProperty("MAPS_API_KEY").orElse("").get()
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.maps.android:maps-compose:6.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
