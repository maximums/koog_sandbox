// Koog LLM clients for APIs Koog doesn't ship: Z.ai and Google's Gemini Decisions API.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.testBalloon)
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // LLMClient, plus Prompt, Message, LLModel and ToolDescriptor that its signatures use.
    api(libs.koog.prompt.executor.clients)
    // KoogHttpClient, which the clients call their APIs with.
    api(libs.koog.http.client.core)
    // The providers' request and response bodies.
    implementation(libs.kotlinx.serialization.json)
    // Koog's tool JSON schemas for OpenAI-style function definitions, which Z.ai uses too.
    implementation(libs.koog.prompt.executor.openai.client.base)

    testImplementation(libs.testBalloon.framework.core)
    testImplementation(libs.koog.http.client.ktor)
    testImplementation(libs.ktor.client.mock)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
