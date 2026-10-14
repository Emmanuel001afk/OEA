package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType

/**
 * OEA's launcher mini-apps: the small tools that should not need a separate app
 * installed, or a trip through the store.
 *
 * Every mini-app is a plain View builder. That keeps them cheap to open and, more
 * importantly, **contained**: a mini-app builds inside a `runCatching`, so a fault
 * in one shows a card in its own page and leaves Home, the drawer and every other
 * mini-app untouched. Nothing here can take the launcher down with it.
 */
object OeaMiniApps {

    /** One mini-app: how it is listed and how its view is built. */
    class App(
        val id: String,
        val label: String,
        val blurb: String,
        val icon: Int,
        /** Builds the mini-app's view. [host] is the activity showing it. */
        val build: (host: Activity, palette: OeaPalette) -> View,
    )

    val all: List<App> = listOf(
        App(
            "gallery", "Gallery", "Every OEA picture — wallpapers, lock screens, your photos and the live scenes", R.drawable.oic_photo_library,
        ) { host, palette -> OeaGalleryApp.build(host, palette) },
        App(
            "notes", "Notes", "Quick notes that stay on this phone", R.drawable.oic_edit,
        ) { host, palette -> OeaNotesApp.build(host, palette) },
        App(
            "calculator", "Calculator", "Arithmetic with a running tape", R.drawable.oic_calculate,
        ) { host, palette -> OeaCalculatorApp.build(host, palette) },
    )

    fun byId(id: String?): App? = all.firstOrNull { it.id == id }

    /**
     * Builds a mini-app's view, or a contained failure card when it cannot start.
     * The user is told what happened and given the route to the fault log, exactly
     * as OEA's own fault shield does elsewhere — a mini-app never fails silently.
     */
    fun build(host: Activity, id: String?, kit: OeaKit): View {
        val app = byId(id)
        if (app == null) {
            return kit.text("This mini-app is no longer available.", 15f, kit.palette.textSecondary).apply {
                setPadding(kit.dp(6), kit.dp(10), kit.dp(6), kit.dp(10))
            }
        }
        val started = System.currentTimeMillis()
        return runCatching { app.build(host, kit.palette) }
            .onFailure { error ->
                Log.w(TAG, "Mini-app ${app.id} could not build", error)
                OeaFaultShield.note("miniapp:${app.id}", error)
            }
            .getOrElse {
                val card = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                card.addView(kit.banner(card, R.drawable.oic_warning_fill, "${app.label} could not start", "A problem in this mini-app was contained — the launcher is unaffected."))
                card.addView(
                    kit.row(card, R.drawable.oic_shield, "See what happened", "Settings › Stability keeps the details") {
                        runCatching {
                            host.startActivity(
                                Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java)
                                    .putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "stability"),
                            )
                        }
                    },
                )
                card
            }
            .also { built ->
                built.alpha = 0f
                built.animate().alpha(1f).setDuration(160L).start()
                if (System.currentTimeMillis() - started > 250L) Log.i(TAG, "Mini-app ${app.id} took ${System.currentTimeMillis() - started} ms to build")
            }
    }

    /** Starts the mini-app host. Used by Home's menu and by Settings. */
    fun open(context: Context, id: String? = null): Boolean = runCatching {
        val intent = Intent(context, OeaMiniAppActivity::class.java)
        if (id != null) intent.putExtra(OeaMiniAppActivity.EXTRA_APP, id)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "Mini-app host could not start", it)
        false
    }

    /** A tile used by the host's index and by any list that offers the mini-apps. */
    fun tile(kit: OeaKit, app: App, onClick: () -> Unit): View {
        val column = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            isClickable = true
            background = kit.ripple(kit.rounded(kit.palette.card, 20), 20)
            setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
            setOnClickListener { onClick() }
        }
        column.addView(kit.iconBadge(app.icon, kit.palette.accentSoft, kit.palette.onAccentSoft, 44))
        column.addView(
            kit.text(app.label, 14f, kit.palette.text, OeaType.medium).apply {
                setPadding(0, kit.dp(8), 0, 0)
                gravity = android.view.Gravity.CENTER
            },
        )
        column.addView(
            kit.text(app.blurb, 11.5f, kit.palette.textTertiary).apply {
                setPadding(0, kit.dp(2), 0, 0)
                gravity = android.view.Gravity.CENTER
                maxLines = 3
                setLineSpacing(kit.dpf(2f), 1f)
            },
        )
        return column
    }

    /** A three-to-a-row grid of the mini-apps. */
    fun grid(kit: OeaKit, parent: LinearLayout, onOpen: (App) -> Unit) {
        all.chunked(3).forEach { chunk ->
            val row = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { index, app ->
                row.addView(
                    tile(kit, app) { onOpen(app) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        leftMargin = if (index == 0) 0 else kit.dp(5)
                        rightMargin = if (index == chunk.lastIndex) 0 else kit.dp(5)
                    },
                )
            }
            repeat(3 - chunk.size) {
                row.addView(View(kit.context), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = kit.dp(5) })
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
        }
    }

    private const val TAG = "OeaMiniApps"
}
