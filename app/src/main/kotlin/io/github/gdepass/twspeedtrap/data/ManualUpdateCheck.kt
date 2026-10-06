package io.github.gdepass.twspeedtrap.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "check for update now" button, held outside any screen so a rotation
 * or a back press while the download runs neither cancels it nor loses the
 * result the rider came back to read.
 */
object ManualUpdateCheck {
    data class State(
        val running: Boolean = false,
        val result: UpdateResult? = null,
        /** Bumped on every finished check so screens can refresh the db metadata. */
        val completedCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(appContext: Context) {
        if (_state.value.running) return
        _state.update { it.copy(running = true, result = null) }
        scope.launch {
            val result = DbUpdater(appContext).checkAndUpdate()
            _state.update { it.copy(running = false, result = result, completedCount = it.completedCount + 1) }
        }
    }
}
