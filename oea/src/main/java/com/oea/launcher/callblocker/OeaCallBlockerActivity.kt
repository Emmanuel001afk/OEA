package com.oea.launcher.callblocker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Standalone Call Blocker UI backed by OEA's existing screening rules. */
class OeaCallBlockerActivity : Activity() {
    private lateinit var content: LinearLayout
    private var selectedTab = "blocked"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedTab = state?.getString("tab") ?: intent.getStringExtra("tab") ?: "blocked"
        render()
    }
    override fun onResume() { super.onResume(); if (::content.isInitialized) render() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("tab", selectedTab); super.onSaveInstanceState(out) }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFF0C0F15.toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@OeaCallBlockerActivity).apply {
                text = "Call Blocker"; textSize = 27f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFF5F7FB.toInt())
            })
            addView(TextView(this@OeaCallBlockerActivity).apply {
                text = "Stop unwanted calls. Keep important people reachable."; textSize = 12f
                setTextColor(0xFF9AA6B8.toInt()); setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(if (roleHeld()) "Ready" else "Setup", true) { requestRole() }, LinearLayout.LayoutParams(dp(82), dp(42)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val held = roleHeld()
        val enabled = OeaCallBlockRules.enabled(this)
        root.addView(TextView(this).apply {
            text = when { !held -> "○ Setup required · enable OEA call screening"; enabled -> "● Protection enabled"; else -> "● Screening ready · protection is off" }
            textSize = 13f; setTextColor(if (held && enabled) 0xFF7BE0B1.toInt() else 0xFFFFC477.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(0xFF171E29.toInt(), 16)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(10)) }
        listOf("blocked" to "Blocked", "allowed" to "Allowed", "rules" to "Rules", "history" to "History").forEach { (key, label) ->
            nav.addView(button(label, key == selectedTab) { selectedTab = key; render() },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(3) })
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(12)) }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        when (selectedTab) {
            "blocked" -> renderBlocked()
            "allowed" -> renderAllowed()
            "rules" -> renderRules()
            "history" -> renderHistory()
            else -> { selectedTab = "blocked"; renderBlocked() }
        }
    }

    private fun renderBlocked() {
        section("PROTECTION")
        val enabled = OeaCallBlockRules.enabled(this)
        row(if (enabled) "Blocking is ON" else "Blocking is OFF",
            if (!roleHeld()) "Tap to set up Android call screening first." else "Tap to ${if (enabled) "pause" else "enable"} matching incoming-call rejection.") {
            if (!roleHeld()) requestRole() else { OeaCallBlockRules.setEnabled(this, !enabled); render() }
        }
        row(if (OeaCallBlockRules.blockUnknown(this)) "Block private / unknown callers: ON" else "Block private / unknown callers: OFF",
            "Applies only to callers whose number is hidden or unavailable. Off by default.") {
            OeaCallBlockRules.setBlockUnknown(this, !OeaCallBlockRules.blockUnknown(this))
            render()
        }
        if (!roleHeld()) action("Choose OEA for call screening", true) { requestRole() }
        section("EXEMPTIONS")
        val contactsPermission = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        row("Allow saved contacts", when {
            OeaCallBlockRules.allowContacts(this) && !contactsPermission -> "On · Contacts permission missing, so this exemption is not active yet"
            OeaCallBlockRules.allowContacts(this) -> "On · saved contacts bypass matching block rules"
            else -> "Off · saved contacts are checked against block rules"
        }) {
            val enabledNow = OeaCallBlockRules.allowContacts(this)
            if (!enabledNow && !contactsPermission) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            else { OeaCallBlockRules.setAllowContacts(this, !enabledNow); render() }
        }
        row("Allow starred contacts", when {
            OeaCallBlockRules.allowStarred(this) && !contactsPermission -> "On · Contacts permission missing, so this exemption is not active yet"
            OeaCallBlockRules.allowStarred(this) -> "On · starred contacts bypass matching rules"
            else -> "Off · starred contacts are checked against block rules"
        }) {
            val enabledNow = OeaCallBlockRules.allowStarred(this)
            if (!enabledNow && !contactsPermission) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            else { OeaCallBlockRules.setAllowStarred(this, !enabledNow); render() }
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            action("Grant contacts access", false) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS) }
        section("OVERVIEW")
        row("Blocking rules", "${OeaCallBlockRules.getExact(this).size} exact · ${OeaCallBlockRules.getPrefix(this).size} prefix · ${OeaCallBlockRules.getSuffix(this).size} suffix") { selectedTab = "rules"; render() }
        row("Allowed numbers", "${OeaCallBlockRules.getAllowList(this).size} exempt numbers") { selectedTab = "allowed"; render() }
        row("Blocked history", "${OeaCallBlockRules.blockedHistory(this).size} saved calls") { selectedTab = "history"; render() }
    }

    private fun renderAllowed() {
        section("ALWAYS ALLOWED")
        text("Numbers here bypass every OEA block rule.")
        val values = OeaCallBlockRules.getAllowList(this).sorted()
        if (values.isEmpty()) empty("No numbers added yet.")
        values.forEach { value -> row(value, "Tap to remove from the allow-list") {
            AlertDialog.Builder(this).setTitle("Remove allowed number?").setMessage(value)
                .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) - value); render()
                }.show()
        } }
        action("＋ Add allowed number", true) { addRule("allow") }
        action("＋ Add from contacts", false) { chooseContact() }
    }

    private fun renderRules() {
        section("MATCH RULES")
        text("Exact rules match the full number. Prefix and suffix rules match the start or end and accept 1–5 digits.")
        ruleGroup("Exact numbers", OeaCallBlockRules.getExact(this).sorted(), "exact")
        ruleGroup("Prefixes · up to 5 digits", OeaCallBlockRules.getPrefix(this).sorted(), "prefix")
        ruleGroup("Suffixes · up to 5 digits", OeaCallBlockRules.getSuffix(this).sorted(), "suffix")
    }

    private fun ruleGroup(title: String, values: List<String>, type: String) {
        section(title.uppercase())
        if (values.isEmpty()) empty("No rules yet.")
        values.forEach { value -> row(value, "Tap to remove this rule") {
            AlertDialog.Builder(this).setTitle("Remove rule?").setMessage(value)
                .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                    val prefix = OeaCallBlockRules.getPrefix(this).toMutableSet()
                    val suffix = OeaCallBlockRules.getSuffix(this).toMutableSet()
                    when (type) { "exact" -> exact.remove(value); "prefix" -> prefix.remove(value); else -> suffix.remove(value) }
                    OeaCallBlockRules.setRules(this, exact, prefix, suffix); render()
                }.show()
        } }
        action("＋ Add rule", false) { addRule(type) }
    }

    private fun renderHistory() {
        section("RECENTLY BLOCKED")
        val history = OeaCallBlockRules.blockedHistory(this)
        if (history.isEmpty()) empty("No blocked calls recorded yet. Entries appear here when OEA rejects matching calls.")
        history.forEach { call ->
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(call.timestamp))
            row(call.number, "Blocked · ${date}") {
                AlertDialog.Builder(this).setTitle(call.number)
                    .setItems(arrayOf("Always allow this number", "Call this number")) { _, which ->
                        if (which == 0) {
                            OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) + call.number)
                            selectedTab = "allowed"; render()
                        } else runCatching { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(call.number)))) }
                    }.setNegativeButton("Close", null).show()
            }
        }
        if (history.isNotEmpty()) action("Clear history", false) {
            AlertDialog.Builder(this).setTitle("Clear blocked history?")
                .setMessage("Only OEA's saved history will be removed; your rules stay unchanged.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> OeaCallBlockRules.clearBlockedHistory(this); render() }.show()
        }
    }

    private fun addRule(type: String) {
        val input = EditText(this).apply {
            hint = if (type == "allow") "Phone number" else if (type == "prefix" || type == "suffix") "1–5 digits" else "Full phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE; setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle(when (type) {
            "allow" -> "Add allowed number"; "exact" -> "Block exact number"
            "prefix" -> "Block number prefix"; else -> "Block number suffix"
        }).setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            val raw = input.text.toString().filter(Char::isDigit)
            val value = if (type == "prefix" || type == "suffix") raw.take(5) else raw.takeLast(15)
            if (value.isEmpty()) { toast("Enter a valid number or 1–5 digit rule."); return@setPositiveButton }
            if (type == "allow") OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) + value)
            else {
                val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                val prefix = OeaCallBlockRules.getPrefix(this).toMutableSet()
                val suffix = OeaCallBlockRules.getSuffix(this).toMutableSet()
                when (type) { "exact" -> exact.add(value); "prefix" -> prefix.add(value); else -> suffix.add(value) }
                OeaCallBlockRules.setRules(this, exact, prefix, suffix)
            }
            if (roleHeld()) OeaCallBlockRules.setEnabled(this, true)
            selectedTab = if (type == "allow") "allowed" else "rules"; render()
        }.show()
    }

    private fun chooseContact() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS); return
        }
        val names = mutableListOf<String>(); val numbers = mutableListOf<String>()
        runCatching {
            contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
                while (cursor.moveToNext() && names.size < 300) {
                    names += cursor.getString(0).orEmpty().ifBlank { "Unnamed contact" }
                    numbers += cursor.getString(1).orEmpty()
                }
            }
        }
        if (names.isEmpty()) { toast("No contacts found."); return }
        AlertDialog.Builder(this).setTitle("Allow a contact").setItems(names.toTypedArray()) { _, which ->
            val number = numbers.getOrNull(which).orEmpty().filter(Char::isDigit).takeLast(15)
            if (number.isNotEmpty()) {
                OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) + number)
                selectedTab = "allowed"; render()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun requestRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true) {
                if (manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    OeaCallBlockRules.setEnabled(this, true); render()
                } else runCatching { startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQUEST_ROLE) }
                    .onFailure { openSettings() }
            } else openSettings()
        } else openSettings()
    }
    private fun openSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { toast("Open Android Settings → Default apps → Call screening.") }
    }
    private fun roleHeld(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
    }.getOrDefault(false)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE) {
            if (roleHeld()) { OeaCallBlockRules.setEnabled(this, true); toast("OEA Call Blocker is ready.") }
            else toast("Call-screening role not granted; blocking remains off.")
            render()
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS) render()
    }

    private fun section(label: String) {
        content.addView(TextView(this).apply {
            text = label; textSize = 11f; letterSpacing = 0.08f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF8F9CAF.toInt()); setPadding(dp(4), dp(12), dp(4), dp(7))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun text(value: String) {
        content.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(0xFFB7C1D0.toInt()); setPadding(dp(4), 0, dp(4), dp(10)) })
    }
    private fun row(title: String, subtitle: String, action: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF171E29.toInt(), 16); isClickable = true; isFocusable = true; setOnClickListener { action() }
        }
        card.addView(TextView(this).apply { text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFF1F4F8.toInt()) })
        card.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(0xFF9AA6B8.toInt()); setPadding(0, dp(4), 0, 0) })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun empty(value: String) {
        content.addView(TextView(this).apply {
            text = value; textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF9AA6B8.toInt())
            setPadding(dp(18), dp(24), dp(18), dp(24)); background = rounded(0xFF171E29.toInt(), 16)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun action(label: String, primary: Boolean, callback: () -> Unit) {
        content.addView(button(label, primary, callback), LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })
    }
    private fun button(label: String, primary: Boolean, callback: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; setTextColor(0xFFF5F7FB.toInt())
        background = rounded(if (primary) 0xFF247D68.toInt() else 0xFF242D3B.toInt(), 14); setOnClickListener { callback() }
    }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_ROLE = 9101; private const val REQUEST_CONTACTS = 9102 }
}
