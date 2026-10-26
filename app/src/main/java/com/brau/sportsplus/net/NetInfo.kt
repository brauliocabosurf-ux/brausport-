package com.brau.sportsplus.net

import com.brau.sportsplus.BuildConfig
import com.brau.sportsplus.core.net.HttpIdentity
import com.brau.sportsplus.core.net.PublicAddressPolicy
import com.brau.sportsplus.core.net.UrlPolicy
import okhttp3.OkHttpClient

/** Piezas de red compartidas por toda la app (una sola instancia de cada una). */
object NetInfo {
    const val USER_AGENT = HttpIdentity.USER_AGENT

    /**
     * Hosts con HTTP permitido. Vienen de la compilación (propiedad brau.cleartextHosts) y son
     * los MISMOS que se escriben en network_security_config.xml. No se pueden cambiar desde la app.
     */
    val cleartextHosts: List<String> = BuildConfig.CLEARTEXT_HOSTS.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val policy: UrlPolicy by lazy { UrlPolicy(cleartextHosts, PublicAddressPolicy) }

    val client: OkHttpClient by lazy { SafeHttp.buildClient(policy, PublicAddressPolicy) }
}
