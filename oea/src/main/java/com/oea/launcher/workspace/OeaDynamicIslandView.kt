package com.oea.launcher.workspace

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
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
 * Which Home surface shows which app's live activity.
 *
 * - "card": everything in the capsule card under the status bar (default).
 * - "camera": everything in the expandable island around the front camera.
 * - "both": apps the user assigned to a style show there; unassigned apps,
 *   OEA calls and charging go to the chosen default surface. So Spotify can
 *   live in the camera island while WhatsApp uses the card.
 */
object OeaIslandRouting {
    fun surfaceEnabled(store: OeaDataStore, surface: OeaDynamicIslandView.Surface): Boolean = when (store.islandStyle()) {
        OeaDataStore.ISLAND_BOTH -> true
        OeaDataStore.ISLAND_CAMERA -> surface == OeaDynamicIslandView.Surface.CAMERA
        else -> surface == OeaDynamicIslandView.Surface.CARD
    }

    /** [packageName] null means a system activity (OEA call, charging). */
    fun surfaceFor(store: OeaDataStore, packageName: String?): OeaDynamicIslandView.Surface = when (store.islandStyle()) {
        OeaDataStore.ISLAND_CAMERA -> OeaDynamicIslandView.Surface.CAMERA
        OeaDataStore.ISLAND_BOTH -> when {
            packageName != null && packageName in store.islandCameraApps() -> OeaDynamicIslandView.Surface.CAMERA
            packageName != null && packageName in store.islandCardApps() -> OeaDynamicIslandView.Surface.CARD
            store.islandDefaultSurface() == OeaDataStore.ISLAND_CAMERA -> OeaDynamicIslandView.Surface.CAMERA
            else -> OeaDynamicIslandView.Surface.CARD
        }
        else -> OeaDynamicIslandView.Surface.CARD
    }
}

/**
 * An in-workspace live-activity surface. It deliberately uses no
 * TYPE_APPLICATION_OVERLAY window: it is a normal Home child, so it cannot
 * cover other apps, require overlay access, or outlive the launcher.
 *
 * Two styles share all of the activity logic below:
 * - [Surface.CARD]: the capsule card under the status bar.
 * - [Surface.CAMERA]: a black pill around the front camera that morphs into
 *   a large card when tapped, like the island phones show at the camera.
 *
 * Both expand *over* Home instead of pushing it down, so opening one never
 * shrinks the icon grid or resizes widgets.
 *
 * Media sessions use Android's standard MediaSession transport controls;
 * other ongoing notifications can expose their ordinary Notification.Action
 * PendingIntents. User-generated RemoteInput actions are intentionally omitted.
 */
class OeaDynamicIslandView(context: Context, private val surface: Surface = Surface.CARD) : FrameLayout(context) {
    enum class Surface { CARD, CAMERA }

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
    // Camera style: compact pill content under the camera.
    private val compactRow = LinearLayout(context)
    private val compactIcon = ImageView(context)
    private val compactFallback = TextView(context)
    private val compactTitle = TextView(context)
    private val trailingText = TextView(context)
    private val equalizer = EqualizerView(context)

    private var palette: OeaPalette = OeaTheme.resolve(context)
    private var current: Payload? = null
    private var expanded = false
    private var batteryPercent = -1
    private var charging = false
    private var receiverRegistered = false

    /** Status bar height and the top display cutout (camera), from window insets. */
    private var statusBarHeight = 0
    private var cutout: Rect? = null
    /** Card style: distance from the top of Home to the capsule (status bar + spacing). */
    private var cardTop = 0
    /** Card style: extra offset when the camera island sits above it (both styles on). */
    private var extraTop = 0
    private var lastCollapsedHeight = -1

    /** Fired when the user expands/collapses (the camera style tints the status bar). */
    var onExpandedChanged: ((Boolean) -> Unit)? = null
    /** Card style: fired when the space Home must reserve for the capsule changes. */
    var onReservedHeightChanged: (() -> Unit)? = null

    val isExpanded: Boolean get() = expanded && visibility == VISIBLE
    private val isCamera get() = surface == Surface.CAMERA

    private val callTicker = object : Runnable {
        override fun run() {
            val payload = current ?: return
            if (payload.kind == Kind.CALL && isCamera && !expanded) updateTrailing(payload)
            if (isAttachedToWindow && payload.kind == Kind.CALL) postDelayed(this, 1_000L)
        }
    }

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
            minimumWidth = if (isCamera) 0 else dp(190)
            elevation = dp(5).toFloat()
            clipChildren = false
            clipToOutline = isCamera
        }
        addView(card, LayoutParams(capsuleWidth(), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(4)
        })

        // Camera style compact pill: [icon] [short title] [live indicator].
        compactRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }
        val leading = FrameLayout(context).apply {
            addView(compactIcon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            addView(compactFallback.apply {
                gravity = Gravity.CENTER
                textSize = 14f
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        compactRow.addView(leading, LinearLayout.LayoutParams(dp(30), -1))
        compactRow.addView(compactTitle.apply {
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4); rightMargin = dp(6) })
        val trailing = FrameLayout(context).apply {
            addView(trailingText.apply {
                gravity = Gravity.CENTER
                textSize = 11.5f
                maxLines = 1
                includeFontPadding = false
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                fontFeatureSettings = "tnum"
            }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(equalizer, FrameLayout.LayoutParams(dp(18), dp(14), Gravity.CENTER))
        }
        compactRow.addView(trailing, LinearLayout.LayoutParams(dp(CAMERA_SIDE_DP), -1))
        card.addView(compactRow, LinearLayout.LayoutParams(-2, -1))

        header.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleExpanded() }
        }
        iconFrame.apply {
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
        card.setOnLongClickListener {
            // Long-press opens the app behind the activity, like the system island.
            val intent = current?.notification?.notification?.contentIntent ?: return@setOnLongClickListener false
            OeaPendingIntents.send(context, intent)
            true
        }

        applyPalette()
        render()
    }

    /**
     * Window geometry from Home's insets. The camera style centres its pill on
     * the top display cutout (the front camera); the card style sits below the
     * status bar.
     */
    fun setTopArea(statusBarHeight: Int, cutout: Rect?) {
        this.statusBarHeight = statusBarHeight
        this.cutout = cutout
        cardTop = statusBarHeight + dp(3)
        applyLayout()
        applyPalette()
    }

    /** Collapses an expanded surface when Home is touched elsewhere. */
    fun collapseIfOutside(rawX: Float, rawY: Float): Boolean {
        if (!isExpanded) return false
        val loc = IntArray(2)
        card.getLocationOnScreen(loc)
        val inside = rawX >= loc[0] && rawX <= loc[0] + card.width && rawY >= loc[1] && rawY <= loc[1] + card.height
        if (inside) return false
        setExpanded(false, animate = true)
        return true
    }

    fun collapse() {
        if (expanded) setExpanded(false, animate = false)
    }

    /**
     * The height Home keeps free for this surface while collapsed; both
     * styles expand over Home instead of pushing it down.
     */
    fun reservedHeight(): Int = when {
        visibility != VISIBLE -> 0
        isCamera -> dp(CAMERA_PILL_DP) + dp(8)
        else -> lastCollapsedHeight.coerceAtLeast(0)
    }

    /** Card style: sit below the camera island when both styles are showing. */
    fun setExtraTop(px: Int) {
        if (px == extraTop) return
        extraTop = px
        applyLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!isCamera && !expanded && card.visibility == VISIBLE) {
            val reserved = card.measuredHeight + dp(4)
            if (reserved != lastCollapsedHeight) {
                lastCollapsedHeight = reserved
                post { onReservedHeightChanged?.invoke() }
            }
        }
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
        removeCallbacks(callTicker)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
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

    private fun accepts(packageName: String?): Boolean = OeaIslandRouting.surfaceFor(store, packageName) == surface

    private fun buildPayload(): Payload? {
        if (!store.dynamicIslandEnabled()) return null
        if (!OeaIslandRouting.surfaceEnabled(store, surface)) return null
        if (accepts(null)) OeaCallState.current?.let(::callPayload)?.let { return it }
        val notification = OeaNotificationState.dynamicIslandNotification { accepts(it.packageName) }
        if (notification != null) notificationPayload(notification)?.let { return it }
        if (charging && batteryPercent >= 0 && accepts(null)) {
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
            val wasVisible = visibility == VISIBLE
            val wasExpanded = expanded
            current = null
            expanded = false
            visibility = GONE
            removeCallbacks(callTicker)
            if (wasExpanded) onExpandedChanged?.invoke(false)
            if (wasVisible) onReservedHeightChanged?.invoke()
            return
        }
        if (current?.key != payload.key && expanded) {
            expanded = false
            onExpandedChanged?.invoke(false)
        }
        val becameVisible = visibility != VISIBLE
        current = payload
        visibility = VISIBLE
        if (becameVisible) onReservedHeightChanged?.invoke()
        updateTrailing(payload)
        removeCallbacks(callTicker)
        if (payload.kind == Kind.CALL) postDelayed(callTicker, 1_000L)

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
        expandIcon.rotation = if (expanded) 180f else 0f
        card.contentDescription = contentDescription
        card.setOnClickListener { toggleExpanded() }
        applyLayout()
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
        val glyph = when (payload.kind) {
            Kind.CALL -> "☎"
            Kind.MEDIA -> "♫"
            Kind.NOTIFICATION -> "•"
            Kind.CHARGING -> "⚡"
        }
        if (icon != null) {
            appIcon.setImageDrawable(icon.mutate().apply { setTint(palette.accent) })
            appIcon.visibility = VISIBLE
            fallbackIcon.visibility = GONE
            compactIcon.setImageDrawable(icon.constantState?.newDrawable()?.mutate()?.apply { setTint(palette.accent) } ?: icon)
            compactIcon.visibility = VISIBLE
            compactFallback.visibility = GONE
            return
        }
        appIcon.visibility = GONE
        fallbackIcon.visibility = VISIBLE
        fallbackIcon.text = glyph
        compactIcon.visibility = GONE
        compactFallback.visibility = VISIBLE
        compactFallback.text = glyph
    }

    /** Camera style trailing indicator: call timer, playing bars, battery level or a live dot. */
    private fun updateTrailing(payload: Payload) {
        if (!isCamera) return
        compactTitle.text = when (payload.kind) {
            Kind.CALL -> payload.call?.let { call ->
                val number = com.oea.launcher.phone.OeaCallUi.displayNumber(call)
                com.oea.launcher.phone.OeaCallUi.contactName(context, number) ?: number
            } ?: payload.title
            else -> payload.title
        }
        val playing = payload.kind == Kind.MEDIA && runCatching {
            payload.notification?.notification?.let(::mediaController)?.playbackState?.state == PlaybackState.STATE_PLAYING
        }.getOrDefault(false)
        equalizer.visibility = if (playing) VISIBLE else GONE
        equalizer.color = palette.accent
        equalizer.playing = playing
        trailingText.visibility = if (playing) GONE else VISIBLE
        trailingText.setTextColor(if (payload.kind == Kind.CALL) Color.rgb(98, 222, 140) else Color.WHITE)
        trailingText.text = when (payload.kind) {
            Kind.CALL -> callTrailing(payload.call)
            Kind.CHARGING -> if (batteryPercent >= 0) "$batteryPercent%" else "⚡"
            Kind.MEDIA -> "❚❚"
            Kind.NOTIFICATION -> "●"
        }
        if (payload.kind == Kind.NOTIFICATION) trailingText.setTextColor(palette.accent)
    }

    private fun callTrailing(call: Call?): String {
        val state = runCatching { call?.state }.getOrNull() ?: return "Call"
        return when (state) {
            Call.STATE_RINGING -> "Ring"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "…"
            Call.STATE_HOLDING -> "Hold"
            Call.STATE_ACTIVE -> {
                val since = runCatching { call?.details?.connectTimeMillis }.getOrNull()?.takeIf { it > 0L } ?: return "On"
                val seconds = ((System.currentTimeMillis() - since) / 1000L).coerceAtLeast(0L)
                String.format(java.util.Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
            }
            else -> "Call"
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
                ((call.details?.callCapabilities ?: 0) and Call.Details.CAPABILITY_HOLD) != 0
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
        setExpanded(!expanded, animate = true)
    }

    private fun setExpanded(value: Boolean, animate: Boolean) {
        if (current == null && value) return
        if (expanded == value) return
        if (animate && isAttachedToWindow && isLaidOut) {
            TransitionManager.beginDelayedTransition(this, TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade())
                duration = if (isCamera) 340L else 260L
                interpolator = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
            })
        }
        expanded = value
        expandIcon.rotation = if (expanded) 180f else 0f
        contentDescription = buildString {
            append(current?.let { labelFor(it.kind) }.orEmpty())
            append(": ")
            append(current?.title.orEmpty())
            append(if (expanded) ". Expanded" else ". Collapsed")
        }
        card.contentDescription = contentDescription
        current?.let(::updateTrailing)
        applyLayout()
        applyPalette()
        onExpandedChanged?.invoke(expanded)
    }

    /**
     * Positions the surface for its style and state. Both styles grow over
     * Home: the card keeps its collapsed slot reserved, the camera pill morphs
     * from the cutout into a wide card.
     */
    private fun applyLayout() {
        val params = card.layoutParams as? LayoutParams ?: return
        if (!isCamera) {
            compactRow.visibility = GONE
            header.visibility = VISIBLE
            details.visibility = if (expanded) VISIBLE else GONE
            params.width = capsuleWidth()
            params.height = LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.leftMargin = 0
            params.topMargin = cardTop + extraTop + dp(4)
            card.setPadding(dp(13), dp(9), dp(13), dp(10))
            card.layoutParams = params
            return
        }
        // The status-bar strip belongs to System UI's window, which takes every
        // touch there; the island therefore hangs just below it, centred under
        // the front camera, where it can be tapped.
        val screenWidth = resources.displayMetrics.widthPixels
        val pillHeight = dp(CAMERA_PILL_DP)
        val pillTop = statusBarHeight + dp(3)
        val centerX = cutout?.centerX() ?: (screenWidth / 2)
        if (expanded) {
            compactRow.visibility = GONE
            header.visibility = VISIBLE
            details.visibility = VISIBLE
            val width = minOf(screenWidth - dp(16), dp(440))
            params.width = width
            params.height = LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = (centerX - width / 2).coerceIn(dp(8), (screenWidth - width - dp(8)).coerceAtLeast(dp(8)))
            params.topMargin = pillTop
            card.setPadding(dp(16), dp(14), dp(16), dp(14))
        } else {
            compactRow.visibility = VISIBLE
            header.visibility = GONE
            details.visibility = GONE
            compactTitle.maxWidth = (screenWidth * 0.48f).toInt()
            card.setPadding(dp(6), 0, dp(4), 0)
            card.measure(
                MeasureSpec.makeMeasureSpec(screenWidth - dp(32), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(pillHeight, MeasureSpec.EXACTLY),
            )
            val width = card.measuredWidth.coerceIn(dp(112), (screenWidth - dp(32)).coerceAtLeast(dp(112)))
            params.width = width
            params.height = pillHeight
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = (centerX - width / 2).coerceIn(dp(8), (screenWidth - width - dp(8)).coerceAtLeast(dp(8)))
            params.topMargin = pillTop
        }
        card.layoutParams = params
    }

    private fun applyPalette() {
        if (isCamera) {
            // Pure black so the pill blends with the camera hole, like a hardware island.
            val pillHeight = (card.layoutParams as? LayoutParams)?.height?.takeIf { it > 0 } ?: dp(30)
            card.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = if (expanded) dp(30).toFloat() else pillHeight / 2f
                if (expanded) setStroke(maxOf(1, dp(1) / 2), 0x26FFFFFF)
            }
            card.elevation = if (expanded) dp(12).toFloat() else 0f
        } else {
            val base = if (palette.dark) Color.rgb(10, 12, 17) else Color.rgb(20, 22, 28)
            card.background = GradientDrawable().apply {
                setColor(OeaTheme.blend(base, palette.accent, 0.045f))
                cornerRadius = dp(28).toFloat()
                setStroke(maxOf(1, dp(1)), OeaTheme.alpha(palette.accent, 0x74))
            }
        }
        compactFallback.setTextColor(palette.accent)
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
        /** Width of the camera pill's indicator side. */
        const val CAMERA_SIDE_DP = 40
        /** Height of the collapsed camera pill. */
        const val CAMERA_PILL_DP = 32
    }

    /** Three animated bars shown in the camera pill while media is playing. */
    private class EqualizerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = RectF()
        private var phase = 0f
        private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }
        var color = Color.WHITE
            set(value) { field = value; invalidate() }
        var playing = false
            set(value) {
                if (field == value) return
                field = value
                syncAnimation()
            }

        private fun syncAnimation() {
            if (playing && isAttachedToWindow && visibility == VISIBLE) {
                if (!animator.isStarted) animator.start()
            } else {
                animator.cancel()
            }
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            syncAnimation()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            syncAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = color
            val w = width.toFloat()
            val h = height.toFloat()
            val gap = w * 0.16f
            val barWidth = (w - gap * 2f) / 3f
            for (i in 0 until 3) {
                val wave = if (playing) {
                    val t = (phase + i * 0.27f) % 1f
                    0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(t * Math.PI)).toFloat()
                } else 0.35f
                val barHeight = h * wave
                val left = i * (barWidth + gap)
                bar.set(left, h - barHeight, left + barWidth, h)
                canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint)
            }
        }
    }
}
