package com.resqmesh.app.settings

import android.content.Context
import androidx.core.content.edit
import com.resqmesh.app.BuildConfig
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

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, null) ?: BuildConfig.API_BASE_URL)
    /** The backend a gateway uploads to (F-05 developer option); the built-in server unless a tester changed it. */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    /** Saving an empty address goes back to the built-in server. */
    fun setServerUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit { if (clean.isEmpty()) remove(KEY_SERVER_URL) else putString(KEY_SERVER_URL, clean) }
        _serverUrl.value = clean.ifEmpty { BuildConfig.API_BASE_URL }
    }

    private val _smsNumber = MutableStateFlow(prefs.getString(KEY_SMS_NUMBER, null) ?: SmsPolicy.allowed.firstOrNull().orEmpty())
    /** Where SOS are texted (F-41): a team number from the build's allow-list, the first one by default. */
    val smsNumber: StateFlow<String> = _smsNumber.asStateFlow()

    /** Saves [number] if the policy allows it; returns whether it did. */
    fun setSmsNumber(number: String): Boolean {
        val clean = SmsPolicy.normalize(number)
        if (!SmsPolicy.isAllowed(clean)) return false
        prefs.edit { putString(KEY_SMS_NUMBER, clean) }
        _smsNumber.value = clean
        return true
    }

    private val _smsEnabled = MutableStateFlow(prefs.getBoolean(KEY_SMS_ENABLED, true))
    val smsEnabled: StateFlow<Boolean> = _smsEnabled.asStateFlow()

    fun setSmsEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_SMS_ENABLED, on) }
        _smsEnabled.value = on
    }

    /** Shows the language screen again (F-01: change it any time). */
    fun chooseLanguageAgain() {
        prefs.edit { remove(KEY_LANGUAGE) }
        _language.value = null
    }

    private companion object {
        const val PREFS = "resqmesh_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SMS_NUMBER = "sms_number"
        const val KEY_SMS_ENABLED = "sms_enabled"
    }
}
