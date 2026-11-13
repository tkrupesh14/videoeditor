plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.videoeditor.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.videoeditor.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:timeline"))
    implementation(project(":core:ai"))
    implementation(project(":core:render"))
    implementation(project(":core:media"))
    implementation(project(":ui:shell"))
    implementation(project(":ui:timeline"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
}
