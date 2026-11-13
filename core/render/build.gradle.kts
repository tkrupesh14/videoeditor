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
    api(project(":core:timeline"))
    implementation(project(":core:media"))
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
}
