package app.lawnchair.oea

import android.app.Application
import app.lawnchair.oea.runtime.OeaRuntime

/**
 * OEA application owner.
 *
 * The application process is initialized by OEA itself. Launcher3/Lawnchair
 * application state is intentionally not started from here.
 */
class OeaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OeaRuntime.initialize(this)
    }
}
