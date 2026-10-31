package com.oea.launcher.icons

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaTheme

/**
 * Per-app icon picker, opened from an app's long-press menu ("Change icon").
 *
 * Offers, for one launcher component:
 *  - reset to the default look (pack + shape as configured everywhere),
 *  - "system": the app's own real icon even when a pack is selected,
 *  - every icon of the selected icon pack (pinned to that pack),
 *  - a picture from the gallery, privately stored and shape-masked.
 *
 * The choice is cosmetic only — OEA keeps launching the same component.
 */
class OeaIconPickerActivity : Activity() {

    private lateinit var kit: OeaKit
    private lateinit var packageName: String
    private var className: String? = null
    private var label: String = "App"

    /** A freshly stored picture that has not been applied yet (cleaned up on back-out). */
    private var pendingImage: String? = null

    private var shownNames = 0
    private var allNames: List<String> = emptyList()
    private var gridBox: LinearLayout? = null
    private var gridRow: LinearLayout? = null
    private var columnsUsed = 0
    private var moreButton: View? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        className = intent.getStringExtra(EXTRA_CLASS)
        label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "App" }
        if (packageName.isBlank()) {
            finish()
            return
        }
        kit = OeaKit(this, OeaTheme.resolve(this))
        val page = kit.page(
            "Change icon",
            "Choose how $label looks on your Home screen. This only changes the picture — the app itself keeps opening exactly as before.",
            onBack = { finish() }
        )
        val box = page.content

        kit.sectionTitle(box, "Current")
        val group = kit.group(box)
        val override = OeaIconOverrides.spec(this, packageName, className)
        val controller = OeaIconController.shared(this)
        val currentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(14))
        }
        currentRow.addView(
            ImageView(this).apply {
                setImageDrawable(controller.icon(packageName, className))
            },
            LinearLayout.LayoutParams(kit.dp(52), kit.dp(52))
        )
        currentRow.addView(
            kit.text(
                when {
                    override == null -> "Default look (your icon pack and shape apply)"
                    override == OeaIconOverrides.SYSTEM -> "The app's own real icon"
                    override.startsWith("pack:") -> "A hand-picked icon from your icon pack"
                    override.startsWith("image:") -> "A picture you chose"
                    else -> "Default look"
                },
                14.5f,
                kit.palette.textSecondary
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = kit.dp(16)
            }
        )
        group.addView(currentRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (override != null) {
            kit.row(group, R.drawable.oic_restart_alt, "Reset to default", "Follow your icon pack and shape again") {
                applyOverride(null)
            }
        }
        if (override != OeaIconOverrides.SYSTEM) {
            kit.row(group, R.drawable.oic_phone_android, "Use the app's own icon", "Ignores the icon pack for $label only") {
                applyOverride(OeaIconOverrides.SYSTEM)
            }
        }

        val pack = OeaDataStore.get(this).iconPackPackage()
        kit.sectionTitle(box, "From your icon pack")
        if (pack.isNullOrBlank()) {
            kit.note(box, "No icon pack is selected. Pick one to choose from its icons here.")
            kit.row(kit.group(box), R.drawable.oic_palette, "Open Appearance settings", "Select an icon pack first") {
                runCatching {
                    startActivity(
                        Intent(this, OeaSystemToolsActivity::class.java)
                            .putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "appearance")
                    )
                }
            }
        } else {
            allNames = controller.packIconNames(pack)
            if (allNames.isEmpty()) {
                kit.note(box, "The selected pack does not list any icons to choose from.")
            } else {
                kit.note(box, "Tap an icon to use it for $label. The choice stays with this app even if you switch packs later.")
                gridBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                box.addView(gridBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                moreButton = kit.tonalButton("Show more icons") { showMore() }
                box.addView(
                    moreButton,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = kit.dp(10)
                    }
                )
                showMore()
            }
        }

        kit.sectionTitle(box, "Your own picture")
        kit.row(kit.group(box), R.drawable.oic_photo_library, "Choose from gallery", "Cropped to a square and masked by your icon shape") {
            runCatching {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQUEST_GALLERY)
            }.onFailure {
                Toast.makeText(this, "No gallery app is available to pick a picture", Toast.LENGTH_SHORT).show()
            }
        }
        kit.note(box, "Pictures are stored inside OEA's private storage. OEA never reads anything else from your gallery.")

        setContentView(page.root)
    }

    /** Adds the next batch of pack icons; keeps the first paint quick on low-RAM phones. */
    private fun showMore() {
        val grid = gridBox ?: return
        val pack = OeaDataStore.get(this).iconPackPackage() ?: return
        val batch = allNames.drop(shownNames).take(BATCH)
        shownNames += batch.size
        val controller = OeaIconController.shared(this)
        batch.forEach { name ->
            if (columnsUsed == 0) {
                gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(gridRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            gridRow?.addView(
                ImageView(this).apply {
                    setImageDrawable(packDrawable(pack, name))
                    background = kit.ripple(null, 18)
                    setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8))
                    contentDescription = name
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { applyOverride("pack:$pack:$name") }
                },
                LinearLayout.LayoutParams(0, kit.dp(54), 1f)
            )
            columnsUsed = (columnsUsed + 1) % COLUMNS
        }
        moreButton?.visibility = if (shownNames >= allNames.size) View.GONE else View.VISIBLE
    }

    private fun packDrawable(pack: String, name: String) = runCatching {
        val res = packageManager.getResourcesForApplication(pack)
        val id = res.getIdentifier(name, "drawable", pack).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", pack)
        if (id == 0) null else res.getDrawable(id, null)
    }.getOrNull()

    private fun applyOverride(value: String?) {
        OeaIconOverrides.setSpec(this, packageName, className, value)
        OeaIconController.shared(this).invalidateComponent(packageName, className)
        pendingImage = null
        Toast.makeText(
            this,
            if (value == null) "${label}'s icon follows your pack and shape again"
            else "${label}'s icon updated",
            Toast.LENGTH_SHORT
        ).show()
        setResult(RESULT_OK)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_GALLERY) {
            val uri: Uri? = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                val spec = OeaIconOverrides.saveImage(this, packageName, className, uri)
                if (spec == null) {
                    Toast.makeText(this, "That picture could not be read", Toast.LENGTH_SHORT).show()
                } else {
                    pendingImage = spec
                    applyOverride(spec)
                }
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        // A picture that was stored but never applied (user backed out) must
        // not stay on disk, and must never replace the icon that is applied:
        // every pick writes a NEW file, and the old file is only deleted once
        // the new spec is actually saved.
        pendingImage?.let { OeaIconOverrides.deleteUnapplied(this, it) }
        pendingImage = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "oea_pkg"
        const val EXTRA_CLASS = "oea_cls"
        const val EXTRA_LABEL = "oea_label"
        private const val REQUEST_GALLERY = 0x4950
        private const val COLUMNS = 5
        private const val BATCH = 150

        fun intent(host: Context, packageName: String, className: String?, label: String): Intent =
            Intent(host, OeaIconPickerActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_CLASS, className)
                .putExtra(EXTRA_LABEL, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
