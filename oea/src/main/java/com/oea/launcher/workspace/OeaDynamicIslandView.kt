package com.oea.launcher.workspace

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
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
import android.os.SystemClock
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
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.games.OeaGameLiveState
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
class OeaDynamicIslandView(
    context: Context,
    private val surface: Surface = Surface.CARD,
    /** Inside an OEA game: only calls, game events and (optionally) music are shown. */
    private val inGame: Boolean = false,
    /** Hosted in its own small application-overlay window for opt-in system-wide display. */
    private val overlayWindow: Boolean = false,
) : FrameLayout(context) {
    enum class Surface { CARD, CAMERA }

    private enum class Kind { CALL, MEDIA, NOTIFICATION, CHARGING, GAME, PRIVACY }

    private data class Payload(
        val key: String,
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val notification: StatusBarNotification? = null,
        val icon: Drawable? = null,
        val call: Call? = null,
        /** OEA game live activity (running game) or a transient game event. */
        val game: OeaGameLiveState.Entry? = null,
        val gameEvent: OeaGameLiveState.Event? = null,
        /** Album art for media sessions. */
        val art: Bitmap? = null,
        /** An incoming call that is still ringing: stays expanded until answered or declined. */
        val ringing: Boolean = false,
    ) {
        val gameGlyph: String get() = game?.activity?.glyph?.ifBlank { null } ?: gameEvent?.event?.glyph?.ifBlank { null } ?: game?.game?.glyph ?: gameEvent?.game?.glyph ?: "🎮"
    }

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
    // Camera and microphone in-use dots (compact and expanded) and the media progress row.
    private val compactPrivacy = PrivacyDotsView(context)
    private val headerPrivacy = PrivacyDotsView(context)
    private val privacyText = TextView(context)
    private val progressRow = LinearLayout(context)
    private val elapsedText = TextView(context)
    private val progressBar = MediaProgressView(context)
    private val durationText = TextView(context)
    private val labelCache = HashMap<String, String>()

    /** Auto-expanded "peek" state: when it ends, and which activity the user closed by hand. */
    private var peekUntil = 0L
    private var peekKey: String? = null
    private var userClosedSignature: String? = null
    private val peekCollapse = Runnable {
        if (expanded && current?.ringing != true && SystemClock.uptimeMillis() >= peekUntil - 50L) setExpanded(false, animate = true)
    }
    private val progressTicker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !expanded || current?.kind != Kind.MEDIA) return
            updateProgress()
            postDelayed(this, 1_000L)
        }
    }

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
    private var lastCameraReservedHeight = -1
    private var lastOverlayGeometryKey: String? = null
    private var swipeStartRawX = 0f
    private var swipeStartRawY = 0f
    private var notificationSwipeTarget: StatusBarNotification? = null
    private var notificationSwipeIntercepted = false

    /** Fired when the user expands/collapses (the camera style tints the status bar). */
    var onExpandedChanged: ((Boolean) -> Unit)? = null
    /** Card style: fired when the space Home must reserve for the capsule changes. */
    var onReservedHeightChanged: (() -> Unit)? = null
    /** Geometry callback used only by the optional system-wide overlay service. */
    var onOverlayGeometryChanged: ((width: Int, height: Int, x: Int, y: Int, visible: Boolean) -> Unit)? = null

    val isExpanded: Boolean get() = expanded && visibility == VISIBLE
    private val isCamera get() = surface == Surface.CAMERA

    private val callTicker = object : Runnable {
        override fun run() {
            val payload = current ?: return
            if (payload.kind == Kind.CALL && isCamera && !expanded) updateTrailing(payload)
            if (isAttachedToWindow && payload.kind == Kind.CALL) postDelayed(this, 1_000L)
        }
    }

    private val privacyObserver: () -> Unit = { render() }
    private val notificationObserver: () -> Unit = { render() }
    private val callObserver: () -> Unit = { render() }
    private val gameObserver: () -> Unit = { render() }
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
        compactRow.addView(compactPrivacy, LinearLayout.LayoutParams(dp(18), -1).apply { rightMargin = dp(4) })
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
        header.addView(headerPrivacy, LinearLayout.LayoutParams(dp(18), dp(22)).apply { leftMargin = dp(6) })
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
        privacyText.apply {
            textSize = 11.5f
            visibility = GONE
            compoundDrawablePadding = dp(6)
        }
        details.addView(privacyText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        progressRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }
        listOf(elapsedText, durationText).forEach {
            it.textSize = 10.5f
            it.fontFeatureSettings = "tnum"
            it.setTextColor(0xFFC5CAD5.toInt())
        }
        progressRow.addView(elapsedText, LinearLayout.LayoutParams(-2, -2))
        progressRow.addView(progressBar, LinearLayout.LayoutParams(0, dp(4), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        progressRow.addView(durationText, LinearLayout.LayoutParams(-2, -2))
        details.addView(progressRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        details.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        card.addView(details, LinearLayout.LayoutParams(-1, -2))
        card.setOnLongClickListener {
            // Long-press opens the app behind the activity, like the system island.
            if (current?.kind == Kind.GAME && !inGame) { OeaGameLiveState.resume(context); return@setOnLongClickListener true }
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
        isCamera -> {
            val cameraTop = maxOf(statusBarHeight, cutout?.bottom ?: 0) + dp(3) + dp(store.islandCameraOffsetY())
            (cameraTop + dp(CAMERA_PILL_DP) + dp(8) - statusBarHeight).coerceAtLeast(0)
        }
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
        OeaGameLiveState.addListener(gameObserver)
        OeaPrivacyMonitor.addListener(context, privacyObserver)
        if (!inGame) registerBatteryReceiver()
        render()
        notifyOverlayGeometry()
    }

    override fun onDetachedFromWindow() {
        OeaNotificationState.removeListener(notificationObserver)
        OeaCallState.removeListener(callObserver)
        OeaGameLiveState.removeListener(gameObserver)
        OeaPrivacyMonitor.removeListener(privacyObserver)
        unregisterBatteryReceiver()
        removeCallbacks(callTicker)
        removeCallbacks(peekCollapse)
        removeCallbacks(progressTicker)
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

    private fun accepts(packageName: String?): Boolean {
        if (packageName != null && !store.islandAllApps() && packageName !in store.islandSelectedApps()) return false
        return OeaIslandRouting.surfaceFor(store, packageName) == surface
    }

    private fun buildPayload(): Payload? {
        // Once the opt-in overlay service has attached both windows, it becomes
        // the sole owner; keep Home visible if startup fails or permission is lost.
        if (store.islandOnOtherApps() && OeaDynamicIslandOverlayService.overlayActive && !overlayWindow) return null
        if (!store.dynamicIslandEnabled()) return null
        if (!OeaIslandRouting.surfaceEnabled(store, surface)) return null
        if (accepts(null)) OeaCallState.current?.let(::callPayload)?.let { return it }
        // OEA games: transient events on the surfaces the game resolved, then the running game's live activity.
        OeaGameLiveState.eventFor(surface)?.let { e ->
            return Payload(key = "game-event:${e.until}", kind = Kind.GAME, title = e.event.text, subtitle = e.game.title, gameEvent = e)
        }
        if (inGame) {
            if (!com.oea.engine.OeaEngine.settings.islandMediaInGames) return null
            val media = OeaNotificationState.dynamicIslandNotification { sbn ->
                accepts(sbn.packageName) && (sbn.notification.category == Notification.CATEGORY_TRANSPORT ||
                    sbn.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true)
            }
            return media?.let { notificationPayload(it) }
        }
        OeaGameLiveState.activityFor(surface)?.let { g ->
            return Payload(key = "game:${g.game.id}", kind = Kind.GAME, title = g.activity.title, subtitle = g.activity.subtitle, game = g)
        }
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
        // Android 12+ already shows its own camera/microphone indicator, so the
        // island only adds dots to an activity it is showing anyway. Older
        // Android versions have no indicator; there the island shows one.
        if (Build.VERSION.SDK_INT < 31 && store.islandPrivacyDots() && accepts(null) &&
            (OeaPrivacyMonitor.cameraInUse || OeaPrivacyMonitor.micInUse)
        ) {
            val both = OeaPrivacyMonitor.cameraInUse && OeaPrivacyMonitor.micInUse
            return Payload(
                key = "privacy",
                kind = Kind.PRIVACY,
                title = when {
                    both -> "Camera and microphone in use"
                    OeaPrivacyMonitor.cameraInUse -> "Camera in use"
                    else -> "Microphone in use"
                },
                subtitle = "An app is using it right now",
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
            ringing = incoming,
        )
    }

    private fun notificationPayload(sbn: StatusBarNotification): Payload? = runCatching {
        val notification = sbn.notification
        val extras = notification.extras
        val media = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        val call = notification.category == Notification.CATEGORY_CALL
        val appName = labelCache.getOrPut(sbn.packageName) {
            runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName.substringAfterLast('.'))
        }
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
        val art = if (media) runCatching {
            controller?.metadata?.let { meta ->
                meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
        }.getOrNull() else null
        // A ringing call notification (another dialer) carries a full-screen intent.
        val ringing = call && notification.fullScreenIntent != null
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
            art = art,
            ringing = ringing,
        )
    }.getOrNull()

    private fun render() {
        try {
            renderContent()
        } catch (error: Exception) {
            Log.w(TAG, "Could not render live activity", error)
            val wasVisible = visibility == VISIBLE
            val wasExpanded = expanded
            current = null
            expanded = false
            visibility = GONE
            removeCallbacks(callTicker)
            if (wasExpanded) onExpandedChanged?.invoke(false)
            if (wasVisible) onReservedHeightChanged?.invoke()
            notifyOverlayGeometry()
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
            notifyOverlayGeometry()
            return
        }
        val previous = current
        if (previous?.key != payload.key) {
            notificationSwipeTarget = null
            notificationSwipeIntercepted = false
            card.animate().cancel()
            card.translationX = 0f
            card.alpha = 1f
            if (expanded) {
                expanded = false
                onExpandedChanged?.invoke(false)
            }
        }
        val becameVisible = visibility != VISIBLE
        val peekFor = peekDuration(previous, payload, becameVisible)
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
            Kind.GAME -> payload.subtitle.ifBlank { payload.game?.game?.title ?: "Game" }
            Kind.PRIVACY -> "Android 11 and older have no camera or microphone indicator, so OEA shows one here."
        }
        rebuildActions(payload)
        updatePrivacy(payload)
        updateProgress()
        expandIcon.rotation = if (expanded) 180f else 0f
        card.contentDescription = contentDescription
        card.setOnClickListener { toggleExpanded() }
        applyLayout()
        applyPalette()
        if (peekFor != 0L) peek(payload, peekFor)
        else if (expanded && previous?.ringing == true && !payload.ringing && peekKey == payload.key) setExpanded(false, animate = true)
    }

    /**
     * How long the island should expand by itself to get attention: a
     * ringing call stays open (-1) until answered or declined; a new message,
     * a new track, plugging in a charger or a game event open it briefly.
     * Nothing peeks while the surface is hidden, in a game (except calls), or
     * for an activity the user has already closed by hand.
     */
    private fun peekDuration(previous: Payload?, payload: Payload, becameVisible: Boolean): Long {
        // Only a surface the user can actually see peeks (Home stopped = window hidden).
        if (!store.islandPeek() || !isAttachedToWindow || windowVisibility != VISIBLE) return 0L
        if (signature(payload) == userClosedSignature) return 0L
        if (payload.ringing) return if (expanded && peekKey == payload.key) 0L else -1L
        if (inGame) return 0L
        val newActivity = previous?.key != payload.key
        return when (payload.kind) {
            Kind.NOTIFICATION -> {
                val fresh = (payload.notification?.postTime ?: 0L) > System.currentTimeMillis() - FRESH_NOTIFICATION_MS
                if (fresh && (newActivity || previous?.title != payload.title || previous.subtitle != payload.subtitle)) PEEK_NOTIFICATION_MS else 0L
            }
            Kind.MEDIA -> if (!newActivity && previous?.title != payload.title) PEEK_MEDIA_MS else 0L
            Kind.CHARGING -> if (newActivity) PEEK_CHARGING_MS else 0L
            Kind.GAME -> if (newActivity && payload.gameEvent != null) (payload.gameEvent.until - SystemClock.uptimeMillis()).coerceIn(1_200L, 6_000L) else 0L
            Kind.CALL, Kind.PRIVACY -> 0L
        }
    }

    private fun signature(payload: Payload) = payload.key + "|" + payload.title + "|" + payload.subtitle + "|" + payload.ringing

    private fun peek(payload: Payload, duration: Long) {
        peekKey = payload.key
        removeCallbacks(peekCollapse)
        if (!expanded) setExpanded(true, animate = true, fromUser = false)
        if (duration > 0L) {
            peekUntil = SystemClock.uptimeMillis() + duration
            postDelayed(peekCollapse, duration)
        }
    }

    /** Camera/microphone dots inside a visible island; text in the expanded view. */
    private fun updatePrivacy(payload: Payload) {
        val show = store.islandPrivacyDots() && payload.kind != Kind.PRIVACY
        val camera = show && OeaPrivacyMonitor.cameraInUse
        val mic = show && OeaPrivacyMonitor.micInUse
        compactPrivacy.set(camera, mic)
        headerPrivacy.set(camera, mic)
        compactPrivacy.visibility = if (camera || mic) VISIBLE else GONE
        headerPrivacy.visibility = if (camera || mic) VISIBLE else GONE
        privacyText.visibility = if (camera || mic) VISIBLE else GONE
        privacyText.text = when {
            camera && mic -> "● Camera and microphone are in use"
            camera -> "● Camera is in use"
            mic -> "● Microphone is in use"
            else -> ""
        }
        privacyText.setTextColor(if (camera) PrivacyDotsView.CAMERA else PrivacyDotsView.MIC)
    }

    /** Media position, updated once a second only while the island is expanded and playing. */
    private fun updateProgress() {
        val payload = current
        val notification = payload?.notification?.notification
        val controller = if (payload?.kind == Kind.MEDIA && notification != null) mediaController(notification) else null
        val duration = runCatching { controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L }.getOrDefault(0L)
        val state = runCatching { controller?.playbackState }.getOrNull()
        if (controller == null || duration <= 0L || state == null) {
            progressRow.visibility = GONE
            removeCallbacks(progressTicker)
            return
        }
        val playing = state.state == PlaybackState.STATE_PLAYING
        var position = state.position
        if (playing && state.lastPositionUpdateTime > 0L) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, duration)
        progressRow.visibility = VISIBLE
        progressBar.fraction = position / duration.toFloat()
        progressBar.color = palette.accent
        elapsedText.text = formatTime(position)
        durationText.text = formatTime(duration)
        removeCallbacks(progressTicker)
        if (playing && expanded && isAttachedToWindow) postDelayed(progressTicker, 1_000L)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000L
        return if (total >= 3600L) String.format(java.util.Locale.US, "%d:%02d:%02d", total / 3600L, (total % 3600L) / 60L, total % 60L)
        else String.format(java.util.Locale.US, "%d:%02d", total / 60L, total % 60L)
    }

    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val candidate = current?.takeIf { it.kind == Kind.NOTIFICATION }?.notification
            val location = IntArray(2)
            card.getLocationOnScreen(location)
            val downInsideCard = card.visibility == VISIBLE && card.isShown &&
                event.rawX >= location[0] && event.rawX <= location[0] + card.width &&
                event.rawY >= location[1] && event.rawY <= location[1] + card.height
            notificationSwipeTarget = candidate?.takeIf { downInsideCard && it.isClearable && OeaNotificationState.connected }
            swipeStartRawX = event.rawX
            swipeStartRawY = event.rawY
            notificationSwipeIntercepted = false
        }
        val notification = notificationSwipeTarget
        if (notification == null || !notification.isClearable || !OeaNotificationState.connected) return false
        if (current?.notification?.key != notification.key) {
            notificationSwipeTarget = null
            notificationSwipeIntercepted = false
            return false
        }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - swipeStartRawX
                val dy = event.rawY - swipeStartRawY
                if (kotlin.math.abs(dx) > dp(40) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                    notificationSwipeIntercepted = true
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                notificationSwipeTarget = null
                notificationSwipeIntercepted = false
            }
        }
        return false
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!notificationSwipeIntercepted) return super.onTouchEvent(event)
        val notification = notificationSwipeTarget
        val dx = event.rawX - swipeStartRawX
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                card.translationX = dx
                card.alpha = (1f - kotlin.math.abs(dx) / dp(360).toFloat()).coerceIn(0.35f, 1f)
            }
            android.view.MotionEvent.ACTION_UP -> {
                notificationSwipeIntercepted = false
                notificationSwipeTarget = null
                val stillCurrent = notification != null && current?.notification?.key == notification.key
                if (stillCurrent && kotlin.math.abs(dx) >= dp(72) && OeaNotificationState.dismiss(notification!!)) {
                    card.animate().cancel()
                    card.animate()
                        .translationX(if (dx < 0) -resources.displayMetrics.widthPixels.toFloat() else resources.displayMetrics.widthPixels.toFloat())
                        .alpha(0f)
                        .setDuration(180L)
                        .withEndAction { card.translationX = 0f; card.alpha = 1f }
                        .start()
                } else {
                    card.animate().translationX(0f).alpha(1f).setDuration(160L).start()
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                notificationSwipeIntercepted = false
                notificationSwipeTarget = null
                card.animate().translationX(0f).alpha(1f).setDuration(140L).start()
            }
        }
        return true
    }

    private fun labelFor(kind: Kind): String = when (kind) {
        Kind.CALL -> "Call"
        Kind.MEDIA -> "Now playing"
        Kind.NOTIFICATION -> "Live notification"
        Kind.CHARGING -> "Battery"
        Kind.GAME -> "Game"
        Kind.PRIVACY -> "Privacy"
    }

    private fun setLeadingIcon(payload: Payload) {
        val icon = payload.icon
        val glyph = when (payload.kind) {
            Kind.CALL -> "☎"
            Kind.MEDIA -> "♫"
            Kind.NOTIFICATION -> "•"
            Kind.CHARGING -> "⚡"
            Kind.GAME -> payload.gameGlyph
            Kind.PRIVACY -> "●"
        }
        val art = payload.art?.takeIf { !it.isRecycled }
        if (art != null) {
            // Album art fills the leading circle instead of a tinted glyph.
            listOf(appIcon, compactIcon).forEach { view ->
                view.setImageBitmap(art)
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                view.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.BLACK) }
                view.clipToOutline = true
                view.visibility = VISIBLE
            }
            fallbackIcon.visibility = GONE
            compactFallback.visibility = GONE
            return
        }
        listOf(appIcon, compactIcon).forEach { view ->
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.background = null
            view.clipToOutline = false
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
            Kind.GAME -> payload.game?.activity?.compactText?.ifBlank { null } ?: payload.title
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
            Kind.GAME -> payload.game?.activity?.trailing?.ifBlank { null } ?: payload.gameGlyph
            Kind.PRIVACY -> if (OeaPrivacyMonitor.cameraInUse) "Cam" else "Mic"
        }
        if (payload.kind == Kind.PRIVACY) trailingText.setTextColor(if (OeaPrivacyMonitor.cameraInUse) PrivacyDotsView.CAMERA else PrivacyDotsView.MIC)
        if (payload.kind == Kind.NOTIFICATION) trailingText.setTextColor(palette.accent)
        if (payload.kind == Kind.GAME) {
            val accent = payload.game?.activity?.accent?.takeIf { it != 0 } ?: payload.game?.game?.accent ?: payload.gameEvent?.game?.accent ?: palette.accent
            trailingText.setTextColor(if (payload.game?.activity?.urgent == true) Color.rgb(255, 99, 99) else accent)
        }
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
            payload.kind == Kind.GAME -> {
                val g = payload.game
                if (!inGame && (payload.game != null || payload.gameEvent != null)) addTextAction("Resume") { OeaGameLiveState.resume(context) }
                g?.activity?.actions?.take(3)?.forEach { a -> addTextAction(a.label.take(16)) { g.onAction?.invoke(a.id); postDelayed({ render() }, 300L) } }
            }
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
        val clearableNotification = payload.notification?.takeIf { payload.kind == Kind.NOTIFICATION && it.isClearable }
        if (clearableNotification != null && OeaNotificationState.connected && actions.childCount < 3) {
            addTextAction("Dismiss") { OeaNotificationState.dismiss(clearableNotification) }
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
        val payload = current ?: return
        removeCallbacks(peekCollapse)
        // Closing an activity by hand stops it from peeking open again until its content changes.
        if (expanded) userClosedSignature = signature(payload)
        setExpanded(!expanded, animate = true)
    }

    private fun setExpanded(value: Boolean, animate: Boolean, fromUser: Boolean = true) {
        if (current == null && value) return
        if (expanded == value) return
        if (fromUser) removeCallbacks(peekCollapse)
        if (animate && isAttachedToWindow && isLaidOut) {
            // Expanding morphs out with a slight spring; collapsing settles quickly.
            TransitionManager.beginDelayedTransition(this, TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade())
                duration = if (value) (if (isCamera) 420L else 320L) else 240L
                interpolator = if (value) OvershootInterpolator(0.85f) else PathInterpolator(0.3f, 0f, 0.1f, 1f)
            })
        }
        expanded = value
        if (!value) removeCallbacks(progressTicker)
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
        if (value) updateProgress()
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
            params.topMargin = if (overlayWindow) 0 else cardTop + extraTop + dp(4)
            card.setPadding(dp(13), dp(9), dp(13), dp(10))
            card.layoutParams = params
            notifyOverlayGeometry()
            return
        }
        // System UI owns status-bar touches. Anchor just below the greater of
        // the status bar and the cutout's lower edge, then let the saved Y
        // offset reposition the island for unusual camera layouts.
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val pillHeight = dp(CAMERA_PILL_DP)
        val cameraX = dp(store.islandCameraOffsetX())
        val pillTop = cameraPillTop(screenHeight, pillHeight)
        val centerX = ((cutout?.centerX() ?: (screenWidth / 2)) + cameraX).coerceIn(0, screenWidth)
        if (expanded) {
            compactRow.visibility = GONE
            header.visibility = VISIBLE
            details.visibility = VISIBLE
            val width = minOf(screenWidth - dp(16), dp(440))
            params.width = width
            params.height = LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = if (overlayWindow) 0 else (centerX - width / 2).coerceIn(dp(8), (screenWidth - width - dp(8)).coerceAtLeast(dp(8)))
            params.topMargin = if (overlayWindow) 0 else pillTop
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
            params.leftMargin = if (overlayWindow) 0 else (centerX - width / 2).coerceIn(dp(8), (screenWidth - width - dp(8)).coerceAtLeast(dp(8)))
            params.topMargin = if (overlayWindow) 0 else pillTop
        }
        card.layoutParams = params
        notifyOverlayGeometry()
        val reserved = reservedHeight()
        if (reserved != lastCameraReservedHeight) {
            lastCameraReservedHeight = reserved
            post { onReservedHeightChanged?.invoke() }
        }
    }

    private fun cameraPillTop(screenHeight: Int, pillHeight: Int): Int {
        val anchor = maxOf(statusBarHeight, cutout?.bottom ?: 0) + dp(3) + dp(store.islandCameraOffsetY())
        return anchor.coerceIn(0, (screenHeight - pillHeight).coerceAtLeast(0))
    }

    /** Requests a WindowManager resize/reposition after a payload or inset change. */
    fun updateOverlayWindowGeometry() = notifyOverlayGeometry()

    private fun notifyOverlayGeometry() {
        if (!overlayWindow || !isAttachedToWindow) return
        post {
            if (!overlayWindow || !isAttachedToWindow) return@post
            val callback = onOverlayGeometryChanged ?: return@post
            val visible = visibility == VISIBLE && card.visibility == VISIBLE
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val cardParams = card.layoutParams as? LayoutParams
            val widthSpec = if (cardParams != null && cardParams.width > 0) {
                MeasureSpec.makeMeasureSpec(cardParams.width.coerceAtMost(screenWidth), MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.AT_MOST)
            }
            val heightSpec = if (cardParams != null && cardParams.height > 0) {
                MeasureSpec.makeMeasureSpec(cardParams.height.coerceAtMost(screenHeight), MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.AT_MOST)
            }
            card.measure(widthSpec, heightSpec)
            val width = if (visible) card.measuredWidth.coerceIn(1, screenWidth) else 1
            val height = if (visible) card.measuredHeight.coerceIn(1, screenHeight) else 1
            val offsetX = if (isCamera) dp(store.islandCameraOffsetX()) else 0
            val cameraCenter = cutout?.centerX() ?: screenWidth / 2
            val centerX = ((if (isCamera) cameraCenter else screenWidth / 2) + offsetX).coerceIn(0, screenWidth)
            val x = if (visible) (centerX - width / 2).coerceIn(0, (screenWidth - width).coerceAtLeast(0)) else 0
            val top = if (isCamera) cameraPillTop(screenHeight, height) else cardTop + extraTop + dp(4)
            val y = if (visible) top.coerceIn(0, (screenHeight - height).coerceAtLeast(0)) else 0
            val key = "$width:$height:$x:$y:$visible"
            if (key == lastOverlayGeometryKey) return@post
            lastOverlayGeometryKey = key
            callback(width, height, x, y, visible)
        }
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
        } else if (store.glassStyle() == OeaDataStore.GLASS_LIQUID) {
            // Liquid glass: deep translucent body with a bright top edge.
            card.background = com.oea.launcher.ui.OeaGlassDrawable(
                fill = OeaTheme.alpha(OeaTheme.blend(Color.rgb(12, 14, 20), palette.accent, 0.06f), 0xE6),
                radius = dp(28).toFloat(),
                density = density,
                tint = palette.accent,
            )
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
        const val PEEK_NOTIFICATION_MS = 4_000L
        const val PEEK_MEDIA_MS = 3_000L
        const val PEEK_CHARGING_MS = 2_500L
        const val FRESH_NOTIFICATION_MS = 8_000L
    }

    /** One or two small dots: green for the camera, orange for the microphone. */
    private class PrivacyDotsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var camera = false
        private var mic = false

        fun set(camera: Boolean, mic: Boolean) {
            if (this.camera == camera && this.mic == mic) return
            this.camera = camera
            this.mic = mic
            contentDescription = when {
                camera && mic -> "Camera and microphone in use"
                camera -> "Camera in use"
                mic -> "Microphone in use"
                else -> null
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) * 0.16f
            val cy = height / 2f
            val dots = listOfNotNull(if (camera) CAMERA else null, if (mic) MIC else null)
            if (dots.isEmpty()) return
            val gap = r * 2.6f
            var cx = width / 2f - (dots.size - 1) * gap / 2f
            dots.forEach { color ->
                paint.color = color
                canvas.drawCircle(cx, cy, r, paint)
                cx += gap
            }
        }

        companion object {
            val CAMERA = Color.rgb(52, 199, 89)
            val MIC = Color.rgb(255, 159, 10)
        }
    }

    /** Thin rounded playback position bar. */
    private class MediaProgressView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var fraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        var color = Color.WHITE
            set(value) { field = value; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = 0x33FFFFFF
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = color
            rect.set(0f, 0f, width * fraction, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }
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
