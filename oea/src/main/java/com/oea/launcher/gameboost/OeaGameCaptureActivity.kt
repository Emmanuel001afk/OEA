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
        // This Activity is only the host for Android's MediaProjection consent UI.
        // Keep it a real, focusable translucent Activity until the user answers.
        // The system consent dialog must not be launched from a disposable
        // trampoline, otherwise Android/OEM task handling can expose OEA instead
        // of the game underneath.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0f)
        window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        overridePendingTransition(0, 0)
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(manager.createScreenCaptureIntent(), REQ) }.onFailure {
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
