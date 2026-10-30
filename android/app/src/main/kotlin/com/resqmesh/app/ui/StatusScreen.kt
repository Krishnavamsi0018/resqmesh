package com.resqmesh.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resqmesh.app.R
import com.resqmesh.app.ui.theme.Tokens
import com.resqmesh.mesh.packet.CancelReason
import com.resqmesh.mesh.packet.ResponderCodes
import com.resqmesh.mesh.sos.CloseReason
import com.resqmesh.mesh.sos.MySos
import com.resqmesh.mesh.sos.Reply
import com.resqmesh.mesh.sos.SosStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Screen A6 (F-14, F-16): the 4-step tracker, the responder's reply, and I'm safe. */
@Composable
fun StatusScreen(sos: MySos, onCancel: (CancelReason) -> Unit, onDismiss: () -> Unit) {
    val style = sos.category.style()
    var confirm by rememberSaveable { mutableStateOf<CancelReason?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(40.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.status_title, stringResource(style.label)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.status_sent_at, time(sos.sentAt)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!sos.isOpen) {
            Closed(sos.closeReason, onDismiss)
            return@Column
        }

        sos.reply?.let { ReplyCard(it, sos.ackedAt) }
        Stepper(sos)
        Text(
            if (sos.located) {
                sos.accuracyM?.let { stringResource(R.string.status_location, it) } ?: stringResource(R.string.status_location_no_accuracy)
            } else {
                stringResource(R.string.status_location_unknown)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { confirm = CancelReason.SAFE },
            colors = ButtonDefaults.buttonColors(containerColor = Tokens.StatusNotified, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(stringResource(R.string.action_safe), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { confirm = CancelReason.MISTAKE }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_mistake))
        }
    }

    confirm?.let { reason ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (reason == CancelReason.SAFE) R.string.confirm_safe_title else R.string.confirm_mistake_title)) },
            text = { Text(stringResource(R.string.confirm_cancel_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onCancel(reason)
                }) { Text(stringResource(R.string.confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.confirm_no)) } },
        )
    }
}

/** Four steps, each with icon, words and time; the current one bold (design 3.5). */
@Composable
private fun Stepper(sos: MySos) {
    val current = sos.step
    val steps = listOf(
        Step(SosStep.WAITING, Icons.Filled.HourglassTop, Tokens.StatusWaiting, stringResource(R.string.step_waiting), sos.sentAt),
        Step(
            SosStep.RELAYED,
            Icons.Filled.Share,
            Tokens.StatusRelayed,
            if (sos.passedTo.isEmpty()) {
                stringResource(R.string.step_relayed_pending)
            } else {
                pluralStringResource(R.plurals.step_relayed, sos.passedTo.size, sos.passedTo.size)
            },
            sos.relayedAt,
        ),
        Step(SosStep.NOTIFIED, Icons.Filled.CloudDone, Tokens.StatusNotified, stringResource(R.string.step_notified), sos.notifiedAt),
        Step(SosStep.ACKED, Icons.Filled.Verified, Tokens.StatusAcked, stringResource(R.string.step_acked), sos.ackedAt),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (step in steps) {
            val reached = step.step.ordinal <= current.ordinal
            val isCurrent = step.step == current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (reached) step.color else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        step.icon,
                        contentDescription = null,
                        tint = if (reached) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        step.text,
                        fontSize = 18.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isCurrent && step.step == SosStep.WAITING) {
                        Text(stringResource(R.string.step_waiting_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (reached) step.at?.let { Text(time(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private data class Step(val step: SosStep, val icon: ImageVector, val color: Color, val text: String, val at: Long?)

/** The responder's message in large type, in the sender's language when it's a known code. */
@Composable
private fun ReplyCard(reply: Reply, at: Long?) {
    val text = replyText(reply.code) ?: reply.message.orEmpty()
    Card(colors = CardDefaults.cardColors(containerColor = Tokens.StatusAcked)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            // A free-text note from the operator comes after the translated code.
            val note = reply.message
            if (replyText(reply.code) != null && !note.isNullOrBlank()) {
                Text(note, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
            }
            val by = listOfNotNull(reply.unit, at?.let(::time)).joinToString(" · ")
            if (by.isNotEmpty()) Text(by, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun replyText(code: String?): String? = replyResource(code)?.let { stringResource(it) }

@StringRes
private fun replyResource(code: String?): Int? = when (code) {
    ResponderCodes.TEAM_DISPATCHED -> R.string.ack_team_dispatched
    ResponderCodes.STAY_PUT -> R.string.ack_stay_put
    ResponderCodes.MOVE_HIGHER -> R.string.ack_move_higher
    else -> null
}

@Composable
private fun Closed(reason: CloseReason?, onDismiss: () -> Unit) {
    val text = when (reason) {
        CloseReason.SAFE -> R.string.closed_safe
        CloseReason.MISTAKE -> R.string.closed_mistake
        CloseReason.EXPIRED, null -> R.string.closed_expired
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Cancel, contentDescription = null, tint = Tokens.StatusClosed)
        Text(stringResource(text), fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp))
    }
    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(stringResource(R.string.action_back_home))
    }
}

/** Western digits in every language (design 3.8). */
private fun time(epochMs: Long): String = TIME.format(Instant.ofEpochMilli(epochMs))

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())
