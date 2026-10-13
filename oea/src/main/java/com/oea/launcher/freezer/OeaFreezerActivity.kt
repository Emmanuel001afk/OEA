package com.oea.launcher.freezer

import com.oea.launcher.OeaSystemToolsActivity

/**
 * Freezer as a standalone OEA app, with its own icon, label and task, like
 * Game Boost, Phone and Call Blocker. It opens straight on the freezer screen
 * and can be placed on Home like any other app.
 */
class OeaFreezerActivity : OeaSystemToolsActivity() {
    override val rootScreen: String get() = "freezer"
}
