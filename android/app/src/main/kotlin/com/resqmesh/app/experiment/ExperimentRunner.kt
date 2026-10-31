package com.resqmesh.app.experiment

import com.resqmesh.app.mesh.MeshNode
import com.resqmesh.mesh.packet.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where a field experiment stands, for the Network tab. */
data class ExperimentState(
    val label: String = "hops-1",
    val running: Boolean = false,
    val sent: Int = 0,
    val target: Int = 0,
    val echoes: Int = 0,
    val echo: Boolean = false,
    val logged: Int = 0,
    val savedTo: String? = null,
)

/**
 * The test console's field experiments (screen A10, TRD 2.12): send [count] test SOS every
 * [intervalMs], echo them at the far end of the chain, and save the log as CSV. Main thread.
 */
class ExperimentRunner(private val node: MeshNode, private val log: ExperimentLog, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ExperimentState())
    val state: StateFlow<ExperimentState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        node.onExperimentEvent = { event ->
            log.add(event)
            _state.update {
                it.copy(logged = log.size, echoes = it.echoes + if (event.kind == ExperimentEventKind.ECHO_RECEIVED) 1 else 0)
            }
        }
    }

    fun setLabel(label: String) {
        log.label = label
        _state.update { it.copy(label = label) }
    }

    fun setEcho(on: Boolean) {
        node.echoTestSos = on
        _state.update { it.copy(echo = on) }
    }

    fun start(count: Int, intervalMs: Long) {
        if (job != null) return
        _state.update { it.copy(running = true, sent = 0, target = count, echoes = 0) }
        job = scope.launch {
            try {
                repeat(count) { i ->
                    node.sendTestSos(Category.FLOOD)
                    _state.update { it.copy(sent = i + 1) }
                    if (i < count - 1) delay(intervalMs)
                }
            } finally {
                job = null
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun save(): File? = log.save().also { file -> _state.update { it.copy(savedTo = file?.absolutePath) } }

    fun clear() {
        log.clear()
        _state.update { it.copy(logged = 0, echoes = 0, sent = 0, target = 0, savedTo = null) }
    }
}
