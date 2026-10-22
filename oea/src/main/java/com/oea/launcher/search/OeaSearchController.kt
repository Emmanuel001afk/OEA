package com.oea.launcher.search

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import com.oea.launcher.R
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * One search result that is not an app: a calculation, a settings page, a
 * contact or a web/app search. [copyText] makes the row copy instead of open.
 */
data class OeaSearchAction(
    val title: String,
    val subtitle: String,
    val intent: Intent?,
    val iconRes: Int = R.drawable.oic_search,
    val copyText: String? = null,
    val kind: Kind = Kind.WEB
) {
    enum class Kind { CALCULATOR, SETTING, CONTACT, WEB }
}

class OeaSearchController(private val context: Context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaSearch").apply { isDaemon = true }
    }
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var generation = 0

    /**
     * Contacts, settings, calculator and web results for [query], computed on
     * a worker thread (contacts queries and package resolves used to run on
     * the UI thread for every keystroke). Only the newest request delivers;
     * [callback] runs on the main thread.
     */
    fun actionsAsync(query: String, callback: (String, List<OeaSearchAction>) -> Unit) {
        val gen = ++generation
        runCatching {
            worker.execute {
                if (gen != generation) return@execute
                val result = runCatching { actions(query) }.getOrDefault(emptyList())
                main.post { if (gen == generation) callback(query, result) }
            }
        }
    }

    /** Apps can be installed between searches; forget which intents resolve. */
    fun clearResolveCache() = resolveCache.clear()

    private data class SettingEntry(val title: String, val action: String, val keywords: List<String>, val icon: Int)

    private val settings = listOf(
        SettingEntry("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "wi-fi", "wireless", "internet", "network"), R.drawable.oic_wifi),
        SettingEntry("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth", "pair", "headphones"), R.drawable.oic_settings),
        SettingEntry("Mobile data", Settings.ACTION_DATA_ROAMING_SETTINGS, listOf("data", "mobile", "sim", "roaming", "apn"), R.drawable.oic_phone_android),
        SettingEntry("Hotspot & tethering", "android.settings.TETHER_SETTINGS", listOf("hotspot", "tether", "share internet"), R.drawable.oic_wifi),
        SettingEntry("Display", Settings.ACTION_DISPLAY_SETTINGS, listOf("display", "brightness", "screen", "dark", "font", "timeout"), R.drawable.oic_contrast),
        SettingEntry("Sound & vibration", Settings.ACTION_SOUND_SETTINGS, listOf("sound", "volume", "ringtone", "vibrat", "silent"), R.drawable.oic_music_note),
        SettingEntry("Battery", Intent.ACTION_POWER_USAGE_SUMMARY, listOf("battery", "power", "saver", "charging"), R.drawable.oic_battery_full),
        SettingEntry("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("storage", "space", "memory", "files"), R.drawable.oic_memory),
        SettingEntry("Apps", Settings.ACTION_APPLICATION_SETTINGS, listOf("apps", "applications", "permissions"), R.drawable.oic_apps),
        // "default" and "home" used to land on the general Apps list, which is not
        // where Android changes the launcher. These are the two pages that actually
        // do it, so a search for either word shows the screen the user is looking for.
        SettingEntry("Default Home app", Settings.ACTION_HOME_SETTINGS, listOf("default", "home", "home app", "home screen", "launcher", "default launcher"), R.drawable.oic_home),
        SettingEntry("Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, listOf("default", "default apps", "default home", "home app", "launcher", "browser", "dialer"), R.drawable.oic_apps),
        SettingEntry("Notifications", "android.settings.NOTIFICATION_SETTINGS", listOf("notification", "alerts", "badges"), R.drawable.oic_notifications),
        SettingEntry("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("location", "gps"), R.drawable.oic_map),
        SettingEntry("Security & privacy", Settings.ACTION_SECURITY_SETTINGS, listOf("security", "lock", "fingerprint", "face", "password", "privacy"), R.drawable.oic_shield),
        SettingEntry("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("accessibility", "talkback", "assistant"), R.drawable.oic_accessibility_new),
        SettingEntry("Date & time", Settings.ACTION_DATE_SETTINGS, listOf("date", "time", "clock", "timezone"), R.drawable.oic_schedule),
        SettingEntry("Language & input", Settings.ACTION_LOCALE_SETTINGS, listOf("language", "keyboard", "input", "locale"), R.drawable.oic_text_fields),
        SettingEntry("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("airplane", "flight", "aeroplane"), R.drawable.oic_settings),
        SettingEntry("NFC", Settings.ACTION_NFC_SETTINGS, listOf("nfc", "tap to pay", "contactless"), R.drawable.oic_settings),
        SettingEntry("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("developer", "usb debugging", "freeform"), R.drawable.oic_developer_mode),
        SettingEntry("Wallpaper", Intent.ACTION_SET_WALLPAPER, listOf("wallpaper", "background"), R.drawable.oic_wallpaper),
        SettingEntry("Android settings", Settings.ACTION_SETTINGS, listOf("settings", "system", "phone settings"), R.drawable.oic_settings)
)

    fun actions(query: String): List<OeaSearchAction> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val result = mutableListOf<OeaSearchAction>()
        calculate(q)?.let { value ->
            result += OeaSearchAction("= $value", "$q • tap to copy", null, R.drawable.oic_calculate, value, OeaSearchAction.Kind.CALCULATOR)
        }
        result += settingsMatches(q)
        result += contacts(q)
        result += webActions(q)
        return result
    }

    private fun settingsMatches(q: String): List<OeaSearchAction> {
        if (q.length < 2) return emptyList()
        val lower = q.lowercase()
        return settings.filter { entry ->
            entry.title.lowercase().contains(lower) || entry.keywords.any { it.startsWith(lower) || lower.startsWith(it) }
        }.take(3).mapNotNull { entry ->
            val intent = Intent(entry.action)
            if (!canResolve(intent)) return@mapNotNull null
            OeaSearchAction(entry.title, "Android settings", intent, entry.icon, kind = OeaSearchAction.Kind.SETTING)
        }
    }

    private fun contacts(q: String): List<OeaSearchAction> {
        if (q.length < 2) return emptyList()
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(q).build()
        val out = mutableListOf<OeaSearchAction>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.HAS_PHONE_NUMBER),
                null, null, ContactsContract.Contacts.SORT_KEY_PRIMARY
)?.use { cursor ->
                while (cursor.moveToNext() && out.size < 4) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: continue
                    val hasPhone = cursor.getInt(2) > 0
                    val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
                    out += OeaSearchAction(
                        name, if (hasPhone) "Contact • tap to open" else "Contact",
                        Intent(Intent.ACTION_VIEW, contactUri), R.drawable.oic_person, kind = OeaSearchAction.Kind.CONTACT
)
                }
            }
        }
        return out
    }

    private fun webActions(q: String): List<OeaSearchAction> {
        val encoded = Uri.encode(q)
        val result = mutableListOf<OeaSearchAction>()
        result += OeaSearchAction("Search the web", q, Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q), R.drawable.oic_public)
        result += OeaSearchAction("Search Play Store", q, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded")), R.drawable.oic_apps)
        result += OeaSearchAction("Search YouTube", q, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")), R.drawable.oic_play_arrow)
        result += OeaSearchAction("Search Maps", q, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")), R.drawable.oic_map)
        result += OeaSearchAction("Search Spotify", q, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")), R.drawable.oic_music_note)
        val resolvable = result.filter { it.intent != null && canResolve(it.intent) }.toMutableList()
        if (resolvable.none { it.title == "Search the web" }) {
            // No ACTION_WEB_SEARCH handler: fall back to the browser.
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded"))
            if (canResolve(browser)) resolvable.add(0, OeaSearchAction("Search the web", q, browser, R.drawable.oic_public))
        }
        return resolvable
    }

    private fun canResolve(intent: Intent): Boolean {
        // Resolution depends on the action, scheme and host, not on the search text.
        val key = "${intent.action}|${intent.data?.scheme}|${intent.data?.host}|${intent.component}"
        return resolveCache.getOrPut(key) {
            runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)
        }
    }

    /** Evaluates + − × ÷ % ^ and parentheses; null when [input] is not arithmetic. */
    fun calculate(input: String): String? {
        val expr = input.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.').replace(" ", "")
        if (expr.length < 3 || expr.none { it in "+-*/%^" } || expr.any { !(it.isDigit() || it in "+-*/%^().") }) return null
        if (!expr.any(Char::isDigit)) return null
        val parser = Parser(expr)
        val value = runCatching { parser.parse() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        val decimal = BigDecimal(value).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val text = if (decimal.scale() <= 0) decimal.toBigInteger().toString() else decimal.toPlainString()
        return if (text == expr) null else text
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expression()
            if (pos != s.length) throw IllegalArgumentException("Unexpected input")
            return v
        }

        private fun expression(): Double {
            var v = term()
            while (pos < s.length) {
                when (s[pos]) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (pos < s.length) {
                when (s[pos]) {
                    '*' -> { pos++; v *= factor() }
                    '/' -> { pos++; v /= factor() }
                    '%' -> {
                        pos++
                        // "50%" alone is a percentage; "7%3" is modulo.
                        v = if (pos >= s.length || s[pos] in "+-*/)") v / 100.0 else v % factor()
                    }
                    else -> return v
                }
            }
            return v
        }

        private fun factor(): Double {
            val base = unary()
            if (pos < s.length && s[pos] == '^') {
                pos++
                return Math.pow(base, factor())
            }
            return base
        }

        private fun unary(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -unary() }
            if (pos < s.length && s[pos] == '+') { pos++; return unary() }
            return primary()
        }

        private fun primary(): Double {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = expression()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing )")
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Number expected")
            return s.substring(start, pos).toDouble()
        }
    }
}
