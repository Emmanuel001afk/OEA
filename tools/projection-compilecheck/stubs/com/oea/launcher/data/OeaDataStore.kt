// Compile-check stub. NOT part of the app build.
// Signature-compatible subset of OeaDataStore used by OeaMultitaskLauncher.
package com.oea.launcher.data

import android.content.Context

object OeaDataStore {
    class Store {
        fun floatingWindowSize(): String = "medium"
    }
    fun get(context: Context): Store = Store()
}
