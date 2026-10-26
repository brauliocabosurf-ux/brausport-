package com.brau.sportsplus

/** Nombre de recurso (de LogoNames) → drawable local. Sin descargas: los logos viven en el APK. */
object LogoResources {
    private val MAP: Map<String, Int> = mapOf(
        "logo_azteca_7_mx" to R.drawable.logo_azteca_7_mx,
        "logo_azteca_mx" to R.drawable.logo_azteca_mx,
        "logo_bein_sports_espanol_us" to R.drawable.logo_bein_sports_espanol_us,
        "logo_bein_sports_xtra_espanol_us" to R.drawable.logo_bein_sports_xtra_espanol_us,
        "logo_bein_sports_xtra_us" to R.drawable.logo_bein_sports_xtra_us,
        "logo_canal_5_mx" to R.drawable.logo_canal_5_mx,
        "logo_cinemax_ar" to R.drawable.logo_cinemax_ar,
        "logo_eltrece_ar" to R.drawable.logo_eltrece_ar,
        "logo_espn_2_ar" to R.drawable.logo_espn_2_ar,
        "logo_espn_3_ar" to R.drawable.logo_espn_3_ar,
        "logo_espn_4_br" to R.drawable.logo_espn_4_br,
        "logo_espn_5_br" to R.drawable.logo_espn_5_br,
        "logo_espn_ar" to R.drawable.logo_espn_ar,
        "logo_espn_deportes_us" to R.drawable.logo_espn_deportes_us,
        "logo_espn_premium_ar" to R.drawable.logo_espn_premium_ar,
        "logo_fox_sports_1_us" to R.drawable.logo_fox_sports_1_us,
        "logo_fox_sports_2_ar" to R.drawable.logo_fox_sports_2_ar,
        "logo_fox_sports_3_ar" to R.drawable.logo_fox_sports_3_ar,
        "logo_fox_sports_ar" to R.drawable.logo_fox_sports_ar,
        "logo_fox_sports_deportes_us" to R.drawable.logo_fox_sports_deportes_us,
        "logo_fox_sports_premium_ar" to R.drawable.logo_fox_sports_premium_ar,
        "logo_nbc_universo_us" to R.drawable.logo_nbc_universo_us,
        "logo_premiere_br" to R.drawable.logo_premiere_br,
        "logo_space_ar" to R.drawable.logo_space_ar,
        "logo_sportv3_br" to R.drawable.logo_sportv3_br,
        "logo_sportv_br" to R.drawable.logo_sportv_br,
        "logo_star_channel_ar" to R.drawable.logo_star_channel_ar,
        "logo_telefe_ar" to R.drawable.logo_telefe_ar,
        "logo_telemundo_us" to R.drawable.logo_telemundo_us,
        "logo_tnt_ar" to R.drawable.logo_tnt_ar,
        "logo_tudn_mx" to R.drawable.logo_tudn_mx,
        "logo_tyc_sports_ar" to R.drawable.logo_tyc_sports_ar,
        "logo_unimas_us" to R.drawable.logo_unimas_us,
        "logo_univision_us" to R.drawable.logo_univision_us,
        "logo_warner_channel_ar" to R.drawable.logo_warner_channel_ar,
    )

    fun forName(name: String?): Int? = name?.let { MAP[it] }
}
