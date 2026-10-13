package com.oea.launcher.workspace

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.telecom.Call
import android.telecom.VideoProfile
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.notifications.OeaNotificationState
import com.oea.launcher.phone.OeaCallState
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme

/**
 * An in-workspace live-activity capsule. It deliberately uses no
 * TYPE_APPLICATION_OVERLAY window: the capsule is a normal Home child, so it
 * cannot cover other apps, require overlay access, or outlive the launcher.
 *
 * Media sessions use Android's standard MediaSession transport controls;
 * other ongoing notifications can expose their ordinary Notification.Action
 * PendingIntents. User-generated RemoteInput actions are intentionally omitted.
 */
class OeaDynamicIslandView(context: Context) : FrameLayout(context) {
    private enum class Kind { CALL, MEDIA, NOTIFICATION, CHARGING }

    private data class Payload(
        val key: String,
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val notification: StatusBarNotification? = null,
        val icon: Drawable? = null,
        val call: Call? = null,
    )

    private val density = resources.displayMetrics.density
    private val store = OeaDataStore.get(context)
    private val card = LinearLayout(context)
    private val header = LinearLayout(context)
    private val iconFrame = FrameLayout(context)
    private val appIcon = ImageView(context)
    private val fallbackIcon = TextView(context)
    private val labels = LinearLayout(context)
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val expandIcon = ImageView(context)
    private val details = LinearLayout(context)
    private val detailText = TextView(context)
    private val actions = LinearLayout(context)

    private var palette: OeaPalette = OeaTheme.resolve(context)
    private var current: Payload? = null
    private var expanded = false
    private var batteryPercent = -1
    private var charging = false
    private var receiverRegistered = false

    private val notificationObserver: () -> Unit = { render() }
    private val callObserver: () -> Unit = { render() }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBattery(intent)
            render()
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false

        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(9), dp(13), dp(10))
            minimumWidth = dp(190)
            elevation = dp(5).toFloat()
            clipChildren = false
        }
        addView(card, LayoutParams(capsuleWidth(), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(4)
        })

        header.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleExpanded() }
        }
        iconFrame.apply {
            gravity = Gravity.CENTER
            addView(appIcon, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
            addView(fallbackIcon, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
        }
        header.addView(iconFrame, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })

        labels.orientation = LinearLayout.VERTICAL
        titleView.apply {
            textSize = 13.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        subtitleView.apply {
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        labels.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        labels.addView(subtitleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(expandIcon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(6) })
        card.addView(header, LinearLayout.LayoutParams(-1, -2))

        details.apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            setPadding(dp(44), dp(8), 0, 0)
        }
        detailText.apply {
            textSize = 12f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        actions.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        details.addView(detailText, LinearLayout.LayoutParams(-1, -2))
        details.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        card.addView(details, LinearLayout.LayoutParams(-1, -2))

        applyPalette()
        render()
    }

    fun setPalette(value: OeaPalette) {
        palette = value
        applyPalette()
        render()
    }

    /** Re-checks persisted settings and notification/battery state when Home resumes. */
    fun refresh() = render()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        OeaNotificationState.addListener(notificationObserver)
        OeaCallState.addListener(callObserver)
        registerBatteryReceiver()
        render()
    }

    override fun onDetachedFromWindow() {
        OeaNotificationState.removeListener(notificationObserver)
        OeaCallState.removeListener(callObserver)
        unregisterBatteryReceiver()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val params = card.layoutParams as? LayoutParams ?: return
        val width = capsuleWidth()
        if (params.width != width) {
            params.width = width
            card.layoutParams = params
        }
    }

    private fun registerBatteryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        var registrationSucceeded = false
        val sticky = runCatching {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(batteryReceiver, filter)
            }
            registrationSucceeded = true
            result
        }.getOrNull()
        // A successful registration can legitimately return a null sticky result.
        receiverRegistered = registrationSucceeded
        sticky?.let(::updateBattery)
    }

    private fun unregisterBatteryReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    private fun updateBattery(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> charging = true
            Intent.ACTION_POWER_DISCONNECTED -> charging = false
            Intent.ACTION_BATTERY_CHANGED, null -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                batteryPercent = if (level >= 0) (level * 100 / scale).coerceIn(0, 100) else -1
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
    }

    private fun buildPayload(): Payload? {
        if (!store.dynamicIslandEnabled()) return null
        OeaCallState.current?.let(::callPayload)?.let { return it }
        val notification = OeaNotificationState.dynamicIslandNotification()
        if (notification != null) notificationPayload(notification)?.let { return it }
        if (charging && batteryPercent >= 0) {
            val full = batteryPercent >= 100
            return Payload(
                key = "charging",
                kind = Kind.CHARGING,
                title = if (full) "Battery full" else "Charging",
                subtitle = "$batteryPercent% battery",
            )
        }
        return null
    }

    private fun callPayload(call: Call): Payload? {
        val state = runCatching { call.state }.getOrNull() ?: return null
        if (state == Call.STATE_DISCONNECTED) return null
        val incoming = state == Call.STATE_RINGING
        val subtitle = when (state) {
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Connecting"
            Call.STATE_ACTIVE -> "Call in progress"
            Call.STATE_HOLDING -> "Call on hold"
            Call.STATE_DISCONNECTING -> "Call ending"
            else -> "Phone call"
        }
        return Payload(
            key = "oea-call:${System.identityHashCode(call)}",
            kind = Kind.CALL,
            title = if (incoming) "Incoming call" else "Phone call",
            subtitle = subtitle,
            call = call,
        )
    }

    private fun notificationPayload(sbn: StatusBarNotification): Payload? = runCatching {
        val notification = sbn.notification
        val extras = notification.extras
        val media = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        val call = notification.category == Notification.CATEGORY_CALL
        val appName = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))
        val controller = if (media) mediaController(notification) else null
        val metadataTitle = runCatching {
            controller?.metadata?.description?.title?.toString()
                ?: controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        }.getOrNull()
        val metadataArtist = runCatching {
            controller?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: controller?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        }.getOrNull()
        val title = listOf(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            metadataTitle,
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty().ifBlank { appName }
        val subtitle = listOf(
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            metadataArtist,
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty().takeIf { it != title }.orEmpty()
        val icon = runCatching { notification.smallIcon?.loadDrawable(context)?.mutate() }.getOrNull()
        Payload(
            key = sbn.key,
            kind = when {
                call -> Kind.CALL
                media -> Kind.MEDIA
                else -> Kind.NOTIFICATION
            },
            title = title,
            subtitle = subtitle,
            notification = sbn,
            icon = icon,
        )
    }.getOrNull()

    private fun render() {
        try {
            renderContent()
        } catch (error: Exception) {
            Log.w(TAG, "Could not render Home live activity", error)
            current = null
            expanded = false
            visibility = GONE
        }
    }

    private fun renderContent() {
        val payload = runCatching { buildPayload() }.getOrNull()
        if (payload == null) {
            current = null
            expanded = false
            visibility = GONE
            return
        }
        if (current?.key != payload.key) expanded = false
        current = payload
        visibility = VISIBLE

        titleView.text = payload.title
        subtitleView.text = payload.subtitle.ifBlank { labelFor(payload.kind) }
        subtitleView.visibility = VISIBLE
        contentDescription = buildString {
            append(labelFor(payload.kind))
            append(": ")
            append(payload.title)
            if (payload.subtitle.isNotBlank()) append(". ${payload.subtitle}")
            append(if (expanded) ". Expanded" else ". Collapsed")
        }
        setLeadingIcon(payload)
        detailText.text = when (payload.kind) {
            Kind.CALL -> payload.subtitle.ifBlank { "Call controls from this notification" }
            Kind.MEDIA -> payload.subtitle.ifBlank { "Media playback controls" }
            Kind.CHARGING -> payload.subtitle
            Kind.NOTIFICATION -> payload.subtitle.ifBlank { labelFor(Kind.NOTIFICATION) }
        }
        rebuildActions(payload)
        details.visibility = if (expanded) VISIBLE else GONE
        expandIcon.rotation = if (expanded) 180f else 0f
        card.contentDescription = contentDescription
        card.setOnClickListener { toggleExpanded() }
        applyPalette()
    }

    private fun labelFor(kind: Kind): String = when (kind) {
        Kind.CALL -> "Call"
        Kind.MEDIA -> "Now playing"
        Kind.NOTIFICATION -> "Live notification"
        Kind.CHARGING -> "Battery"
    }

    private fun setLeadingIcon(payload: Payload) {
        val icon = payload.icon
        if (icon != null) {
            appIcon.setImageDrawable(icon.mutate().apply { setTint(palette.accent) })
            appIcon.visibility = VISIBLE
            fallbackIcon.visibility = GONE
            return
        }
        appIcon.visibility = GONE
        fallbackIcon.visibility = VISIBLE
        fallbackIcon.text = when (payload.kind) {
            Kind.CALL -> "☎"
            Kind.MEDIA -> "♫"
            Kind.NOTIFICATION -> "•"
            Kind.CHARGING -> "⚡"
        }
    }

    private fun rebuildActions(payload: Payload) {
        actions.removeAllViews()
        val notification = payload.notification?.notification
        when {
            payload.call != null -> addCallControls(payload.call)
            payload.kind == Kind.MEDIA && notification != null -> {
                val controller = mediaController(notification)
                val before = actions.childCount
                if (controller != null) addMediaControls(controller)
                if (actions.childCount == before) addNotificationActions(notification)
            }
            notification != null -> addNotificationActions(notification)
        }

        val contentIntent = payload.notification?.notification?.contentIntent
        if (contentIntent != null) {
            addTextAction("Open") { OeaPendingIntents.send(context, contentIntent) }
        }

        if (actions.childCount == 0) {
            actions.visibility = GONE
        } else {
            actions.visibility = VISIBLE
        }
    }

    private fun addCallControls(call: Call) {
        val state = runCatching { call.state }.getOrNull() ?: return
        if (state == Call.STATE_RINGING) {
            addTextAction("Answer") {
                runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
                postDelayed({ render() }, 250L)
            }
            addTextAction("Decline") {
                runCatching { call.reject(false, null) }
                postDelayed({ render() }, 250L)
            }
            return
        }
        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) return
        addTextAction("End call") {
            runCatching { call.disconnect() }
            postDelayed({ render() }, 250L)
        }
        if (state == Call.STATE_HOLDING) {
            addTextAction("Resume") {
                runCatching { call.unhold() }
                postDelayed({ render() }, 250L)
            }
        } else if (state == Call.STATE_ACTIVE) {
            val canHold = runCatching {
                ((call.details?.capabilities ?: 0) and Call.Details.CAPABILITY_HOLD) != 0
            }.getOrDefault(false)
            if (canHold) {
                addTextAction("Hold") {
                    runCatching { call.hold() }
                    postDelayed({ render() }, 250L)
                }
            }
        }
    }

    private fun addMediaControls(controller: MediaController) {
        val playback = runCatching { controller.playbackState }.getOrNull() ?: return
        val flags = playback.actions
        val transport = controller.transportControls
        if ((flags and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L) {
            addIconAction(R.drawable.oic_skip_next, "Previous track", rotation = 180f) {
                runCatching { transport.skipToPrevious() }
                postDelayed({ render() }, 350L)
            }
        }
        val canPause = (flags and PlaybackState.ACTION_PAUSE) != 0L || (flags and PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val canPlay = (flags and PlaybackState.ACTION_PLAY) != 0L || (flags and PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val playing = playback.state == PlaybackState.STATE_PLAYING || playback.state == PlaybackState.STATE_BUFFERING
        val canTogglePlayback = if (playing) canPause else canPlay
        if (canTogglePlayback) {
            addIconAction(
                if (playing) R.drawable.oic_pause else R.drawable.oic_play_arrow,
                if (playing) "Pause" else "Play",
            ) {
                runCatching { if (playing) transport.pause() else transport.play() }
                postDelayed({ render() }, 350L)
            }
        }
        if ((flags and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L) {
            addIconAction(R.drawable.oic_skip_next, "Next track") {
                runCatching { transport.skipToNext() }
                postDelayed({ render() }, 350L)
            }
        }
    }

    private fun addNotificationActions(notification: Notification) {
        val available = runCatching {
            notification.actions.orEmpty().filter { action ->
                action.actionIntent != null && action.remoteInputs.isNullOrEmpty()
            }.take(3)
        }.getOrDefault(emptyList())
        available.forEach { action ->
            val title = action.title?.toString()?.trim().orEmpty()
            if (title.isNotBlank()) {
                addTextAction(title.take(16)) {
                    action.actionIntent?.let { OeaPendingIntents.send(context, it) }
                    postDelayed({ render() }, 350L)
                }
            }
        }
    }

    private fun addIconAction(icon: Int, description: String, rotation: Float = 0f, onClick: () -> Unit) {
        val button = FrameLayout(context).apply {
            background = roundedActionBackground()
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        button.addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(icon)?.mutate()?.apply { setTint(Color.WHITE) })
            this.rotation = rotation
        }, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
        actions.addView(button, LinearLayout.LayoutParams(dp(40), dp(36)).apply { rightMargin = dp(7) })
    }

    private fun addTextAction(label: String, onClick: () -> Unit) {
        val button = TextView(context).apply {
            text = label
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = roundedActionBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        actions.addView(button, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(7) })
    }

    private fun mediaController(notification: Notification): MediaController? = runCatching {
        val extras = notification.extras
        val token = if (Build.VERSION.SDK_INT >= 33) {
            extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            (extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token)
        }
        token?.let { MediaController(context, it) }
    }.getOrNull()

    private fun toggleExpanded() {
        if (current == null) return
        expanded = !expanded
        details.visibility = if (expanded) VISIBLE else GONE
        expandIcon.rotation = if (expanded) 180f else 0f
        contentDescription = buildString {
            append(current?.let { labelFor(it.kind) }.orEmpty())
            append(": ")
            append(current?.title.orEmpty())
            append(if (expanded) ". Expanded" else ". Collapsed")
        }
        card.contentDescription = contentDescription
    }

    private fun applyPalette() {
        val base = if (palette.dark) Color.rgb(10, 12, 17) else Color.rgb(20, 22, 28)
        card.background = GradientDrawable().apply {
            setColor(OeaTheme.blend(base, palette.accent, 0.045f))
            cornerRadius = dp(28).toFloat()
            setStroke(maxOf(1, dp(1)), OeaTheme.alpha(palette.accent, 0x74))
        }
        iconFrame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(OeaTheme.alpha(palette.accent, 0x2C))
        }
        titleView.setTextColor(Color.WHITE)
        subtitleView.setTextColor(0xFFC5CAD5.toInt())
        fallbackIcon.setTextColor(palette.accent)
        fallbackIcon.textSize = 17f
        fallbackIcon.gravity = Gravity.CENTER
        expandIcon.setImageDrawable(context.getDrawable(R.drawable.oic_expand_more)?.mutate()?.apply { setTint(0xFFCFD4DE.toInt()) })
        detailText.setTextColor(0xFFE2E6EE.toInt())
    }

    private fun capsuleWidth(): Int = minOf(
        (resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(dp(190)),
        dp(420),
    )

    private fun roundedActionBackground(): Drawable = GradientDrawable().apply {
        setColor(0x22FFFFFF)
        cornerRadius = dp(13).toFloat()
        setStroke(maxOf(1, dp(1) / 2), 0x44FFFFFF)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        const val TAG = "OeaLiveActivity"
    }
}
