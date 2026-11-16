// Declared once here so every module shares one Kotlin Gradle plugin (it breaks if loaded per module).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.testBalloon) apply false
}
