package com.oea.launcher

import android.app.Application
import com.oea.launcher.runtime.OeaRuntime

/** OEA application owner. */
class OeaApplication : Application() {
    override fun onCreate() { super.onCreate(); OeaRuntime.initialize(this) }
}
