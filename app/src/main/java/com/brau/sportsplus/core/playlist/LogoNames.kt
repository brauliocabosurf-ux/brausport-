package com.brau.sportsplus.core.playlist

/**
 * Logos LOCALES (incluidos en el APK, carpeta res/drawable-nodpi). La app no descarga logos:
 * no hay conexiones a terceros ni decodificación de imágenes remotas.
 *
 * Origen de las imágenes: repositorio público tv-logo/tv-logos (ver THIRD_PARTY_NOTICES.md).
 * Clave = nombre del canal en minúsculas, sin acentos, espacios ni signos.
 */
object LogoNames {
    private val EXACT = mapOf(
        "espnpremiumlat" to "logo_espn_premium_ar",
        "espnpremiumargentina" to "logo_espn_premium_ar",
        "foxsports" to "logo_fox_sports_ar",
        "foxsports2" to "logo_fox_sports_2_ar",
        "foxsports3" to "logo_fox_sports_3_ar",
        "foxsportspremium" to "logo_fox_sports_premium_ar",
        "foxsportsmx" to "logo_fox_sports_ar",
        "foxsports2mx" to "logo_fox_sports_2_ar",
        "foxsports1" to "logo_fox_sports_1_us",
        "foxdeportes" to "logo_fox_sports_deportes_us",
        "tudn" to "logo_tudn_mx",
        "tycsports" to "logo_tyc_sports_ar",
        "espn2" to "logo_espn_2_ar",
        "espn3" to "logo_espn_3_ar",
        "espn4" to "logo_espn_4_br",
        "espn5" to "logo_espn_5_br",
        "espn2mx" to "logo_espn_2_ar",
        "espn3mx" to "logo_espn_3_ar",
        "espn4mx" to "logo_espn_4_br",
        "espn5mx" to "logo_espn_5_br",
        "espndeportes" to "logo_espn_deportes_us",
        "azteca7" to "logo_azteca_7_mx",
        "aztecadeportes" to "logo_azteca_mx",
        "canal5" to "logo_canal_5_mx",
        "tnt" to "logo_tnt_ar",
        "space" to "logo_space_ar",
        "warnerbrostv" to "logo_warner_channel_ar",
        "starchannel" to "logo_star_channel_ar",
        "cinemax" to "logo_cinemax_ar",
        "telefe" to "logo_telefe_ar",
        "eltrece" to "logo_eltrece_ar",
        "telemundo" to "logo_telemundo_us",
        "telemundomiami" to "logo_telemundo_us",
        "univision" to "logo_univision_us",
        "unimas" to "logo_unimas_us",
        "universo" to "logo_nbc_universo_us",
        "beinsportsespanol" to "logo_bein_sports_espanol_us",
        "beinsportsxtraespanol" to "logo_bein_sports_xtra_espanol_us",
        "beinsportsxtra" to "logo_bein_sports_xtra_us",
        "sportv" to "logo_sportv_br",
        "sportv3" to "logo_sportv3_br",
    )

    /** Si no hay coincidencia exacta, se usa el logo general de la familia del canal. */
    private val PREFIX = listOf(
        "premiere" to "logo_premiere_br",
        "espn" to "logo_espn_ar",
    )

    fun forKey(key: String): String? = EXACT[key] ?: PREFIX.firstOrNull { key.startsWith(it.first) }?.second

    /** Todos los nombres de recurso referenciados (la prueba verifica que existan). */
    val allResourceNames: Set<String> get() = (EXACT.values + PREFIX.map { it.second }).toSet()
}
