package com.example.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    fun wrap(base: Context, languageTag: String): Context {
        if (languageTag.isBlank()) return base
        val locale = when (languageTag) {
            "fa" -> Locale.forLanguageTag("fa-IR")
            "en" -> Locale.forLanguageTag("en-US")
            else -> Locale.forLanguageTag(languageTag)
        } ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
