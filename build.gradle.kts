// Versiones revisadas el 25-oct-2026 en fuentes oficiales (ver SECURITY.md → "Dependencias").
// AGP 8.13.2: última 8.x (Gradle ≥ 8.13, JDK 17, API máx. 36.1, soporta Kotlin 2.3).
// No se migra a AGP 9.x en esta entrega: exige Gradle 9.6 y cambios de DSL que no se pudieron
// verificar compilando.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
