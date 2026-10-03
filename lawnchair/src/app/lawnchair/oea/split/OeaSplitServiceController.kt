package app.lawnchair.oea.split

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class OeaSplitServiceController(private val context: Context) {
    enum class State { PAUSED, TRIGGER_SPLIT, LAUNCH_SECOND }
    @Volatile var state: State = State.PAUSED
        private set
    private var pair: Pair<Intent, Intent>? = null
    private var resetJob: Job? = null

    fun begin(first: Intent, second: Intent) {
        if (state != State.PAUSED) return
        pair = first to second
        state = State.TRIGGER_SPLIT
        launch(first)
        resetJob?.cancel()
        resetJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(4000)
            reset()
        }
    }

    fun onWindowEvent(packageName: String, splitVisible: Boolean, triggerSplit: () -> Unit) {
        val current = pair ?: return
        when (state) {
            State.TRIGGER_SPLIT -> if (packageName == current.first.`package`) {
                triggerSplit()
                state = State.LAUNCH_SECOND
            }
            State.LAUNCH_SECOND -> when {
                splitVisible && packageName != current.second.`package` -> launch(current.second)
                splitVisible && packageName == current.second.`package` -> reset()
            }
            State.PAUSED -> Unit
        }
    }

    fun reset() {
        resetJob?.cancel()
        resetJob = null
        pair = null
        state = State.PAUSED
    }

    private fun launch(intent: Intent) { runCatching { context.startActivity(intent) } }
}
