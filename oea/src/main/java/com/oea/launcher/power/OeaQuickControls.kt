package com.oea.launcher.power

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.R
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.assistant.OeaSystemActions
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType

/**
 * OEA's quick controls, as a view rather than a screen.
 *
 * They used to be an Activity, and that was the whole problem: opening quick
 * controls pulled the user *out* of the launcher, and anything that went wrong on
 * the way out looked like "OEA threw me out of Home". Building the sheet as a
 * plain view means Home can show it in place — above the icons, below nothing —
 * and the activity (opened from the Quick Settings tile or Settings) uses exactly
 * the same code, so the two can never drift apart.
 *
 * Contents are the essentials when a hardware key fails: lock, the power menu,
 * volume, sound mode, Do Not Disturb and the notification shade, each with a
 * visible explanation when the phone has not granted the capability it needs.
 */
object OeaQuickControls {
    /**
     * Builds the sheet for [context]. [onClose] is called when the user asks the
     * sheet to go away; the caller decides whether that means finishing an
     * activity or removing a view from Home.
     */
    fun sheet(context: Context, palette: OeaPalette, onClose: () -> Unit): View {
        val kit = OeaKit(context, palette)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        fun toast(message: String) = runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        fun launch(intent: Intent) {
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast("Android doesn't offer that screen on this phone.") }
            onClose()
        }

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
            background = kit.roundedTop(palette.background, 30)
            isClickable = true
        }
        sheet.addView(
            View(context).apply { background = kit.rounded(OeaTheme.alpha(palette.textSecondary, 0x66), 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) },
        )
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(kit.text("Quick controls", 20f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(kit.circleIconButton(R.drawable.oic_close, "Close", palette.cardHigh, palette.text) { onClose() })
        sheet.addView(head)
        sheet.addView(kit.text("Screen, sound and interruptions — without leaving what you were doing", 12.5f, palette.textSecondary).apply { setPadding(0, dp(2), 0, dp(12)) })

        // ---- the four one-tap actions
        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(icon: Int, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
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
            action(R.drawable.oic_lock, "Lock screen") {
                when (OeaPower.lockScreen(context)) {
                    OeaPower.LockResult.LOCKED -> onClose()
                    OeaPower.LockResult.NEEDS_SETUP -> {
                        // Say what is missing instead of appearing to do nothing.
                        toast("OEA needs its Assistant or Device admin to lock the screen")
                        launch(Intent(context, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power"))
                    }
                }
            },
            action(R.drawable.oic_restart_alt, "Power menu") {
                if (!OeaAssistant.openPowerMenu()) {
                    toast("OEA Assistant is off, so Android's power menu cannot be opened from here")
                    OeaAssistant.openSetup(context)
                }
            },
            action(R.drawable.oic_volume_up, "Volume") { OeaPower.showVolumePanel(context) },
            action(R.drawable.oic_notifications, "Notifications") {
                if (!OeaSystemActions.expandNotifications(context)) toast("OEA Assistant is off, so notifications cannot be pulled down from here")
            },
        ).forEachIndexed { index, view ->
            grid.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        sheet.addView(grid)

        // ---- volume sliders, straight from Android's own streams
        val audio = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()
        if (audio != null) {
            kit.sectionTitle(sheet, "Volume")
            val group = kit.group(sheet)
            volumeRow(context, kit, palette, group, R.drawable.oic_music_note, "Media", AudioManager.STREAM_MUSIC, audio, onClose)
            volumeRow(context, kit, palette, group, R.drawable.oic_call, "Ring & notifications", AudioManager.STREAM_RING, audio, onClose)
            volumeRow(context, kit, palette, group, R.drawable.oic_alarm, "Alarms", AudioManager.STREAM_ALARM, audio, onClose)
            kit.sectionTitle(sheet, "Sound mode")
            val modes = listOf(
                AudioManager.RINGER_MODE_NORMAL to "Sound",
                AudioManager.RINGER_MODE_VIBRATE to "Vibrate",
                AudioManager.RINGER_MODE_SILENT to "Silent",
            )
            val current = modes.indexOfFirst { it.first == audio.ringerMode }.coerceAtLeast(0)
            sheet.addView(kit.segmented(modes.map { it.second }, current) { index ->
                if (!OeaPower.setRinger(context, modes[index].first)) {
                    toast("Allow OEA's Do Not Disturb access so it can switch to silent")
                    runCatching { context.startActivity(OeaPower.policyAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            })
        }

        // ---- Do Not Disturb, with an honest row when the access is missing
        val dndGroup = kit.group(sheet).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12) }
        if (OeaPower.policyAccess(context)) {
            kit.switchRow(dndGroup, R.drawable.oic_do_not_disturb_on, "Do Not Disturb", "Priority-only interruptions", OeaPower.dndOn(context)) { on ->
                if (!OeaPower.setDnd(context, on)) toast("Android refused the Do Not Disturb change")
            }
        } else {
            kit.row(dndGroup, R.drawable.oic_do_not_disturb_on, "Do Not Disturb", "Allow OEA's Do Not Disturb access to switch it here") {
                runCatching { context.startActivity(OeaPower.policyAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        kit.row(dndGroup, R.drawable.oic_tune, "Buttons & screen", "Wave to wake, lock options and these controls") {
            launch(Intent(context, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power"))
        }
        return sheet
    }

    /**
     * One stream's slider. Ring volume is floored above zero unless OEA holds Do
     * Not Disturb access, because dragging ring volume to zero *is* switching the
     * phone to silent, and Android refuses that silently otherwise — a control
     * that appears not to work is worse than one that is explained.
     */
    private fun volumeRow(
        context: Context,
        kit: OeaKit,
        palette: OeaPalette,
        group: LinearLayout,
        icon: Int,
        label: String,
        stream: Int,
        audio: AudioManager,
        onClose: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val max = runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(0)
        if (max <= 0) return
        val min = if (Build.VERSION.SDK_INT >= 28) runCatching { audio.getStreamMinVolume(stream) }.getOrDefault(0) else 0
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(kit.iconView(icon, palette.accent, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
        head.addView(kit.text(label, 14.5f, palette.text, OeaType.medium))
        box.addView(head)
        val floor = if (stream == AudioManager.STREAM_RING && !OeaPower.policyAccess(context)) maxOf(min, 1) else min
        box.addView(SeekBar(context).apply {
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
}
