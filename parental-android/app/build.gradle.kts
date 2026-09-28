plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.parental.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.parental.android"
        minSdk = 26 // Android 8.0 — Foreground Service ổn định
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Protocol engine + parser chạy thuần JVM (không đụng Android framework)
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // JSON protocol — Jackson bên dưới kotlinx-serialization config không đủ
    // expressiveness cho envelope generic, dùng Jackson trực tiếp giống hệt
    // parental-common (cùng ObjectMapper semantics: FAIL_ON_UNKNOWN_PROPERTIES=false).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // Coroutines: Foreground Service + network engine chạy nền
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.github.tony19:logback-android:3.0.0")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // DataStore: lưu IP server + token ghép đôi
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Unit test JVM (JUnit 4 — Android Gradle mặc định; kotlin-test assertions)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
