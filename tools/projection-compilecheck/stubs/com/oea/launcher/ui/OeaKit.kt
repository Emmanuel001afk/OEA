// Compile-check stub. NOT part of the app build.
// Signature-compatible subset of OeaKit used by the settings integration.
package com.oea.launcher.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

class OeaKit(context: Context) {
    class Page(val content: LinearLayout)
    fun sectionTitle(parent: ViewGroup, title: String) {}
    fun note(parent: ViewGroup, value: String) {}
    fun row(
        group: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String? = null,
        value: String? = null,
        trailing: View? = null,
        chevron: Boolean = true,
        iconBg: Int = 0,
        iconTint: Int = 0,
        onClick: (() -> Unit)? = null
    ): LinearLayout = group
    fun switchRow(
        group: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {}
    fun confirm(
        title: String,
        message: String,
        positive: String,
        destructive: Boolean = false,
        negative: String = "Cancel",
        onConfirm: () -> Unit
    ) {}
}
