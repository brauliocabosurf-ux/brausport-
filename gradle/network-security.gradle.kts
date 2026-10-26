// HTTP en claro: lista de hosts EXACTOS (sin comodines ni subdominios). Fuente ÚNICA para:
//   - network_security_config.xml (lo aplica Android a OkHttp), y
//   - BuildConfig.CLEARTEXT_HOSTS (lo aplica UrlPolicy dentro de la app).
// Se lee de la propiedad brau.cleartextHosts (gradle.properties o -P) o de BRAU_CLEARTEXT_HOSTS.
// Vacío = ningún HTTP. No se puede cambiar desde la app instalada.
//
// Este script no depende del plugin de Android: se puede ejecutar solo para revisar el XML
// generado (ver SECURITY.md, "Verificar la configuración generada").

import java.util.Locale

val brauCleartextHosts: List<String> =
    ((findProperty("brau.cleartextHosts") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("BRAU_CLEARTEXT_HOSTS") ?: "")
        .split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()

// Solo IPv4 literales o nombres de dominio completos; nada de comodines, puertos ni rutas.
val brauHostPattern = Regex("""^(([0-9]{1,3}\.){3}[0-9]{1,3}|[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+)$""")
brauCleartextHosts.forEach { host ->
    if (!brauHostPattern.matches(host)) throw GradleException("Host HTTP inválido en brau.cleartextHosts: $host")
}

extra["brauCleartextHosts"] = brauCleartextHosts

abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input abstract val hosts: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val list = hosts.get()
        val domains = list.joinToString("\n") { "        <domain includeSubdomains=\"false\">$it</domain>" }
        val cleartextBlock = if (list.isEmpty()) "" else """
    <!-- Excepción ÚNICA: HTTP solo hacia estos hosts exactos. -->
    <domain-config cleartextTrafficPermitted="true">
$domains
    </domain-config>
"""
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<!-- GENERADO al compilar desde brau.cleartextHosts. No editar a mano. -->
<network-security-config>
    <!-- Por defecto: solo HTTPS y solo certificados del sistema (no los instalados por el usuario). -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
$cleartextBlock</network-security-config>
"""
        val dir = outputDir.get().asFile.resolve("xml")
        dir.mkdirs()
        dir.resolve("network_security_config.xml").writeText(xml)
    }
}

tasks.register<GenerateNetworkSecurityConfig>("generateNetworkSecurityConfig") {
    hosts.set(brauCleartextHosts)
    outputDir.set(layout.buildDirectory.dir("generated/nsc/res"))
}
