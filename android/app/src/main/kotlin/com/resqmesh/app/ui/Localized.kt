package com.resqmesh.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.resqmesh.app.settings.AppLanguage
import java.util.Locale

/**
 * Shows [content] in the app's own language, whatever the phone's language is (F-01). Only the
 * resources change: the context still reaches the activity, so permission requests and
 * startActivity keep working.
 */
@Composable
fun Localized(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val systemConfig = LocalConfiguration.current
    val config = remember(language, systemConfig) {
        Configuration(systemConfig).apply { setLocale(Locale.forLanguageTag(language.code)) }
    }
    val context = remember(base, config) { LocaleContext(base, base.createConfigurationContext(config).resources) }
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config, content = content)
}

private class LocaleContext(base: Context, private val localized: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
}
