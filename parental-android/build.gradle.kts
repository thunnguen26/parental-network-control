// Parental Android Client — build độc lập bằng Gradle (KHÔNG thuộc Maven reactor).
// Mở dự án này bằng Android Studio, hoặc chạy: ./gradlew :app:assembleDebug
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
