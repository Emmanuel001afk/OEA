package com.oea.launcher.power

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.R
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType

/**
 * Turns the screen on over the lock screen and finishes at once, leaving the
 * lock screen in front (Android's supported way to wake the display).
 */
class OeaWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 400L)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/**
 * Quick controls: a sheet over whatever is on screen with lock, power menu,
 * volume, ringer and Do Not Disturb — the essentials when a power or volume
 * key fails. Opened from its Quick Settings tile, Home gestures or Settings.
 */
/**
 * Quick controls as a screen: the same sheet Home shows in place, for the Quick
 * Settings tile and for Settings. It is deliberately a thin wrapper — one sheet,
 * one set of behaviours, no second copy to fall out of step.
 */
class OeaQuickControlsActivity : Activity() {
    private lateinit var palette: OeaPalette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Contain any fault in this sheet: it must never be seen as a launcher crash.
        val ok = com.oea.launcher.fault.OeaFaultShield.guard("quick-controls:create") { render() }
        if (!ok) {
            com.oea.launcher.ui.OeaScreenFailure.show(this, "Quick Controls", IllegalStateException("Quick controls could not be displayed"))
        }
    }

    override fun onResume() {
        super.onResume()
        com.oea.launcher.fault.OeaFaultShield.guard("quick-controls:resume") { render() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun render() {
        try {
            palette = OeaTheme.resolve(this)
            OeaInsets.edgeToEdge(window, lightBars = false)
            runCatching { window.decorView.setBackgroundColor(Color.TRANSPARENT) }
            val root = FrameLayout(this).apply {
                setBackgroundColor(OeaTheme.alpha(Color.BLACK, 0x66))
                setOnClickListener { finish() }
            }
            val sheet = try {
                OeaQuickControls.sheet(this, palette) { finish() }
            } catch (e: Throwable) {
                com.oea.launcher.fault.OeaFaultShield.note("quick-controls:sheet", e)
                // Fallback: minimal controls so user is not stuck with black screen.
                OeaQuickControls.fallbackSheet(this, palette) { finish() }
            }
            val scroll = android.widget.ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(sheet)
            }
            root.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            root.setOnApplyWindowInsetsListener { _, insets ->
                runCatching {
                    val bars = OeaInsets.bars(insets)
                    sheet.setPadding(dp(18), dp(10), dp(18), dp(18) + bars[3])
                }
                insets
            }
            setContentView(root)
        } catch (e: Throwable) {
            com.oea.launcher.fault.OeaFaultShield.note("quick-controls:render", e)
            throw e
        }
    }
}

/** Quick Settings tile: lock the screen from anywhere (tap), Buttons & screen settings (long-press). */
class OeaLockTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Lock screen"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (OeaAssistant.isConnected() || OeaPower.deviceAdminCanLock(this@OeaLockTileService)) "OEA" else "Set up in OEA"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (OeaPower.lockScreen(this) == OeaPower.LockResult.LOCKED) return
        openInSettings(this, Intent(this, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power"))
    }
}

/** Quick Settings tile: opens OEA's Quick controls sheet over the current app. */
class OeaQuickControlsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Quick controls"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        openInSettings(this, Intent(this, OeaQuickControlsActivity::class.java))
    }
}

/** startActivityAndCollapse with the PendingIntent form Android 14+ requires. */
private fun openInSettings(service: TileService, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        if (Build.VERSION.SDK_INT >= 34) {
            service.startActivityAndCollapse(PendingIntent.getActivity(service, intent.component?.className.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            service.startActivityAndCollapse(intent)
        }
    }.onFailure { runCatching { service.startActivity(intent) } }
}
