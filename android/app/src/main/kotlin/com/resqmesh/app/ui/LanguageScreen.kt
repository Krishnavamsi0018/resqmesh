package com.resqmesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resqmesh.app.R
import com.resqmesh.app.settings.AppLanguage

/** Screen A1: the first thing a new user sees. Each language is written in its own script. */
@Composable
fun LanguageScreen(onPick: (AppLanguage) -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.headlineSmall)
        for (language in AppLanguage.entries) {
            OutlinedButton(onClick = { onPick(language) }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                Text(language.nativeName, fontSize = 24.sp)
            }
        }
    }
}
