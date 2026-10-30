package com.resqmesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resqmesh.app.R
import com.resqmesh.mesh.packet.Category
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Screen A5 (F-11): a 5-second shrinking ring with a large Cancel. It sends at zero, or at once
 * on Send now. The people chips map to the SOS's ppl field: 1, 2 (two to five) or 5 (five or more).
 */
@Composable
fun CountdownScreen(category: Category, sending: Boolean, onSend: (people: Int) -> Unit, onCancel: () -> Unit) {
    val style = category.style()
    val haptics = LocalHapticFeedback.current
    var remaining by rememberSaveable { mutableFloatStateOf(COUNTDOWN_S) }
    var people by rememberSaveable { mutableIntStateOf(1) }
    val send = {
        if (!sending) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onSend(people)
        }
    }

    LaunchedEffect(sending) {
        while (!sending && remaining > 0f) {
            delay(TICK_MS)
            remaining = (remaining - TICK_MS / 1000f).coerceAtLeast(0f)
        }
        if (!sending) send()
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(40.dp))
            Text(
                stringResource(style.label),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
            val track = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                drawArc(style.color, -90f, 360f * remaining / COUNTDOWN_S, false, topLeft, arcSize, style = Stroke(stroke))
            }
            if (sending) {
                CircularProgressIndicator(color = style.color)
            } else {
                Text(ceil(remaining).toInt().toString(), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            stringResource(if (sending) R.string.countdown_sending else R.string.countdown_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.countdown_people), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in PEOPLE) {
                FilterChip(
                    selected = people == value,
                    onClick = { people = value },
                    enabled = !sending,
                    label = { Text(label, fontSize = 18.sp, modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) },
                )
            }
        }
        Button(
            onClick = send,
            enabled = !sending,
            colors = ButtonDefaults.buttonColors(containerColor = style.color, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(stringResource(R.string.action_send_now), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onCancel, enabled = !sending, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(stringResource(R.string.action_cancel), fontSize = 20.sp)
        }
    }
}

private const val COUNTDOWN_S = 5f
private const val TICK_MS = 100L
private val PEOPLE = listOf(1 to "1", 2 to "2–5", 5 to "5+")
