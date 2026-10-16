package com.oea.launcher.miniapps

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * EMAGE's full-screen picture viewer.
 *
 * It replaces the old 360dp bottom sheet: the picture fills the screen on a
 * black background, swipes left and right move through the album you opened it
 * from, and the actions are one tap away. "Wallpaper" hands the picture to the
 * Themes app, which offers Home, lock screen or both — the same flow a picture
 * from the phone's own gallery uses.
 */
internal object OeaEmageViewer {

    private val io = Executors.newSingleThreadExecutor { Thread(it, "EmageViewer").apply { isDaemon = true } }

    /** Opens [items] at [index] full-screen. [onChanged] runs after a delete or favourite change. */
    fun show(host: Activity, items: List<OeaImageApp.ImageItem>, index: Int, onChanged: () -> Unit) {
        if (items.isEmpty()) return
        val kit = OeaKit(host, com.oea.launcher.ui.OeaTheme.resolve(host))
        val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var position = index.coerceIn(0, items.lastIndex)

        val root = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }
        val picture = ImageView(host).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Picture"
        }
        root.addView(picture, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(kit.dp(8), kit.dp(28), kit.dp(12), kit.dp(8))
        }
        val counter = TextView(host).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        top.addView(kit.circleIconButton(R.drawable.oic_close, "Close", 0x33FFFFFF, Color.WHITE) { dialog.dismiss() },
            LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(10) })
        top.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            setPadding(kit.dp(4), kit.dp(6), kit.dp(4), kit.dp(12))
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        fun action(icon: Int, label: String, onClick: () -> Unit) {
            val cell = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setPadding(kit.dp(6), kit.dp(4), kit.dp(6), kit.dp(4))
                setOnClickListener { onClick() }
            }
            cell.addView(kit.iconView(icon, Color.WHITE, 22))
            cell.addView(TextView(host).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
            })
            bottom.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }

        fun current() = items[position]

        fun load() {
            val item = current()
            counter.text = "${position + 1} / ${items.size}  •  ${item.name}"
            picture.setImageDrawable(null)
            io.execute {
                val bmp = runCatching {
                    host.contentResolver.loadThumbnail(item.uri, Size(1440, 2560), null)
                }.getOrNull()
                host.runOnUiThread {
                    if (!host.isDestroyed && dialog.isShowing && position < items.size && items[position].uri == item.uri) {
                        if (bmp != null) picture.setImageBitmap(bmp) else picture.setImageResource(R.drawable.oic_photo_library)
                    }
                }
            }
        }

        fun move(delta: Int) {
            val next = position + delta
            if (next !in items.indices) return
            position = next
            load()
        }

        action(R.drawable.oic_info, "Details") {
            OeaImageApp.showDetailsFor(host, kit, current())
        }
        action(R.drawable.oic_share_filled, "Share") {
            runCatching {
                host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, current().uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share image"))
            }.onFailure { toast(host, "Nothing can share this picture") }
        }
        action(R.drawable.oic_wallpaper, "Wallpaper") {
            setAsWallpaper(host, current())
        }
        action(R.drawable.oic_edit, "Edit") {
            runCatching {
                host.startActivity(Intent(Intent.ACTION_EDIT).apply {
                    setDataAndType(current().uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                })
            }.onFailure { toast(host, "No editor is installed") }
        }
        action(R.drawable.oic_delete, "Delete") {
            val item = current()
            kit.confirm("Delete this picture?", "It is removed from the device. Android may ask you to confirm.", "Delete", destructive = true) {
                val removed = runCatching { host.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false)
                if (removed) {
                    OeaImageApp.rememberTrash(host, item)
                    onChanged()
                    if (items.size <= 1) {
                        dialog.dismiss()
                    } else {
                        // The album list is the caller's; step to a neighbour so the viewer stays useful.
                        position = position.coerceAtMost(items.lastIndex - 1)
                        load()
                    }
                } else {
                    toast(host, "Android would not delete this picture")
                }
            }
        }

        val detector = GestureDetector(host, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) > 120 && abs(velocityX) > 300) {
                    move(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        picture.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }

        dialog.setContentView(root)
        dialog.show()
        load()
    }

    /** Sends the picture to Themes: "Set as wallpaper" for Home, lock screen or both. */
    fun setAsWallpaper(host: Activity, item: OeaImageApp.ImageItem) {
        runCatching {
            host.startActivity(Intent(host, com.oea.launcher.themes.OeaThemesActivity::class.java).apply {
                action = Intent.ACTION_ATTACH_DATA
                setDataAndType(item.uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { toast(host, "Themes could not be opened") }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

}
