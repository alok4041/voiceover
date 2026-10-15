plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val tokenServerUrl = providers.gradleProperty("TOKEN_SERVER_URL")
    .orElse("https://squadvoice-api-alok1404-251015-0109.onrender.com")
    .get()

android {
    namespace = "com.squadvoice.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.squadvoice.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.4"
        buildConfigField("String", "TOKEN_SERVER_URL", "\"$tokenServerUrl\"")
    }

    buildTypes {
        debug {
            manifestPlaceholders["allowCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["allowCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("io.livekit:livekit-android:2.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
