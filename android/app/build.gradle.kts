plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no kotlin-android plugin here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.resqmesh.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.resqmesh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.0-gateway"

        // Public TEST key from shared/test-vectors, so responder acks can be tried before the
        // backend exists. Week 7 replaces it with the real server key.
        buildConfigField("String", "SERVER_PUBLIC_KEY", "\"AgvUJ0RrcjQk2A0srTUro982SdDvj6rgyn6yVEOUGyk\"")
        // Where gateways upload. Empty until the API is deployed (week 7); testers can set it in the Network tab.
        buildConfigField("String", "API_BASE_URL", "\"\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The app switches language itself (F-01), so every language must ship in every install.
    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":mesh-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.nearby)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.org.json)
}
