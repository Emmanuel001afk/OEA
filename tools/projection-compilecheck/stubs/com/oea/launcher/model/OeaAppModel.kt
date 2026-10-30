// Compile-check stub. NOT part of the app build.
// Signature-compatible copy of OeaAppInfo (oea/src/main/java/com/oea/launcher/model/OeaAppModel.kt).
package com.oea.launcher.model

data class OeaAppInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val category: Int = -1,
    val system: Boolean = false,
    val stamp: Long = 0L
)
