plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.videoeditor.core.render"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:timeline"))
    testImplementation(libs.junit4)
}
