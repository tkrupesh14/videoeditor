plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api: AiSuggestion exposes EditPreview/ProjectDiff, and suggest() takes an EditorSession.
    api(project(":core:timeline"))
    implementation(libs.kotlinx.serialization.json)
    // api: ApiKeyStore and SpendGuard expose StateFlow in their public signatures.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.anthropic.java)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // Real-API smoke test spends real credits: never part of the default test run.
        excludeTags("manual-real-api")
    }
}

// Manual only: ANTHROPIC_API_KEY=... ./gradlew :core:ai:manualRealApiTest (see core/ai/README.md).
val manualRealApiTest by tasks.registering(Test::class) {
    description = "Runs the @Tag(\"manual-real-api\") test against the real Anthropic API. Spends real credits."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("manual-real-api") }
    outputs.upToDateWhen { false }
}
