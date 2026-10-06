package com.oea.launcher.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * OEA-owned widget picker entry point.
 *
 * The platform's widget picker remains the system UI; Launcher3 is not used as
 * the picker controller. The host ID is allocated by OEA so widget lifecycle
 * ownership can later remain entirely inside the OEA runtime.
 */
class OeaWidgetPickerActivity : Activity() {
    private val hostId = 0x4F4541

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = AppWidgetHost(this, hostId)
        val appWidgetId = host.allocateAppWidgetId()
        val pick = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        runCatching { startActivityForResult(pick, REQUEST_PICK) }
            .onFailure {
                host.deleteAppWidgetId(appWidgetId)
                Toast.makeText(this, "Widget picker unavailable", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    @Deprecated("Use Activity Result APIs when this flow is expanded.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK) {
            if (resultCode != RESULT_OK || data == null) finish()
            else {
                val info = runCatching {
                    AppWidgetManager.getInstance(this)
                        .getAppWidgetInfo(data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                }.getOrNull()
                if (info == null) {
                    Toast.makeText(this, "Widget could not be selected", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    // Placement is handled by the OEA workspace in the next stage.
                    setResult(RESULT_OK, data)
                    finish()
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        private const val REQUEST_PICK = 0x4F45
    }
}
