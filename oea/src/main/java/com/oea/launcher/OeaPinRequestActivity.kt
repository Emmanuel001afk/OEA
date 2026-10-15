package com.oea.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.oea.launcher.runtime.OeaRuntime
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType

/**
 * Handles "Add to Home screen" requests from other apps (pinned shortcuts such
 * as a Chrome website or a contact, and pinned widgets). Android sends these
 * only to the default Home app.
 */
class OeaPinRequestActivity : Activity() {
    private var request: LauncherApps.PinItemRequest? = null
    private var pendingWidgetId = -1
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        com.oea.launcher.fault.OeaFaultShield.guard("pin-request:create") {
            super.onCreate(savedInstanceState)
            OeaRuntime.initialize(application)
            val launcherApps = getSystemService(LauncherApps::class.java)
            val req = runCatching { launcherApps?.getPinItemRequest(intent) }.getOrNull()
            if (req == null || !req.isValid) {
                finish()
                return
            }
            request = req
            val kit = OeaKit(this, OeaTheme.resolve(this))
            when (req.requestType) {
                LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> confirmShortcut(kit, req, launcherApps)
                LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> confirmWidget(kit, req)
                else -> finish()
            }
        }
    }

    private fun confirmShortcut(kit: OeaKit, req: LauncherApps.PinItemRequest, launcherApps: LauncherApps?) {
        val info = req.shortcutInfo ?: run { finish(); return }
        val label = (info.shortLabel ?: info.longLabel)?.toString() ?: "Shortcut"
        val icon = runCatching { launcherApps?.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi) }.getOrNull()
        val appLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.`package`, 0)).toString() }.getOrDefault(info.`package`)
        val dialog = kit.sheet("Add to Home screen", appLabel) { panel, dialog ->
            preview(kit, panel, icon, label)
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Add") {
                    if (req.accept()) {
                        OeaRuntime.engine(this).workspace.addShortcutToHome(info)
                    } else {
                        Toast.makeText(this, "The app withdrew this shortcut", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
))
        }
        dialog.setOnDismissListener { done() }
    }

    private fun confirmWidget(kit: OeaKit, req: LauncherApps.PinItemRequest) {
        val info = runCatching { req.getAppWidgetProviderInfo(this) }.getOrNull() ?: run { finish(); return }
        val label = info.loadLabel(packageManager)
        val preview = runCatching { info.loadPreviewImage(this, 0) }.getOrNull() ?: runCatching { info.loadIcon(this, 0) }.getOrNull()
        val dialog = kit.sheet("Add widget to Home", label) { panel, dialog ->
            preview(kit, panel, preview, label, large = true)
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Add") {
                    val widgets = OeaRuntime.engine(this).workspace.widgets
                    val id = widgets.allocateId()
                    if (widgets.bindIfAllowed(id, info, req.extras)) {
                        acceptWidget(id)
                        dialog.dismiss()
                    } else {
                        pendingWidgetId = id
                        finishing = true
                        dialog.setOnDismissListener(null)
                        dialog.dismiss()
                        runCatching { startActivityForResult(widgets.bindIntent(id, info), REQUEST_BIND) }
                            .onFailure { widgets.deleteId(id); done() }
                    }
                }
))
        }
        dialog.setOnDismissListener { done() }
    }

    private fun preview(kit: OeaKit, panel: LinearLayout, drawable: android.graphics.drawable.Drawable?, label: String, large: Boolean = false) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(16), kit.dp(18), kit.dp(16), kit.dp(16))
            background = kit.rounded(kit.palette.card, 24)
        }
        card.addView(ImageView(this).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(if (large) -1 else kit.dp(64), kit.dp(if (large) 140 else 64)))
        card.addView(kit.text(label, 15f, kit.palette.text, OeaType.medium).apply {
            gravity = Gravity.CENTER
            setPadding(0, kit.dp(10), 0, 0)
        })
        panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(14) })
    }

    private fun acceptWidget(id: Int) {
        val req = request
        val workspace = OeaRuntime.engine(this).workspace
        val accepted = req != null && runCatching {
            req.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })
        }.getOrDefault(false)
        if (accepted) {
            workspace.addPinnedWidget(id)
            Toast.makeText(this, "Widget added to Home", Toast.LENGTH_SHORT).show()
        } else {
            workspace.widgets.deleteId(id)
        }
    }

    @Deprecated("Activity result API is sufficient for this one-shot flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_BIND) {
            if (resultCode == RESULT_OK && pendingWidgetId > 0) acceptWidget(pendingWidgetId)
            else if (pendingWidgetId > 0) OeaRuntime.engine(this).workspace.widgets.deleteId(pendingWidgetId)
            pendingWidgetId = -1
            done()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun done() {
        if (!isFinishing) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_BIND = 7501
    }
}
