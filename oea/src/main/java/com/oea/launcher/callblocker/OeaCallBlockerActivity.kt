package com.oea.launcher.callblocker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Standalone Call Blocker UI backed by OEA's call screening and in-call protection rules. */
class OeaCallBlockerActivity : Activity() {
    private lateinit var content: LinearLayout
    private var selectedTab = "blocked"

    override fun onCreate(state: Bundle?) {
        com.oea.launcher.fault.OeaFaultShield.guard("call-blocker:create") {
            super.onCreate(state)
            selectedTab = state?.getString("tab") ?: intent.getStringExtra("tab") ?: "blocked"
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-arm the daily cleanup alarm (alarms do not survive reboots, and
        // re-arming is cheap) and apply the retention now — opening the
        // blocker screen is a natural moment to drop expired entries.
        runCatching { OeaCallBlockRules.ensureDailyCleanup(this) }
        runCatching { OeaCallBlockRules.pruneHistory(this) }
        if (::content.isInitialized) render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("tab", selectedTab)
        super.onSaveInstanceState(out)
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFF0C0F15.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@OeaCallBlockerActivity).apply {
                text = "Call Blocker"
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFF5F7FB.toInt())
            })
            addView(TextView(this@OeaCallBlockerActivity).apply {
                text = "Stop unwanted calls. Keep important people reachable."
                textSize = 12f
                setTextColor(0xFF9AA6B8.toInt())
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val screeningReady = roleHeld()
        val dialerReady = dialerRoleHeld()
        val anyRoleReady = screeningReady || dialerReady
        header.addView(
            button(if (screeningReady) "Ready" else "Setup", !screeningReady) { requestRole() },
            LinearLayout.LayoutParams(dp(86), dp(40))
        )
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val enabled = OeaCallBlockRules.enabled(this)
        val totalRules = OeaCallBlockRules.getExact(this).size +
            OeaCallBlockRules.getPrefix(this).size +
            OeaCallBlockRules.getSuffix(this).size
        root.addView(TextView(this).apply {
            text = when {
                !anyRoleReady -> "○ Setup required · tap Setup to enable Android call screening"
                enabled && screeningReady -> "● Protection active · Call screening enabled ($totalRules rules)"
                enabled && dialerReady -> "● Protection active via OEA Default Phone · tap Setup for full screening"
                else -> "● Protection paused · tap Blocking below to resume"
            }
            textSize = 13f
            setTextColor(if (anyRoleReady && enabled) 0xFF7BE0B1.toInt() else 0xFFFFC477.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF171E29.toInt(), 16)
            setOnClickListener {
                if (!screeningReady) requestRole()
                else {
                    OeaCallBlockRules.setEnabled(this@OeaCallBlockerActivity, !enabled)
                    render()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(10))
        }
        listOf("blocked" to "Blocked", "allowed" to "Allowed", "rules" to "Rules", "history" to "History").forEach { (key, label) ->
            nav.addView(
                button(label, key == selectedTab) { selectedTab = key; render() },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) }
            )
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(12))
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        when (selectedTab) {
            "blocked" -> renderBlocked()
            "allowed" -> renderAllowed()
            "rules" -> renderRules()
            "history" -> renderHistory()
            else -> {
                selectedTab = "blocked"
                renderBlocked()
            }
        }
    }

    private fun renderBlocked() {
        section("PROTECTION")
        val enabled = OeaCallBlockRules.enabled(this)
        row(
            if (enabled) "Blocking is ON" else "Blocking is OFF",
            if (!roleHeld() && !dialerRoleHeld()) {
                "Tap to toggle protection and grant Android call-screening access."
            } else {
                "Tap to ${if (enabled) "pause" else "enable"} incoming-call rejection."
            }
        ) {
            val next = !enabled
            OeaCallBlockRules.setEnabled(this, next)
            if (next && !roleHeld()) requestRole() else render()
        }
        row(
            if (OeaCallBlockRules.blockUnknown(this)) "Block private / unknown callers: ON" else "Block private / unknown callers: OFF",
            "Rejects callers whose number is hidden, restricted, or unavailable."
        ) {
            val next = !OeaCallBlockRules.blockUnknown(this)
            OeaCallBlockRules.setBlockUnknown(this, next)
            if (next) OeaCallBlockRules.setEnabled(this, true)
            render()
        }
        if (!roleHeld()) {
            action("Enable OEA Call Screening role", true) { requestRole() }
        }
        section("QUICK BLOCK")
        action("＋ Block a number", true) { addRule("exact") }
        action("＋ Block from recent calls", false) { chooseRecentCallToBlock() }
        action("＋ Block from contacts", false) { chooseContact(forBlock = true) }

        section("EXEMPTIONS")
        val contactsPermission = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        row(
            "Allow saved contacts",
            when {
                OeaCallBlockRules.allowContacts(this) && !contactsPermission ->
                    "On · Contacts permission missing, so this exemption is not active yet"
                OeaCallBlockRules.allowContacts(this) ->
                    "On · saved contacts bypass prefix/suffix rules (exact blocked numbers still block)"
                else ->
                    "Off · all callers are checked against your block rules"
            }
        ) {
            val enabledNow = OeaCallBlockRules.allowContacts(this)
            if (!enabledNow && !contactsPermission) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            } else {
                OeaCallBlockRules.setAllowContacts(this, !enabledNow)
                render()
            }
        }
        row(
            "Allow starred contacts",
            when {
                OeaCallBlockRules.allowStarred(this) && !contactsPermission ->
                    "On · Contacts permission missing, so this exemption is not active yet"
                OeaCallBlockRules.allowStarred(this) ->
                    "On · starred contacts bypass prefix/suffix rules"
                else ->
                    "Off · starred contacts are checked against block rules"
            }
        ) {
            val enabledNow = OeaCallBlockRules.allowStarred(this)
            if (!enabledNow && !contactsPermission) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            } else {
                OeaCallBlockRules.setAllowStarred(this, !enabledNow)
                render()
            }
        }
        if (!contactsPermission) {
            action("Grant contacts access", false) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            }
        }
        section("OVERVIEW")
        row(
            "Blocking rules",
            "${OeaCallBlockRules.getExact(this).size} exact · ${OeaCallBlockRules.getPrefix(this).size} prefix · ${OeaCallBlockRules.getSuffix(this).size} suffix"
        ) { selectedTab = "rules"; render() }
        row(
            "Allowed numbers",
            "${OeaCallBlockRules.getAllowList(this).size} exempt numbers"
        ) { selectedTab = "allowed"; render() }
        row(
            "Blocked history",
            "${OeaCallBlockRules.blockedHistory(this).size} saved calls"
        ) { selectedTab = "history"; render() }
    }

    private fun renderAllowed() {
        section("ALWAYS ALLOWED")
        text("Numbers here bypass every OEA block rule.")
        val values = OeaCallBlockRules.getAllowList(this).sorted()
        if (values.isEmpty()) empty("No numbers added yet.")
        values.forEach { value ->
            row(value, "Tap to remove from the allow-list") {
                AlertDialog.Builder(this)
                    .setTitle("Remove allowed number?")
                    .setMessage(value)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) - value)
                        render()
                    }
                    .show()
            }
        }
        action("＋ Add allowed number", true) { addRule("allow") }
        action("＋ Add from contacts", false) { chooseContact(forBlock = false) }
    }

    private fun renderRules() {
        section("MATCH RULES")
        text("Exact rules match both local (080…) and country-code (+234…) formats automatically. Prefix and suffix rules match 1–5 digits.")
        action("＋ Block from recent calls", true) { chooseRecentCallToBlock() }
        action("＋ Block from contacts", false) { chooseContact(forBlock = true) }
        ruleGroup("Exact numbers", OeaCallBlockRules.getExact(this).sorted(), "exact")
        ruleGroup("Prefixes · up to 5 digits", OeaCallBlockRules.getPrefix(this).sorted(), "prefix")
        ruleGroup("Suffixes · up to 5 digits", OeaCallBlockRules.getSuffix(this).sorted(), "suffix")
    }

    private fun ruleGroup(title: String, values: List<String>, type: String) {
        section(title.uppercase())
        if (values.isEmpty()) empty("No rules yet.")
        values.forEach { value ->
            row(value, "Active rule · tap to remove") {
                AlertDialog.Builder(this)
                    .setTitle("Remove rule?")
                    .setMessage(value)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                        val prefix = OeaCallBlockRules.getPrefix(this).toMutableSet()
                        val suffix = OeaCallBlockRules.getSuffix(this).toMutableSet()
                        when (type) {
                            "exact" -> exact.remove(value)
                            "prefix" -> prefix.remove(value)
                            else -> suffix.remove(value)
                        }
                        OeaCallBlockRules.setRules(this, exact, prefix, suffix)
                        render()
                    }
                    .show()
            }
        }
        action("＋ Add ${type} rule", false) { addRule(type) }
    }

    private fun renderHistory() {
        section("SCREENING HEALTH")
        // The honest diagnostic for "blocking stopped": if calls came in but
        // there is no recent screening event, the system never invoked OEA
        // (role lost, or the manufacturer's auto-launch blocked it) — a
        // different problem from rules not matching, and visible right here.
        val lastScreened = OeaCallBlockRules.lastScreenedAt(this)
        val lastScreenedText = if (lastScreened <= 0L) "never recorded" else
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastScreened))
        row(
            "Screening service",
            when {
                roleHeld() -> "Call screening role held · last invoked $lastScreenedText"
                dialerRoleHeld() -> "OEA is the default Phone app · last screening event $lastScreenedText"
                else -> "No screening role · Android will not invoke OEA for calls · last event $lastScreenedText"
            }
        ) {
            AlertDialog.Builder(this)
                .setTitle("Why blocking can stop")
                .setMessage(
                    "Android only calls OEA's screening service while OEA holds the Call " +
                        "Screening role (or is the default Phone app). If calls ring through and " +
                        "'last invoked' above is old, the system is not calling OEA at all:\n\n" +
                        "• Another app may have taken the screening role — tap Setup to take it back.\n" +
                        "• XOS-class phones can also refuse to start OEA for a call: check " +
                        "Settings › Apps › OEA › Auto-launch / 'Display pop-up windows while running " +
                        "in the background', and battery optimization.\n\n" +
                        "If screening IS being invoked, the log below shows every decision OEA made " +
                        "and why. History size never affects blocking — rules and history are stored " +
                        "separately."
                )
                .setPositiveButton("Setup") { _, _ -> requestRole() }
                .setNegativeButton("Close", null)
                .show()
        }
        val decisions = OeaCallBlockRules.recentDecisions(this)
        if (decisions.isEmpty()) {
            empty("No screening decisions recorded yet. After the next screened call, every decision OEA made appears here with its reason.")
        } else {
            decisions.take(8).forEach { d ->
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.timestamp))
                row(if (d.blocked) "Blocked · ${d.number}" else "Allowed · ${d.number}", "${d.reason} · $date")
            }
        }

        section("HISTORY RETENTION")
        val current = OeaCallBlockRules.retention(this)
        OeaCallBlockRules.RETENTIONS.forEach { (id, label) ->
            row(label, if (id == current) "Selected" else "") {
                OeaCallBlockRules.setRetention(this, id)
                render()
            }
        }
        row("Delete old entries now", "Applies the selected retention immediately") {
            val removed = OeaCallBlockRules.pruneHistory(this)
            android.widget.Toast.makeText(this, if (removed > 0) "$removed old entries deleted" else "Nothing to delete yet", android.widget.Toast.LENGTH_SHORT).show()
            render()
        }

        section("RECENTLY BLOCKED")
        val history = OeaCallBlockRules.blockedHistory(this)
        if (history.isEmpty()) empty("No blocked calls recorded yet. Entries appear here when OEA rejects matching calls.")
        history.forEach { call ->
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(call.timestamp))
            row(call.number, "Blocked · $date") {
                AlertDialog.Builder(this)
                    .setTitle(call.number)
                    .setItems(arrayOf("Always allow this number", "Call this number")) { _, which ->
                        if (which == 0) {
                            OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) + call.number)
                            selectedTab = "allowed"
                            render()
                        } else {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", call.number, null)))
                            }
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
        if (history.isNotEmpty()) {
            action("Clear history", false) {
                AlertDialog.Builder(this)
                    .setTitle("Clear blocked history?")
                    .setMessage("Only OEA's saved history will be removed; your rules stay unchanged.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        OeaCallBlockRules.clearBlockedHistory(this)
                        render()
                    }
                    .show()
            }
        }
    }

    private fun addRule(type: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        val input = EditText(this).apply {
            hint = when (type) {
                "allow" -> "Phone number (e.g. 08031234567)"
                "prefix" -> "Starting 1–5 digits (e.g. 0803)"
                "suffix" -> "Ending 1–5 digits (e.g. 4567)"
                else -> "Phone number (e.g. 08031234567)"
            }
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(this)
            .setTitle(
                when (type) {
                    "allow" -> "Add allowed number"
                    "exact" -> "Block exact number"
                    "prefix" -> "Block number prefix"
                    else -> "Block number suffix"
                }
            )
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().filter(Char::isDigit)
                val value = if (type == "prefix" || type == "suffix") raw.take(5) else raw.takeLast(15)
                if (value.isEmpty()) {
                    toast("Enter a valid number or 1–5 digit rule.")
                    return@setPositiveButton
                }
                if (type == "allow") {
                    OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) + value)
                } else {
                    val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                    val prefix = OeaCallBlockRules.getPrefix(this).toMutableSet()
                    val suffix = OeaCallBlockRules.getSuffix(this).toMutableSet()
                    when (type) {
                        "exact" -> exact.add(value)
                        "prefix" -> prefix.add(value)
                        else -> suffix.add(value)
                    }
                    OeaCallBlockRules.setRules(this, exact, prefix, suffix)
                    OeaCallBlockRules.setEnabled(this, true)
                }
                selectedTab = if (type == "allow") "allowed" else "rules"
                if (type != "allow" && !roleHeld() && !dialerRoleHeld()) {
                    toast("Rule saved. Grant Call Screening access so Android routes incoming calls through OEA.")
                    requestRole()
                } else {
                    toast("Rule saved.")
                    render()
                }
            }
            .show()
    }

    private fun chooseRecentCallToBlock() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG)
            return
        }
        val labels = mutableListOf<String>()
        val numbers = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && numbers.size < 60) {
                    val raw = cursor.getString(0).orEmpty().trim()
                    val digits = raw.filter(Char::isDigit).takeLast(15)
                    if (digits.isEmpty() || !seen.add(digits)) continue
                    val name = cursor.getString(1).orEmpty().trim()
                    labels += if (name.isNotEmpty()) "$name ($raw)" else raw
                    numbers += digits
                }
            }
        }
        if (numbers.isEmpty()) {
            toast("No recent calls found.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Block a recent caller")
            .setItems(labels.toTypedArray()) { _, which ->
                val number = numbers.getOrNull(which).orEmpty()
                if (number.isNotEmpty()) {
                    val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                    exact.add(number)
                    OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
                    OeaCallBlockRules.setEnabled(this, true)
                    toast("Blocked $number")
                    selectedTab = "rules"
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseContact(forBlock: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            return
        }
        val names = mutableListOf<String>()
        val numbers = mutableListOf<String>()
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && names.size < 300) {
                    val name = cursor.getString(0).orEmpty().ifBlank { "Unnamed contact" }
                    val num = cursor.getString(1).orEmpty().trim()
                    if (num.isNotEmpty()) {
                        names += "$name ($num)"
                        numbers += num
                    }
                }
            }
        }
        if (names.isEmpty()) {
            toast("No contacts found.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (forBlock) "Block a contact" else "Allow a contact")
            .setItems(names.toTypedArray()) { _, which ->
                val number = numbers.getOrNull(which).orEmpty().filter(Char::isDigit).takeLast(15)
                if (number.isNotEmpty()) {
                    if (forBlock) {
                        val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                        exact.add(number)
                        OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
                        OeaCallBlockRules.setEnabled(this, true)
                        toast("Blocked $number")
                        selectedTab = "rules"
                    } else {
                        OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) + number)
                        selectedTab = "allowed"
                    }
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true) {
                if (manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    OeaCallBlockRules.setEnabled(this, true)
                    toast("OEA Call Screening is active.")
                    render()
                } else {
                    runCatching {
                        startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQUEST_ROLE)
                    }.onFailure { openSettings() }
                }
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

    private fun dialerRoleHeld(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
        }
    }.getOrDefault(false)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE) {
            if (roleHeld()) {
                OeaCallBlockRules.setEnabled(this, true)
                toast("OEA Call Blocker is ready.")
            } else {
                toast("Call-screening role not granted yet.")
            }
            render()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS) render()
        if (requestCode == REQUEST_CALL_LOG && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            chooseRecentCallToBlock()
        }
    }

    private fun section(label: String) {
        content.addView(TextView(this).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF8F9CAF.toInt())
            setPadding(dp(4), dp(12), dp(4), dp(7))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun text(value: String) {
        content.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(0xFFB7C1D0.toInt())
            setPadding(dp(4), 0, dp(4), dp(10))
        })
    }

    private fun row(title: String, subtitle: String, action: () -> Unit = {}) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF171E29.toInt(), 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFF1F4F8.toInt())
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(0xFF9AA6B8.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun empty(value: String) {
        content.addView(TextView(this).apply {
            text = value
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9AA6B8.toInt())
            setPadding(dp(18), dp(24), dp(18), dp(24))
            background = rounded(0xFF171E29.toInt(), 16)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun action(label: String, primary: Boolean, callback: () -> Unit) {
        content.addView(button(label, primary, callback), LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })
    }

    private fun button(label: String, primary: Boolean, callback: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(0xFFF5F7FB.toInt())
        background = rounded(if (primary) 0xFF247D68.toInt() else 0xFF242D3B.toInt(), 14)
        setOnClickListener { callback() }
    }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_ROLE = 9101
        private const val REQUEST_CONTACTS = 9102
        private const val REQUEST_CALL_LOG = 9103
    }
}
