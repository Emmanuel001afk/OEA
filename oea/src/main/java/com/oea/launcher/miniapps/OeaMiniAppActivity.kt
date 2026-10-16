package com.oea.launcher.miniapps

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType

/**
 * Hosts one OEA mini-app, or the index of them.
 *
 * The activity exists for one reason: a mini-app must have a surface that can
 * fault on its own without taking Home with it. Everything a mini-app needs is
 * passed in (a palette and the kit), so a mini-app cannot reach into the
 * launcher's workspace — and a mini-app that throws is caught here.
 *
 * Each mini-app also has its own concrete subclass (see [OeaMiniAppActivities])
 * with its own manifest entry, icon, label and task, so Android and the user
 * see twelve real applications instead of one shared window whose contents had
 * to be guessed from launch extras (which is what made the wrong screen — or a
 * black one — appear on some devices).
 */
open class OeaMiniAppActivity : Activity() {
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaKit
    private var screenId: String? = null

    /**
     * Concrete mini-app activities hard-code their app here, so the screen a
     * mini-app shows can never depend on intent extras surviving task
     * recreation, or on activity-alias metadata being readable on a given
     * Android build — the two failure modes behind "the app opens to the wrong
     * screen / to nothing".
     */
    open val appId: String? get() = null

    /** True for the plain index host (opened from Settings, not the launcher). */
    private val isIndexHost get() = appId == null && intent.getStringExtra(EXTRA_APP) == null && resolveAliasId() == null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val created = OeaFaultShield.guard("miniapp:host:create") {
            palette = OeaTheme.resolve(this)
            kit = OeaKit(this, palette)
            screenId = resolveScreenId()
            renderGuarded()
        }
        if (!created) showFatalFallback()
    }

    private fun resolveScreenId(): String? = appId ?: intent.getStringExtra(EXTRA_APP) ?: resolveAliasId()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        OeaFaultShield.guard("miniapp:host:intent") {
            setIntent(intent)
            screenId = resolveScreenId()
            renderGuarded()
        }
    }

    private fun resolveAliasId(): String? {
        // Kept only so very old home-screen shortcuts that launched through the
        // removed activity aliases keep opening their mini-app instead of the index.
        return try {
            val pm = packageManager
            val info = pm.getActivityInfo(componentName, android.content.pm.PackageManager.GET_META_DATA)
            info.metaData?.getString("oea.miniapp.id")
        } catch (_: Throwable) { null }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A permission just changed hands (camera, calendar, mic, SMS…): rebuild
        // the mini-app so it picks up its new capabilities immediately instead
        // of showing the "needs permission" card until the next visit.
        OeaFaultShield.guard("miniapp:host:permission") { renderGuarded() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (screenId == "camera" && OeaCameraApp.handleActivityResult(this, requestCode, resultCode, data)) return
        if (requestCode == 4001 && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val text = results?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) {
                try {
                    val p = getSharedPreferences("oea_notes_v2", MODE_PRIVATE)
                    val existing = p.getString("notes", "") ?: ""
                    val id = System.currentTimeMillis()
                    val encoded = if (existing.isBlank()) "" else existing + "\u001E"
                    val newRow = listOf(id.toString(), text.replace("\u001F", " ").replace("\u001E", " "), "0", "voice", System.currentTimeMillis().toString(), "0").joinToString("\u001F")
                    p.edit().putString("notes", encoded + newRow).apply()
                    android.widget.Toast.makeText(this, "Voice note saved", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                    intent.putExtra("voice_result", text)
                }
                renderGuarded()
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        if (screenId == "camera") {
            OeaFaultShield.guard("miniapp:camera:pause") { OeaCameraApp.onHostPaused(this) }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        OeaFaultShield.guard("miniapp:host:resume") {
            if (!::palette.isInitialized) {
                palette = OeaTheme.resolve(this)
                kit = OeaKit(this, palette)
                renderGuarded()
                return@guard
            }
            val fresh = OeaTheme.resolve(this)
            if (fresh != palette) {
                palette = fresh
                kit = OeaKit(this, palette)
                renderGuarded()
            }
        }
    }

    /** Renders with a containment net: a fault shows an in-app card, never a dead window. */
    private fun renderGuarded() {
        val ok = OeaFaultShield.guard("miniapp:host:render") { render() }
        if (!ok) showRenderFallback()
    }

    private fun render() {
        OeaInsets.edgeToEdge(window, lightBars = !palette.dark)
        val app = OeaMiniApps.byId(screenId)
        if (app != null && app.id == "camera") {
            // The Camera is full-screen: its viewfinder fills the window and its
            // controls sit over it, so it gets no title page around it.
            setContentView(OeaMiniApps.build(this, app.id, kit))
            return
        }
        val page = kit.page(
            title = app?.label ?: "Mini-apps",
            subtitle = app?.blurb ?: "Small tools built into OEA. They open straight from the launcher and keep working offline.",
            onBack = when {
                app != null && isIndexHost -> ({ show(null) })
                app != null -> ({ finish() })
                else -> null
            },
            trailing = if (app == null) android.widget.ImageView(this).apply {
                setImageResource(R.drawable.oea_logo)
                layoutParams = LinearLayout.LayoutParams(kit.dp(40), kit.dp(40))
                contentDescription = "OEA"
            } else null
)
        if (app == null) {
            page.content.addView(OeaKitHost.sectionTitle(kit, "On this phone"))
            OeaMiniApps.grid(kit, page.content) { chosen ->
                // The index hands straight over to the app's own task; it never
                // reuses this window, so Back from a mini-app returns to Home,
                // not to a mismatched host.
                if (!OeaMiniApps.open(this, chosen.id)) show(chosen.id)
            }
            kit.note(
                page.content,
                "Each mini-app is its own application with its own icon and task: it opens straight from Home, the drawer and Recents, and a problem inside one stays inside it."
)
        } else {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(OeaMiniApps.build(this, app.id, kit), LinearLayout.LayoutParams(-1, -2))
            page.content.addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(page.root)
    }

    /**
     * Last-resort content when even the kit cannot build a page: a plain,
     * system-styled message so the window is never blank and Back always works.
     */
    private fun showFatalFallback() {
        showRenderFallback()
    }

    private fun showRenderFallback() {
        runCatching {
            val background = if (OeaTheme.isSystemNight(this)) 0xFF14161C.toInt() else 0xFFF6F7FA.toInt()
            val textColor = if (OeaTheme.isSystemNight(this)) 0xFFF2F4F7.toInt() else 0xFF181B21.toInt()
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                setBackgroundColor(background)
            }
            root.addView(android.widget.TextView(this).apply {
                text = "This OEA app hit a problem it could not show."
                textSize = 17f
                setTextColor(textColor)
            })
            root.addView(android.widget.TextView(this).apply {
                text = "OEA contained it; the launcher and your other apps are unaffected. Settings › Stability keeps the details."
                textSize = 14f
                setPadding(0, 24, 0, 0)
                setTextColor(textColor)
                alpha = 0.7f
            })
            setContentView(root)
        }.onFailure {
            // Nothing else can be done safely; Android's own window background
            // (never transparent black) remains, and the fault is on record.
            OeaFaultShield.note("miniapp:host:fallback", "even the fallback screen failed to build")
        }
    }

    override fun onBackPressed() {
        // Inside the index host, Back from a mini-app returns to the index.
        // In a dedicated mini-app window, Back behaves like any other app's.
        if (screenId != null && isIndexHost) {
            show(null)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** Rebuilds the page in place, which is how a mini-app refreshes itself. */
    fun refresh() = runCatching { renderGuarded() }.onFailure { OeaFaultShield.note("miniapp:host", it) }

    /** Opens another mini-app (or the index) without stacking activities. Used by the index host only. */
    fun show(id: String?) {
        screenId = id
        runCatching { renderGuarded() }.onFailure { OeaFaultShield.note("miniapp:host", it) }
    }

    /** A toast that never throws (a dying activity must not shadow the real error). */
    fun say(message: String) {
        runCatching { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val EXTRA_APP = "oea.miniapp.id"
    }
}

/**
 * The two helpers a mini-app needs from the activity, in one place so a mini-app
 * never holds a reference to the activity itself.
 */
object OeaKitHost {
    /** A section heading, matching the rest of OEA. */
    fun sectionTitle(kit: OeaKit, title: String): View = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(kit.text(title, 13f, kit.palette.textTertiary, OeaType.medium).apply {
            letterSpacing = 0.08f
            setPadding(kit.dp(6), kit.dp(10), 0, kit.dp(8))
        })
    }
}
