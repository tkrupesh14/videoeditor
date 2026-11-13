plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.videoeditor.core.media"
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
    api(project(":core:timeline"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
}
