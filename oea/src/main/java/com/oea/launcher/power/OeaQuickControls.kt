package com.oea.launcher.power

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.R
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.assistant.OeaSystemActions
import com.oea.launcher.camera.OeaTorch
import com.oea.launcher.fault.OeaFaultShield
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
 *
 * After the stability audit this is now a proper Notification Centre + Quick Settings:
 * - Quick Settings tiles (Wi-Fi, Bluetooth, Flashlight, Rotation, Location, etc.)
 * - Brightness slider with system or window fallback
 * - Volume sliders, sound mode, DND
 * - Notification list from OeaNotificationState (when granted)
 * - Lock, power menu, buttons & screen entry
 *
 * Every row is guarded; a failing tile never takes the whole sheet down.
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
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) }
)
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(kit.text("Quick controls", 20f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(kit.circleIconButton(R.drawable.oic_close, "Close", palette.cardHigh, palette.text) { onClose() })
        sheet.addView(head)
        sheet.addView(kit.text("Screen, sound, connectivity and notifications — without leaving Home", 12.5f, palette.textSecondary).apply { setPadding(0, dp(2), 0, dp(12)) })

        // ---- primary actions
        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(icon: Int, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(10))
            background = kit.ripple(kit.rounded(palette.card, 20), 20)
            isClickable = true
            contentDescription = label
            setOnClickListener { OeaFaultShield.guard("quick-controls:action:$label") { onClick() } }
            addView(kit.iconBadge(icon, palette.accentSoft, palette.onAccentSoft, 42), LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(kit.text(label, 12f, palette.text, OeaType.medium).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                maxLines = 2
            })
        }
        listOf(
            action(R.drawable.oic_lock, "Lock") {
                when (OeaPower.lockScreen(context)) {
                    OeaPower.LockResult.LOCKED -> onClose()
                    OeaPower.LockResult.NEEDS_SETUP -> {
                        toast("OEA needs its Assistant or Device admin to lock")
                        launch(Intent(context, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power"))
                    }
                }
            },
            action(R.drawable.oic_restart_alt, "Power") {
                if (!OeaAssistant.openPowerMenu()) {
                    toast("OEA Assistant is off, so power menu cannot be opened")
                    OeaAssistant.openSetup(context)
                }
            },
            action(R.drawable.oic_volume_up, "Volume") { OeaPower.showVolumePanel(context) },
            action(R.drawable.oic_notifications, "Shade") {
                if (!OeaSystemActions.expandNotifications(context)) toast("OEA Assistant is off, so notification shade cannot be opened")
            }
).forEachIndexed { index, view ->
            grid.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        sheet.addView(grid)

        // ---- Quick Settings tiles (real state, real action where Android allows)
        OeaFaultShield.guard("quick-controls:qs-tiles") {
            kit.sectionTitle(sheet, "Connectivity")
            val qsGroup = kit.group(sheet)
            // Flashlight: one shared owner, so the label reflects the real LED —
            // including a change made from a Home gesture or the Camera screen.
            var torchOn = OeaTorch.isOn()
            val torchAvailable = OeaTorch.available(context)
            fun toggleTorch(): Boolean {
                if (!torchAvailable) return false
                torchOn = OeaTorch.toggle(context)
                return true
            }
            // Each row is independent; failures are contained.

            // ---- Bluetooth: a REAL toggle. Android 12+ grants it to ordinary
            // apps with the runtime permission BLUETOOTH_CONNECT (declared for
            // exactly this); Android 11- uses the legacy pair.
            runCatching {
                val adapter = bluetoothAdapter(context)
                if (adapter != null) {
                    val hasControl = bluetoothPermissionGranted(context)
                    val state = runCatching { adapter.isEnabled }.getOrDefault(false)
                    if (hasControl) {
                        kit.row(qsGroup, R.drawable.oic_tune, "Bluetooth", if (state) "On · tap to turn off" else "Off · tap to turn on") {
                            runCatching {
                                if (adapter.isEnabled) adapter.disable() else adapter.enable()
                            }.onFailure { toast("Android refused the Bluetooth change: ${it.message}") }
                        }
                    } else {
                        kit.row(qsGroup, R.drawable.oic_tune, "Bluetooth", "Allow Nearby devices to control it here") {
                            requestBluetoothPermission(context)
                        }
                    }
                } else {
                    kit.row(qsGroup, R.drawable.oic_tune, "Bluetooth", "Not available on this device")
                }
            }

            // ---- Wi-Fi: real state, and the in-place system panel for changes.
            // Android 10+ forbids third-party apps from switching Wi-Fi
            // themselves; the system panel is the surface Android provides.
            runCatching {
                val wifi = wifiState(context)
                kit.row(qsGroup, R.drawable.oic_wallpaper, "Wi-Fi", when (wifi) {
                    0 -> "Disabled · open the Wi-Fi panel"
                    1 -> "Enabled · no connection · open the panel"
                    3 -> "Enabled · open the panel"
                    else -> "Open the Wi-Fi panel"
                }) {
                    launch(wifiPanelIntent())
                }
            }

            // ---- Auto-rotate: a REAL toggle via the same WRITE_SETTINGS flow
            // the brightness slider already uses.
            runCatching {
                val canWrite = Settings.System.canWrite(context)
                val autoRotate = Settings.System.getInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
                ) == 1
                kit.row(qsGroup, R.drawable.oic_auto_awesome, "Auto-rotate", when {
                    canWrite -> if (autoRotate) "On · tap to turn off" else "Off · tap to turn on"
                    else -> "Allow Modify system settings to toggle here"
                }) {
                    if (canWrite) {
                        runCatching {
                            Settings.System.putInt(
                                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                                if (autoRotate) 0 else 1
                            )
                        }.onFailure { toast("Android refused the rotation change") }
                    } else {
                        launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
            }

            // ---- Airplane mode: state is readable; the internet panel is the
            // compliant change surface (apps cannot switch it).
            runCatching {
                val on = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                kit.row(qsGroup, R.drawable.oic_sports_esports, "Airplane mode", if (on) "On · open internet panel" else "Off · open internet panel") {
                    launch(internetPanelIntent())
                }
            }

            // ---- Location: apps cannot switch it; show the real mode and the
            // honest shortcut. (isLocationEnabled is API 28+; older devices
            // fall back to the location-mode setting.)
            runCatching {
                val lm = context.getSystemService(android.location.LocationManager::class.java)
                val on = if (Build.VERSION.SDK_INT >= 28) {
                    lm?.let { runCatching { it.isLocationEnabled }.getOrDefault(false) } ?: false
                } else {
                    @Suppress("DEPRECATION")
                    runCatching {
                        android.provider.Settings.Secure.getInt(
                            context.contentResolver,
                            android.provider.Settings.Secure.LOCATION_MODE,
                            android.provider.Settings.Secure.LOCATION_MODE_OFF
                        ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
                    }.getOrDefault(false)
                }
                kit.row(qsGroup, R.drawable.oic_home, "Location", if (on) "On · open location settings" else "Off · open location settings") {
                    launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }

            // ---- Battery saver: readable, not switchable by apps.
            runCatching {
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                val on = pm?.isPowerSaveMode == true
                kit.row(qsGroup, R.drawable.oic_battery_full, "Battery saver", if (on) "On · open battery saver" else "Off · open battery saver") {
                    launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                }
            }

            // ---- Flashlight: the one connectivity-adjacent LED OEA owns
            // outright.
            runCatching {
                var torchRowRef: LinearLayout? = null
                val torchRow = kit.row(qsGroup, R.drawable.oic_contrast, "Flashlight", if (torchOn) "On" else "Tap to toggle") {
                    if (!toggleTorch()) toast("Flashlight not available on this device")
                    // Update label - best effort
                    torchRowRef?.let { row ->
                        runCatching { row.getChildAt(2) as? android.widget.TextView }?.getOrNull()?.let { tv ->
                            tv.text = if (torchOn) "On" else "Off"
                        }
                    }
                }
                torchRowRef = torchRow
            }
        }

        // ---- brightness slider
        OeaFaultShield.guard("quick-controls:brightness") {
            kit.sectionTitle(sheet, "Brightness")
            val brightGroup = kit.group(sheet)
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val canWrite = if (Build.VERSION.SDK_INT >= 23) Settings.System.canWrite(context) else true
            var currentBright = 128
            try {
                currentBright = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            } catch (_: Throwable) {}
            val labelRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            labelRow.addView(kit.iconView(R.drawable.oic_contrast, palette.accent, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
            labelRow.addView(kit.text(if (canWrite) "System brightness" else "App brightness (allow Modify system settings for system-wide)", 14f, palette.text, OeaType.medium))
            box.addView(labelRow)
            box.addView(SeekBar(context).apply {
                max = 255
                progress = currentBright.coerceIn(0, 255)
                contentDescription = "Brightness"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        if (canWrite) {
                            runCatching {
                                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(10, 255))
                            }
                        } else {
                            // Window brightness fallback - affects only current window but gives feedback
                            val win = (context as? android.app.Activity)?.window
                            if (win != null) {
                                val lp = win.attributes
                                lp.screenBrightness = v / 255f
                                win.attributes = lp
                            }
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            if (!canWrite) {
                box.addView(kit.text("Tap to allow system brightness control", 12f, palette.accent, OeaType.medium).apply {
                    setPadding(0, dp(8), 0, 0)
                    setOnClickListener {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                })
            }
            brightGroup.addView(box)
        }

        // ---- volume sliders
        OeaFaultShield.guard("quick-controls:volume") {
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
                    AudioManager.RINGER_MODE_SILENT to "Silent"
)
                val current = modes.indexOfFirst { it.first == audio.ringerMode }.coerceAtLeast(0)
                sheet.addView(kit.segmented(modes.map { it.second }, current) { index ->
                    if (!OeaPower.setRinger(context, modes[index].first)) {
                        toast("Allow DND access to switch to silent")
                        runCatching { context.startActivity(OeaPower.policyAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                })
            }
        }

        // ---- DND + Buttons & screen
        OeaFaultShield.guard("quick-controls:dnd") {
            val dndGroup = kit.group(sheet).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12) }
            if (OeaPower.policyAccess(context)) {
                kit.switchRow(dndGroup, R.drawable.oic_do_not_disturb_on, "Do Not Disturb", "Priority-only interruptions", OeaPower.dndOn(context)) { on ->
                    if (!OeaPower.setDnd(context, on)) toast("Android refused the DND change")
                }
            } else {
                kit.row(dndGroup, R.drawable.oic_do_not_disturb_on, "Do Not Disturb", "Allow access to switch here") {
                    runCatching { context.startActivity(OeaPower.policyAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            kit.row(dndGroup, R.drawable.oic_tune, "Buttons & screen", "Wave to wake, lock options and gestures") {
                launch(Intent(context, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power"))
            }
        }

        // ---- Notification Centre (when access granted)
        OeaFaultShield.guard("quick-controls:notifications") {
            val hasAccess = try {
                android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?.contains(context.packageName) == true
            } catch (_: Throwable) { false }
            if (hasAccess) {
                kit.sectionTitle(sheet, "Notifications")
                val notifGroup = kit.group(sheet)
                val notifications = try { com.oea.launcher.notifications.OeaNotificationState.snapshot() } catch (_: Throwable) { emptyList() }
                if (notifications.isEmpty()) {
                    notifGroup.addView(kit.text("No active notifications", 13f, palette.textSecondary).apply {
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    })
                } else {
                    notifications.take(8).forEach { sbn ->
                        runCatching {
                            val title = sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: sbn.packageName.substringAfterLast('.')
                            val text = sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: ""
                            kit.row(notifGroup, R.drawable.oic_notifications, title, text, chevron = true) {
                                runCatching {
                                    sbn.notification.contentIntent?.send()
                                    onClose()
                                }.onFailure {
                                    toast("Could not open notification")
                                }
                            }
                        }
                    }
                    if (notifications.size > 8) {
                        kit.row(notifGroup, R.drawable.oic_notifications, "View all notifications", "${notifications.size} active") {
                            if (!OeaSystemActions.expandNotifications(context)) toast("Assistant off")
                        }
                    }
                }
            } else {
                kit.sectionTitle(sheet, "Notification Centre")
                val g = kit.group(sheet)
                kit.banner(g, R.drawable.oic_notifications, "Allow notification access", "OEA can show your notifications here and in Dynamic Island.", com.oea.launcher.ui.OeaKit.Tone.INFO, "Allow") {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }

        return sheet
    }

    fun fallbackSheet(context: Context, palette: OeaPalette, onClose: () -> Unit): View {
        val kit = OeaKit(context, palette)
        fun dp(v: Int) = (context.resources.displayMetrics.density * v + 0.5f).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = kit.roundedTop(palette.background, 30)
        }
        root.addView(kit.text("Quick controls", 18f, palette.text, OeaType.medium))
        root.addView(kit.text("A problem occurred loading full controls. Basic options are available.", 13f, palette.textSecondary).apply { setPadding(0, dp(6), 0, dp(12)) })
        val g = kit.group(root)
        kit.row(g, R.drawable.oic_lock, "Lock screen", null) {
            OeaPower.lockScreen(context)
            onClose()
        }
        kit.row(g, R.drawable.oic_volume_up, "Volume panel", null) {
            OeaPower.showVolumePanel(context)
        }
        kit.row(g, R.drawable.oic_tune, "Buttons & screen", null) {
            runCatching { context.startActivity(Intent(context, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            onClose()
        }
        root.addView(kit.tonalButton("Close") { onClose() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        return root
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
        onClose: () -> Unit
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

    // ---------------------------------------------------- connectivity helpers

    /** The real Bluetooth adapter, when the device has one. */
    private fun bluetoothAdapter(context: Context): android.bluetooth.BluetoothAdapter? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        } else {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        }
    }.getOrNull()

    /** Whether we may read AND change the adapter state right now. */
    fun bluetoothPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Asks for BLUETOOTH_CONNECT (Android 12+) from whatever activity is
     * hosting the sheet. The sheet is rebuilt on resume, so the real toggle
     * appears as soon as the user grants it.
     */
    private fun requestBluetoothPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val activity = context as? android.app.Activity
        if (activity == null) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        runCatching {
            activity.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 4781)
        }
    }

    /** Wi-Fi state from WifiManager: 0=disabled, 1=enabling, 2=enabling, 3=enabled. */
    private fun wifiState(context: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        context.getSystemService(android.net.wifi.WifiManager::class.java)?.wifiState ?: -1
    }.getOrDefault(-1)

    /**
     * The in-place system Wi-Fi panel (Android 10+): Android will not let a
     * third-party app switch Wi-Fi itself (WifiManager.setWifiEnabled is
     * system-only since Android 10), so this panel is the real change
     * surface. Older devices fall back to the Wi-Fi settings screen.
     */
    private fun wifiPanelIntent(): Intent = if (Build.VERSION.SDK_INT >= 29) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }

    /** The in-place internet panel (Wi-Fi + mobile data + airplane mode). */
    private fun internetPanelIntent(): Intent = if (Build.VERSION.SDK_INT >= 29) {
        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    } else {
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    }
}
