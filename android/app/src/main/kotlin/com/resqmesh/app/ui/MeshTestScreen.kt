package com.resqmesh.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resqmesh.app.AppGraph
import com.resqmesh.app.R
import com.resqmesh.app.mesh.LogEntry
import com.resqmesh.app.mesh.LogKind
import com.resqmesh.app.mesh.MeshService
import com.resqmesh.app.mesh.Peer
import com.resqmesh.app.ui.theme.Tokens
import com.resqmesh.mesh.packet.Category
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Mesh test console (a first cut of screens A8 and A10): start relaying, link phones (optionally
 * as a chain), send test SOS packets and watch them hop across. The real SOS screens replace it in week 6.
 */
@Composable
fun MeshTestScreen(graph: AppGraph) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(MeshPermissions.missing(context)) }
    var locationOn by remember { mutableStateOf(isLocationOn(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Settings can change while we're away (permissions granted, location toggled): re-check on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missing = MeshPermissions.missing(context)
        locationOn = isLocationOn(context)
        batteryOk = isIgnoringBatteryOptimizations(context)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = MeshPermissions.missing(context)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (missing.isNotEmpty()) {
            PermissionGate(
                onAllow = { launcher.launch(missing.toTypedArray()) },
                onOpenSettings = { openAppSettings(context) },
            )
        } else {
            MeshPanel(graph, locationOn, batteryOk)
        }
    }
}

@Composable
internal fun PermissionGate(onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.perm_grant))
        }
        // If the user said "don't ask again", only Settings can grant it.
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.perm_settings))
        }
    }
}

@Composable
private fun MeshPanel(graph: AppGraph, locationOn: Boolean, batteryOk: Boolean) {
    val context = LocalContext.current
    val running by graph.state.running.collectAsStateWithLifecycle()
    val peers by graph.state.peers.collectAsStateWithLifecycle()
    val log by graph.state.log.collectAsStateWithLifecycle()
    val counters by graph.state.counters.collectAsStateWithLifecycle()
    val held by graph.state.held.collectAsStateWithLifecycle()
    val chainOn by graph.chain.enabled.collectAsStateWithLifecycle()
    val ticked by graph.chain.ticked.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.test_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.test_node_id, graph.keys.nodeId.short),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (!locationOn) {
            item {
                Warning(stringResource(R.string.warn_location_off), stringResource(R.string.perm_settings)) {
                    openLocationSettings(context)
                }
            }
        }
        if (!batteryOk) {
            item {
                Warning(stringResource(R.string.warn_battery), stringResource(R.string.warn_battery_fix)) {
                    requestIgnoreBatteryOptimizations(context)
                }
            }
        }
        item {
            Button(
                onClick = { if (running) MeshService.stop(context) else MeshService.start(context) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(if (running) R.string.test_stop else R.string.test_start))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SendButton(stringResource(R.string.test_send_flood), Tokens.CatFlood, running, Modifier.weight(1f)) {
                    graph.node.sendTestSos(Category.FLOOD)
                }
                SendButton(stringResource(R.string.test_send_medical), Tokens.CatMedical, running, Modifier.weight(1f)) {
                    graph.node.sendTestSos(Category.MEDICAL)
                }
            }
        }
        item {
            Text(
                stringResource(
                    R.string.test_counts,
                    counters.sent, counters.accepted, counters.forwarded, counters.duplicates, counters.rejected,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pluralStringResource(R.plurals.test_held, held, held),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.test_chain_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.test_chain_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = chainOn, onCheckedChange = graph::setChainMode)
            }
        }
        item {
            Text(
                stringResource(R.string.test_peers, peers.count { it.linked }),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (peers.isEmpty()) {
            item { Text(stringResource(R.string.test_no_peers), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(peers, key = { it.endpointId }) { peer ->
                PeerRow(
                    peer = peer,
                    chainOn = chainOn,
                    ticked = peer.name.nodeId in ticked,
                    onToggle = { graph.toggleChainPeer(peer.name.nodeId) },
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.test_log), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = graph.state::clearLog) { Text(stringResource(R.string.test_clear_log)) }
            }
        }
        items(log) { LogRow(it) }
    }
}

@Composable
private fun SendButton(label: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Warning(text: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, color = Tokens.StatusWaiting, fontWeight = FontWeight.Medium)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, chainOn: Boolean, ticked: Boolean, onToggle: () -> Unit) {
    val flags = buildList {
        if (peer.name.gateway) add("gateway")
        if (peer.name.smsCapable) add("SMS")
    }.joinToString(", ")
    val status = when {
        peer.linked -> stringResource(R.string.test_peer_linked)
        peer.connecting -> stringResource(R.string.test_peer_connecting)
        chainOn && !ticked -> stringResource(R.string.test_peer_not_ticked)
        else -> stringResource(R.string.test_peer_seen)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (chainOn) Checkbox(checked = ticked, onCheckedChange = { onToggle() })
        Text(
            peer.name.nodeId.short,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(112.dp),
        )
        Text(status, color = if (peer.linked) Tokens.StatusNotified else MaterialTheme.colorScheme.onSurfaceVariant)
        if (flags.isNotEmpty()) Text(" · $flags", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.kind) {
        LogKind.ACCEPTED -> Tokens.StatusNotified
        LogKind.SENT, LogKind.SYNCED -> Tokens.StatusRelayed
        LogKind.FORWARDED -> Tokens.StatusWaiting
        LogKind.REJECTED, LogKind.ERROR -> Tokens.StatusRejected
        LogKind.DUPLICATE -> Tokens.StatusClosed
        LogKind.LINK -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row {
        Text(
            TIME.format(Instant.ofEpochMilli(entry.atMillis)),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(entry.text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())
