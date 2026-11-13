plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:timeline"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

application {
    // `./gradlew :core:mcp:installDist` -> core/mcp/build/install/vedit-mcp/bin/vedit-mcp
    applicationName = "vedit-mcp"
    mainClass.set("dev.videoeditor.core.mcp.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // The end-to-end test starts the real CLI as a child process from the installed distribution.
    dependsOn(tasks.named("installDist"))
    systemProperty(
        "veditMcpLauncher",
        layout.buildDirectory
            .file("install/vedit-mcp/bin/vedit-mcp")
            .get()
            .asFile.absolutePath,
    )
}
