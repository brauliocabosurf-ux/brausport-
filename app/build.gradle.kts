import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cada build en GitHub sube el número de versión, así la tele acepta la actualización.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// HTTP en claro: la lista de hosts y la generación de network_security_config.xml viven en
// gradle/network-security.gradle.kts (un solo lugar, verificable por separado).
apply(from = rootProject.file("gradle/network-security.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val cleartextHosts = extra["brauCleartextHosts"] as List<String>

// ---------------------------------------------------------------------------------------------
// Firma de Release: SOLO con la llave correcta. Sin respaldo de depuración.
// La llave llega por variables de entorno (en CI, desde los Secrets) y se compara su
// certificado con la huella esperada, para conservar la posibilidad de actualizar la app instalada.
// ---------------------------------------------------------------------------------------------
val expectedCertSha256: String = (findProperty("brau.expectedCertSha256") as String? ?: "").lowercase(Locale.ROOT)
val keystorePath: String? = System.getenv("BRAU_KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("BRAU_KEYSTORE_PASSWORD")
val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && file(keystorePath).isFile

fun releaseCertSha256(): String {
    val ks = KeyStore.getInstance("PKCS12")
    FileInputStream(keystorePath!!).use { ks.load(it, keystorePassword!!.toCharArray()) }
    val cert = ks.getCertificate("brau") ?: throw GradleException("La llave no contiene el alias 'brau'")
    return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

android {
    namespace = "com.brau.sportsplus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.brau.sportsplus"
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
        buildConfigField("String", "CLEARTEXT_HOSTS", "\"${cleartextHosts.joinToString(",")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "brau"
                keyPassword = keystorePassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 desactivado a propósito: ofuscar no es una medida de seguridad para esta app
            // y complicaría depurar fallos en la tele.
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric (prueba de integración HLS)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// El XML generado se agrega a los recursos y se genera antes de cualquier compilación.
android.sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/nsc/res").get().asFile)
tasks.named("preBuild") { dependsOn("generateNetworkSecurityConfig") }

// Release sin la llave correcta → error claro (nunca se firma con la llave de depuración).
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name in setOf("packageRelease", "assembleRelease", "bundleRelease") }
    if (!wantsRelease) return@whenReady
    if (!hasReleaseKey) {
        throw GradleException("Falta la llave de firma de Release: define BRAU_KEYSTORE_FILE y BRAU_KEYSTORE_PASSWORD.")
    }
    if (expectedCertSha256.isEmpty()) throw GradleException("Falta brau.expectedCertSha256 en gradle.properties.")
    val actual = releaseCertSha256()
    if (actual != expectedCertSha256) {
        throw GradleException("La llave de firma NO es la esperada (huella distinta). La tele rechazaría la actualización.")
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // Mismas versiones que usa Media3 1.11.1 para sus propias pruebas (gradle/libs.versions.toml de Media3).
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.media3:media3-test-utils-robolectric:$media3")
}
