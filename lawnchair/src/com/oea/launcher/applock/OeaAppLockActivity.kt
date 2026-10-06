package com.oea.launcher.applock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

class OeaAppLockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardSecure) {
            startActivityForResult(km.createConfirmDeviceCredentialIntent("Unlock app", "Confirm your device credential to open this app"), REQUEST_UNLOCK)
        } else {
            finishAndReturnHome()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UNLOCK && resultCode == RESULT_OK) {
            OeaAppLockStore.unlockFor(this)
            finish()
        } else if (requestCode == REQUEST_UNLOCK) {
            finishAndReturnHome()
        }
    }

    override fun onBackPressed() = finishAndReturnHome()

    private fun finishAndReturnHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val REQUEST_UNLOCK = 901
        private const val EXTRA_PACKAGE = "package"
        fun launch(context: Context, packageName: String) =
            context.startActivity(Intent(context, OeaAppLockActivity::class.java).putExtra(EXTRA_PACKAGE, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}
