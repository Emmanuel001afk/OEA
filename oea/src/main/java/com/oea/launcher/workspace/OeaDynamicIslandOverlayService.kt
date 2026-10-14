package com.oea.launcher.workspace

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore

/**
 * Optional system-wide host for OEA's live-activity surfaces. It is started
 * only after the user enables the feature and grants Android overlay access.
 */
class OeaDynamicIslandOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var store: OeaDataStore
    private var cardIsland: OeaDynamicIslandView? = null
    private var cameraIsland: OeaDynamicIslandView? = null
    private var receiverRegistered = false
    private var overlayAppOps: AppOpsManager? = null

    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { refreshIslands() }
    }
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { op, changedPackage ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW &&
            (changedPackage == null || changedPackage == packageName)
        ) {
            mainHandler.post {
                if (!canRun()) {
                    overlayActive = false
                    disableOverlayIfPermissionRevoked()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    refreshDisplayGeometry()
                }
            }
        }
    }
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val configurationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) refreshDisplayGeometry()
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlayActive = false
        store = OeaDataStore.get(applicationContext)
        windowManager = getSystemService(WindowManager::class.java) ?: run {
            Log.e(TAG, "WindowManager is unavailable; leaving the in-Home island enabled")
            store.setIslandOnOtherApps(false)
            stopSelf()
            return
        }
        createNotificationChannel()
        // Promote before doing WindowManager work; this is a user-visible,
        // explicit feature and must not silently become a background service.
        val notification = buildNotification()
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            Log.e(TAG, "Could not start the optional Dynamic Island foreground service", error)
            store.setIslandOnOtherApps(false)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }.isSuccess
        if (!promoted) {
            stopSelf()
            return
        }
        if (!canRun()) {
            disableOverlayIfPermissionRevoked()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        store.registerListener(preferenceListener)
        watchOverlayPermission()
        createIsland(OeaDynamicIslandView.Surface.CARD).also { cardIsland = it }
        createIsland(OeaDynamicIslandView.Surface.CAMERA).also { cameraIsland = it }
        cameraIsland?.onReservedHeightChanged = {
            cardIsland?.setExtraTop(cameraIsland?.reservedHeight() ?: 0)
        }
        cardIsland?.setExtraTop(cameraIsland?.reservedHeight() ?: 0)
        val cardAttached = attachIsland(cardIsland)
        val cameraAttached = attachIsland(cameraIsland)
        if (!cardAttached || !cameraAttached) {
            Log.w(TAG, "Could not attach both system-wide island windows; stopping the optional service")
            store.setIslandOnOtherApps(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        overlayActive = true
        registerConfigurationReceiver()
        refreshDisplayGeometry()
        refreshIslands()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            overlayActive = false
            store.setIslandOnOtherApps(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canRun()) {
            overlayActive = false
            disableOverlayIfPermissionRevoked()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) refreshIslands()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayActive = false
        store.unregisterListener(preferenceListener)
        overlayAppOps?.let { ops -> runCatching { ops.stopWatchingMode(overlayPermissionListener) } }
        overlayAppOps = null
        if (receiverRegistered) runCatching { unregisterReceiver(configurationReceiver) }
        receiverRegistered = false
        mainHandler.removeCallbacksAndMessages(null)
        removeIsland(cardIsland)
        removeIsland(cameraIsland)
        cardIsland = null
        cameraIsland = null
        super.onDestroy()
    }

    private fun canRun(): Boolean =
        store.islandOnOtherApps() && store.dynamicIslandEnabled() && Settings.canDrawOverlays(this)

    private fun disableOverlayIfPermissionRevoked() {
        if (!Settings.canDrawOverlays(this) && store.islandOnOtherApps()) {
            store.setIslandOnOtherApps(false)
        }
    }

    private fun createIsland(surface: OeaDynamicIslandView.Surface): OeaDynamicIslandView {
        val island = OeaDynamicIslandView(this, surface, overlayWindow = true)
        island.onOverlayGeometryChanged = { width, height, x, y, visible ->
            updateIslandWindow(island, width, height, x, y, visible)
        }
        island.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                island.collapse()
                true
            } else false
        }
        island.setOnApplyWindowInsetsListener { _, insets ->
            val cutout = topCutout(insets)
            val statusHeight = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            } else {
                statusBarHeight()
            }
            island.setTopArea(statusHeight, cutout)
            insets
        }
        return island
    }

    private fun attachIsland(island: OeaDynamicIslandView?): Boolean {
        if (island == null) return false
        if (island.parent != null) return true
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        return runCatching {
            windowManager.addView(island, params)
            island.requestApplyInsets()
            true
        }.onFailure { Log.w(TAG, "Could not attach system-wide island window", it) }
            .getOrDefault(false)
    }

    private fun watchOverlayPermission() {
        val ops = getSystemService(AppOpsManager::class.java) ?: return
        runCatching {
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionListener)
            overlayAppOps = ops
        }.onFailure { Log.w(TAG, "Could not watch overlay permission changes", it) }
    }

    private fun updateIslandWindow(
        island: OeaDynamicIslandView,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        visible: Boolean,
    ) {
        if (!Settings.canDrawOverlays(this)) {
            overlayActive = false
            disableOverlayIfPermissionRevoked()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Camera-hole mode is a portrait-only surface. Do not leave a
        // floating capsule across landscape games/videos; Home's own island
        // remains governed by its normal workspace layout.
        val portraitOnlyHidden = island === cameraIsland &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val shouldShow = visible && !portraitOnlyHidden
        val existing = island.layoutParams as? WindowManager.LayoutParams
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = (existing ?: WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )).apply {
            this.width = if (shouldShow) width.coerceAtLeast(1) else 1
            this.height = if (shouldShow) height.coerceAtLeast(1) else 1
            gravity = Gravity.TOP or Gravity.START
            this.x = if (shouldShow) x.coerceAtLeast(0) else 0
            this.y = if (shouldShow) y.coerceAtLeast(0) else 0
            flags = if (shouldShow) baseFlags else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching {
            if (island.parent == null) windowManager.addView(island, params)
            else windowManager.updateViewLayout(island, params)
        }.onFailure { error ->
            Log.w(TAG, "Could not update system-wide island window", error)
            if (error is SecurityException) {
                overlayActive = false
                disableOverlayIfPermissionRevoked()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun removeIsland(island: View?) {
        if (island == null || island.parent == null) return
        runCatching { windowManager.removeViewImmediate(island) }
            .onFailure { Log.w(TAG, "Could not remove system-wide island window", it) }
    }

    private fun refreshDisplayGeometry() {
        val metricsInsets = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { windowManager.currentWindowMetrics.windowInsets }.getOrNull()
        } else null
        val insets = metricsInsets ?: cameraIsland?.rootWindowInsets ?: cardIsland?.rootWindowInsets
        val status = if (Build.VERSION.SDK_INT >= 30 && metricsInsets != null) {
            metricsInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        } else {
            statusBarHeight()
        }
        val topCutout = topCutout(insets)
        listOfNotNull(cardIsland, cameraIsland).forEach { island ->
            island.setTopArea(status, topCutout)
            island.updateOverlayWindowGeometry()
        }
    }

    private fun topCutout(insets: WindowInsets?): Rect? {
        if (Build.VERSION.SDK_INT < 28) return null
        val rects = runCatching { insets?.displayCutout?.boundingRects }.getOrNull().orEmpty()
        val screenWidth = resources.displayMetrics.widthPixels
        return rects
            .filter { it.top <= dp(8) && it.width() in 1 until screenWidth / 2 }
            .minByOrNull { kotlin.math.abs(it.centerX() - screenWidth / 2) }
            ?.let { Rect(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun refreshIslands() {
        if (!canRun()) {
            overlayActive = false
            disableOverlayIfPermissionRevoked()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        listOfNotNull(cardIsland, cameraIsland).forEach { island ->
            island.refresh()
            island.updateOverlayWindowGeometry()
        }
    }

    private fun registerConfigurationReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        receiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(configurationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(configurationReceiver, filter)
            true
        }.getOrDefault(false)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Dynamic Island", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the optional OEA live-activity island visible across apps."
        })
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OeaDynamicIslandOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
            .setContentTitle("OEA Dynamic Island is active")
            .setContentText("Showing compatible live activities over other apps")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopIntent)
            .build()
    }

    companion object {
        @Volatile internal var overlayActive: Boolean = false
        private const val TAG = "OeaIslandOverlay"
        private const val CHANNEL_ID = "oea_dynamic_island_overlay"
        private const val NOTIFICATION_ID = 0x0EA3
        private const val ACTION_STOP = "com.oea.launcher.action.STOP_ISLAND_OVERLAY"
        private const val ACTION_REFRESH = "com.oea.launcher.action.REFRESH_ISLAND_OVERLAY"

        /** Starts only from a visible user action or stops when any prerequisite is gone. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val store = OeaDataStore.get(app)
            val shouldRun = store.dynamicIslandEnabled() && store.islandOnOtherApps() && Settings.canDrawOverlays(app)
            if (!shouldRun) {
                app.stopService(Intent(app, OeaDynamicIslandOverlayService::class.java))
                return
            }
            val intent = Intent(app, OeaDynamicIslandOverlayService::class.java).setAction(ACTION_REFRESH)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            }.onFailure {
                Log.w(TAG, "Could not start system-wide island service", it)
                store.setIslandOnOtherApps(false)
            }
        }
    }
}
