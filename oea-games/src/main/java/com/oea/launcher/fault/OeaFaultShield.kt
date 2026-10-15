package com.oea.launcher.fault

object OeaFaultShield {
    @JvmStatic
    fun note(tag: String, t: Throwable?) {
        // no-op in standalone game builds to avoid circular dependency on :oea
    }

    @JvmStatic
    fun note(tag: String, message: String?) {
        // no-op
    }

    @JvmStatic
    fun guard(tag: String, block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    @JvmStatic
    fun <T> guard(tag: String, default: T, block: () -> T): T {
        return try { block() } catch (_: Throwable) { default }
    }
}
