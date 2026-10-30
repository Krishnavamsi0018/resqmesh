package com.resqmesh.app.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's own language (F-01), picked on first launch. It also tells responders which language to reply in. */
enum class AppLanguage(val code: String, val nativeName: String) {
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी"),
    KANNADA("kn", "ಕನ್ನಡ"),
    ;

    companion object {
        fun of(code: String?): AppLanguage? = entries.firstOrNull { it.code == code }
    }
}

/** User settings kept across restarts. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(AppLanguage.of(prefs.getString(KEY_LANGUAGE, null)))
    /** Null until the user picks one on the first screen. */
    val language: StateFlow<AppLanguage?> = _language.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        prefs.edit { putString(KEY_LANGUAGE, language.code) }
        _language.value = language
    }

    /** Shows the language screen again (F-01: change it any time). */
    fun chooseLanguageAgain() {
        prefs.edit { remove(KEY_LANGUAGE) }
        _language.value = null
    }

    private companion object {
        const val PREFS = "resqmesh_settings"
        const val KEY_LANGUAGE = "language"
    }
}
