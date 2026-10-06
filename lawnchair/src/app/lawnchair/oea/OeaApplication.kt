package app.lawnchair.oea

import android.app.Application

/**
 * OEA-owned application process.
 *
 * The native OEA HOME runtime does not initialize Launcher3/Lawnchair as its
 * launcher application. Legacy Lawnchair code remains available during the
 * migration only so individual features can be ported without changing the
 * OEA runtime owner.
 */
class OeaApplication : Application()
