plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotest.property)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Golden JSON Schemas: `./gradlew :core:timeline:test -PupdateSchemas` rewrites them from the generator.
    systemProperty("schemaDir", file("src/test/resources/schemas").absolutePath)
    systemProperty("updateSchemas", project.hasProperty("updateSchemas").toString())
    inputs.dir("src/test/resources/schemas").optional()
}
