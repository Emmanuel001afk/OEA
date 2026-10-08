package com.oea.launcher.gameboost

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

class OeaGameCaptureActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_RECORD = "record"
        private const val REQ = 7401
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent in-game capture host. It must not create a separate
        // OEA task or recents entry; the game remains visible underneath
        // Android's capture sheet while Game Boost stays in its overlay.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0f)
        window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        overridePendingTransition(0, 0)
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Game Boost is a full-screen recorder/screenshot tool, not an app-window
        // picker. On Android 14+ explicitly request the default display so the
        // consent sheet follows the game/display capture flow used by recorders.
        val consentIntent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        runCatching { startActivityForResult(consentIntent, REQ) }.onFailure {
            Toast.makeText(this, "OEA could not start screen capture.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ || resultCode != RESULT_OK || data == null) {
            getSharedPreferences("oea_game_boost", MODE_PRIVATE).edit()
                .putBoolean("recording", false)
                .putBoolean("capture_active", false)
                .apply()
            finish()
            return
        }
        val serviceIntent = Intent(this, OeaGameCaptureService::class.java).apply {
            action = OeaGameCaptureService.ACTION_START
            putExtra(OeaGameCaptureService.EXTRA_MODE, getIntent().getStringExtra(EXTRA_MODE) ?: MODE_SCREENSHOT)
            putExtra(OeaGameCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OeaGameCaptureService.EXTRA_RESULT_DATA, data)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        }.onFailure {
            getSharedPreferences("oea_game_boost", MODE_PRIVATE).edit().putBoolean("recording", false).putBoolean("capture_active", false).apply()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
