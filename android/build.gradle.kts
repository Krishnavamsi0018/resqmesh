plugins {
    alias(libs.plugins.android.application) apply false
    // Declaring the Kotlin plugin here pins one Kotlin version for the whole build;
    // AGP 9's built-in Kotlin picks it up for :app.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
