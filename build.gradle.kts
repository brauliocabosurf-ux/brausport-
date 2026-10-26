// AGP 9.1.1 admite API 37, requerida por OkHttp Android 5.5.1.
// Requiere Gradle 9.3.1 y JDK 17. Kotlin externo se conserva mediante gradle.properties.
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
