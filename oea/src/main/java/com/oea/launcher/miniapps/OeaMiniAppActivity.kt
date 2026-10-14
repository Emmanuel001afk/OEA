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
 */
class OeaMiniAppActivity : Activity() {
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaKit
    private var screenId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = OeaTheme.resolve(this)
        kit = OeaKit(this, palette)
        screenId = intent.getStringExtra(EXTRA_APP)
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenId = intent.getStringExtra(EXTRA_APP)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Mini-apps show OEA's own pictures; a palette change must not leave a
        // stale-looking page behind.
        val fresh = OeaTheme.resolve(this)
        if (fresh != palette) {
            palette = fresh
            kit = OeaKit(this, palette)
            render()
        }
    }

    private fun render() {
        OeaInsets.edgeToEdge(window, lightBars = !palette.dark)
        val app = OeaMiniApps.byId(screenId)
        val page = kit.page(
            title = app?.label ?: "Mini-apps",
            subtitle = app?.blurb ?: "Small tools built into OEA. They open straight from the launcher and keep working offline.",
            onBack = if (app != null) ({ show(null) }) else null,
            trailing = if (app == null) android.widget.ImageView(this).apply {
                setImageResource(R.drawable.oea_logo)
                layoutParams = LinearLayout.LayoutParams(kit.dp(40), kit.dp(40))
                contentDescription = "OEA"
            } else null,
        )
        if (app == null) {
            page.content.addView(OeaKitHost.sectionTitle(kit, "On this phone"))
            OeaMiniApps.grid(kit, page.content) { chosen -> show(chosen.id) }
            kit.note(
                page.content,
                "Each mini-app is part of OEA itself: no store, no download, and a problem inside one is contained so the launcher keeps running.",
            )
        } else {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(OeaMiniApps.build(this, app.id, kit), LinearLayout.LayoutParams(-1, -2))
            page.content.addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(page.root)
    }

    /** Rebuilds the page in place, which is how a mini-app refreshes itself. */
    fun refresh() = runCatching { render() }.onFailure { OeaFaultShield.note("miniapp:host", it) }

    /** Opens another mini-app (or the index) without stacking activities. */
    fun show(id: String?) {
        screenId = id
        runCatching { render() }.onFailure { OeaFaultShield.note("miniapp:host", it) }
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
