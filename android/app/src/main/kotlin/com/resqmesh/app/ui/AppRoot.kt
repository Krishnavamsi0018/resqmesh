package com.resqmesh.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resqmesh.app.AppGraph
import com.resqmesh.app.R
import com.resqmesh.mesh.packet.Category
import kotlinx.coroutines.delay

private enum class Tab { HOME, STATUS, NETWORK }

/** The whole app: language (A1), permissions (A3), then Home (A4/A5), My SOS (A6) and Network (A8/A10). */
@Composable
fun AppRoot(graph: AppGraph) {
    val language by graph.settings.language.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val chosen = language
        if (chosen == null) {
            LanguageScreen(onPick = graph.settings::setLanguage)
        } else {
            Localized(chosen) { PermissionsThen { MainTabs(graph) } }
        }
    }
}

@Composable
private fun PermissionsThen(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(MeshPermissions.missing(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { missing = MeshPermissions.missing(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = MeshPermissions.missing(context)
    }
    if (missing.isEmpty()) {
        content()
    } else {
        PermissionGate(onAllow = { launcher.launch(missing.toTypedArray()) }, onOpenSettings = { openAppSettings(context) })
    }
}

@Composable
private fun MainTabs(graph: AppGraph) {
    val context = LocalContext.current
    val running by graph.state.running.collectAsStateWithLifecycle()
    val peers by graph.state.peers.collectAsStateWithLifecycle()
    val mySos by graph.state.mySos.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var countdown by rememberSaveable { mutableStateOf<Category?>(null) }
    var sending by rememberSaveable { mutableStateOf(false) }
    var locationOn by remember { mutableStateOf(isLocationOn(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { locationOn = isLocationOn(context) }

    LaunchedEffect(Unit) { graph.autoStartRelay() }
    // An open SOS closes itself after 12 h; check now and then while the app is on screen.
    LaunchedEffect(Unit) {
        while (true) {
            graph.node.refreshSos()
            delay(60_000)
        }
    }

    val pending = countdown
    if (pending != null) {
        val cancel = {
            graph.abandonSos()
            countdown = null
        }
        BackHandler(enabled = !sending, onBack = cancel)
        CountdownScreen(
            category = pending,
            sending = sending,
            onSend = { people ->
                sending = true
                graph.sendSos(pending, people) {
                    sending = false
                    countdown = null
                    tab = Tab.STATUS
                }
            },
            onCancel = cancel,
        )
        return
    }

    val showStatus = mySos != null
    if (tab == Tab.STATUS && !showStatus) tab = Tab.HOME

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                )
                if (showStatus) {
                    NavigationBarItem(
                        selected = tab == Tab.STATUS,
                        onClick = { tab = Tab.STATUS },
                        icon = { Icon(Icons.Filled.Sos, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_status)) },
                    )
                }
                NavigationBarItem(
                    selected = tab == Tab.NETWORK,
                    onClick = { tab = Tab.NETWORK },
                    icon = { Icon(Icons.Filled.Hub, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_network)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    running = running,
                    linked = peers.count { it.linked },
                    locationOn = locationOn,
                    mySos = mySos,
                    onPick = { category ->
                        graph.prepareSos()
                        countdown = category
                    },
                    onOpenStatus = { tab = Tab.STATUS },
                    onFixLocation = { openLocationSettings(context) },
                    onChangeLanguage = graph.settings::chooseLanguageAgain,
                )
                Tab.STATUS -> mySos?.let { sos ->
                    StatusScreen(sos, onCancel = graph.node::cancelSos, onDismiss = {
                        graph.node.dismissSos()
                        tab = Tab.HOME
                    })
                }
                Tab.NETWORK -> MeshTestScreen(graph)
            }
        }
    }
}
