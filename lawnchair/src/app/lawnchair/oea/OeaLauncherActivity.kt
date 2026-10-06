package app.lawnchair.oea

import android.app.Activity
import android.os.Bundle
import app.lawnchair.oea.engine.OeaLauncherEngine

/**
 * OEA's native HOME entry point.
 *
 * This replaces Launcher3/Quickstep as the runtime owner of HOME.
 */
class OeaLauncherActivity : Activity() {
    private lateinit var engine: OeaLauncherEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = OeaLauncherEngine(this)
        engine.start()
        setContentView(engine.workspace)
    }

    override fun onResume() {
        super.onResume()
        if (::engine.isInitialized) {
            engine.start()
        }
    }
}
