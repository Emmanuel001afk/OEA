package app.lawnchair.oea.applock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OeaAppLockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(target, 0)).toString()
        }.getOrDefault(target)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(this@OeaAppLockActivity).apply { text = "$label is locked by OEA"; textSize = 22f; gravity = Gravity.CENTER })
            addView(Button(this@OeaAppLockActivity).apply {
                text = "Unlock for 5 minutes"
                setOnClickListener { OeaAppLockStore.unlockFor(this@OeaAppLockActivity); finish() }
            })
            addView(Button(this@OeaAppLockActivity).apply {
                text = "Back to OEA"
                setOnClickListener { returnHome() }
            })
        })
    }
    override fun onBackPressed() = returnHome()
    private fun returnHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    companion object {
        private const val EXTRA_PACKAGE = "package"
        fun launch(context: Context, packageName: String) =
            context.startActivity(Intent(context, OeaAppLockActivity::class.java).putExtra(EXTRA_PACKAGE, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}
