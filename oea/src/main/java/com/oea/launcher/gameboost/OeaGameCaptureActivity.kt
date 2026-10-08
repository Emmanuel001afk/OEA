package com.oea.launcher.gameboost

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class OeaGameCaptureActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_RECORD = "record"
        private const val REQ = 7401
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ || resultCode != RESULT_OK || data == null) {
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
        }
        finish()
    }
}
