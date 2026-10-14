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
class OeaQuickControlsActivity : Activity() {
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaKit
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun render() {
        palette = OeaTheme.resolve(this)
        kit = OeaKit(this, palette)
        OeaInsets.edgeToEdge(window, lightBars = false)
        val root = FrameLayout(this).apply {
            setBackgroundColor(OeaTheme.alpha(Color.BLACK, 0x66))
            setOnClickListener { finish() }
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
            background = kit.roundedTop(palette.background, 30)
            isClickable = true
        }
        sheet.addView(View(this).apply { background = kit.rounded(OeaTheme.alpha(palette.textSecondary, 0x66), 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        sheet.addView(kit.text("Quick controls", 21f, palette.text, OeaType.medium))
        sheet.addView(kit.text("Screen, power menu, sound and Do Not Disturb", 13f, palette.textSecondary).apply { setPadding(0, dp(2), 0, dp(14)) })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(icon: Int, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(10))
            background = kit.ripple(kit.rounded(palette.card, 20), 20)
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(kit.iconBadge(icon, palette.accentSoft, palette.onAccentSoft, 42), LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(kit.text(label, 12f, palette.text, OeaType.medium).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                maxLines = 2
            })
        }
        listOf(
            action(R.drawable.oic_lock, "Lock screen") { lock() },
            action(R.drawable.oic_restart_alt, "Power menu") { powerMenu() },
            action(R.drawable.oic_volume_up, "Volume panel") { OeaPower.showVolumePanel(this) },
            action(R.drawable.oic_notifications, "Notifications") { notifications() },
        ).forEachIndexed { i, view ->
            grid.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        sheet.addView(grid)

        val audio = getSystemService(AudioManager::class.java)
        if (audio != null) {
            kit.sectionTitle(sheet, "Volume")
            val group = kit.group(sheet)
            volumeRow(group, R.drawable.oic_music_note, "Media", AudioManager.STREAM_MUSIC, audio)
            volumeRow(group, R.drawable.oic_call, "Ring & notifications", AudioManager.STREAM_RING, audio)
            volumeRow(group, R.drawable.oic_alarm, "Alarms", AudioManager.STREAM_ALARM, audio)
            kit.sectionTitle(sheet, "Sound mode")
            val modes = listOf(AudioManager.RINGER_MODE_NORMAL to "Sound", AudioManager.RINGER_MODE_VIBRATE to "Vibrate", AudioManager.RINGER_MODE_SILENT to "Silent")
            sheet.addView(kit.segmented(modes.map { it.second }, modes.indexOfFirst { it.first == audio.ringerMode }.coerceAtLeast(0)) { index ->
                if (!OeaPower.setRinger(this, modes[index].first)) {
                    toast("Allow Do Not Disturb access so OEA can switch to silent.")
                    runCatching { startActivity(OeaPower.policyAccessIntent()) }
                }
                main.postDelayed({ render() }, 250L)
            })
        }
        val dndGroup = kit.group(sheet).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12) }
        if (OeaPower.policyAccess(this)) {
            kit.switchRow(dndGroup, R.drawable.oic_do_not_disturb_on, "Do Not Disturb", "Priority-only interruptions", OeaPower.dndOn(this)) { on ->
                OeaPower.setDnd(this, on)
            }
        } else {
            kit.row(dndGroup, R.drawable.oic_do_not_disturb_on, "Do Not Disturb", "Allow OEA's Do Not Disturb access to switch it here") {
                runCatching { startActivity(OeaPower.policyAccessIntent()) }
            }
        }
        kit.row(dndGroup, R.drawable.oic_tune, "Do Not Disturb settings", null) { open(OeaPower.dndSettingsIntent()) }
        kit.row(dndGroup, R.drawable.oic_notifications, "Notification settings", null) { open(OeaPower.notificationSettingsIntent()) }
        kit.row(dndGroup, R.drawable.oic_power, "Buttons & screen", "Wave to wake, lock options and these controls") {
            open(Intent(this, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power"))
        }

        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(sheet)
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            sheet.setPadding(dp(20), dp(12), dp(20), dp(20) + bars[3])
            insets
        }
        setContentView(root)
    }

    private fun volumeRow(group: LinearLayout, icon: Int, label: String, stream: Int, audio: AudioManager) {
        val max = runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(0)
        if (max <= 0) return
        val min = if (Build.VERSION.SDK_INT >= 28) runCatching { audio.getStreamMinVolume(stream) }.getOrDefault(0) else 0
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(kit.iconView(icon, palette.accent, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
        head.addView(kit.text(label, 14.5f, palette.text, OeaType.medium))
        box.addView(head)
        // Ring volume 0 would switch the phone to silent, which needs DND access.
        val floor = if (stream == AudioManager.STREAM_RING && !OeaPower.policyAccess(this)) maxOf(min, 1) else min
        box.addView(SeekBar(this).apply {
            this.max = max - floor
            progress = (audio.getStreamVolume(stream) - floor).coerceIn(0, max - floor)
            contentDescription = "$label volume"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) runCatching { audio.setStreamVolume(stream, value + floor, 0) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        group.addView(box)
    }

    private fun lock() {
        when (OeaPower.lockScreen(this)) {
            OeaPower.LockResult.LOCKED -> finish()
            OeaPower.LockResult.NEEDS_SETUP -> {
                toast("Turn on OEA Assistant (or OEA's Device admin) so OEA can lock the screen.")
                open(Intent(this, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power"))
            }
        }
    }

    private fun powerMenu() {
        if (OeaPower.openPowerMenu()) {
            finish()
            return
        }
        toast("Turn on OEA Assistant to open Android's power menu.")
        OeaAssistant.openSetup(this)
    }

    private fun notifications() {
        if (com.oea.launcher.assistant.OeaSystemActions.expandNotifications(this)) finish()
        else toast("Turn on OEA Assistant to open notifications from here.")
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Android doesn't offer that screen on this phone.") }
        finish()
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
