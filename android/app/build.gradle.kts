import java.util.Properties

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
        versionCode = 10
        versionName = "0.7.0-sms"

        // Public half of the server key that signs responder replies (F-31). The private seed is
        // SERVER_KEY_SEED_HEX on the server only; a new key means a new app release.
        buildConfigField("String", "SERVER_PUBLIC_KEY", "\"2u5qNe3LJcC0vS4rGDWEWINv_K3TVtto0UlR2p308XA\"")
        // Where gateways upload: the Render service (render.yaml). Testers can change it in the Network tab.
        buildConfigField("String", "API_BASE_URL", "\"https://resqmesh-ds0v.onrender.com\"")
        // Team phones SOS may be texted to (TRD 2.7 number policy), from resqmesh.smsNumbers in
        // local.properties so personal numbers stay out of git. Empty: this build sends no SMS.
        val smsNumbers = localProperty("resqmesh.smsNumbers").filter { it.isDigit() || it == '+' || it == ',' }
        buildConfigField("String", "SMS_ALLOWED_NUMBERS", "\"$smsNumbers\"")
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

fun localProperty(key: String): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(key).orEmpty()
}
