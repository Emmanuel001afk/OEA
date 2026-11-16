package com.oea.launcher.callblocker

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.LinearLayout
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import java.text.DateFormat
import java.util.Date

/** Standalone Call Blocker UI backed by OEA's call screening and in-call protection rules. */
class OeaCallBlockerActivity : Activity() {
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaAppKit
    private var shell: OeaAppKit.AppShell? = null
    private lateinit var content: LinearLayout
    private var selectedTab = "blocked"

    override fun onCreate(state: Bundle?) {
        OeaFaultShield.guard("call-blocker:create") {
            super.onCreate(state)
            selectedTab = state?.getString("tab") ?: intent.getStringExtra("tab") ?: "blocked"
            buildShell()
        }
    }

    override fun onResume() {
        super.onResume()
        OeaFaultShield.guard("call-blocker:resume") {
            runCatching { OeaCallBlockRules.ensureDailyCleanup(this) }
            runCatching { OeaCallBlockRules.pruneHistory(this) }
            val fresh = OeaTheme.resolve(this)
            if (!::palette.isInitialized || fresh != palette || shell == null) {
                buildShell()
            } else {
                render()
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("tab", selectedTab)
        super.onSaveInstanceState(out)
    }

    private fun buildShell() {
        palette = OeaTheme.resolve(this)
        kit = OeaAppKit(this, palette)
        OeaInsets.edgeToEdge(window, lightBars = !palette.dark)
        val tabLabels = listOf("blocked" to "Blocked", "allowed" to "Allowed", "rules" to "Rules", "history" to "History")
        if (tabLabels.none { it.first == selectedTab }) selectedTab = "blocked"
        val tabIcons = mapOf(
            "blocked" to R.drawable.oic_shield_fill,
            "allowed" to R.drawable.oic_check_circle_fill,
            "rules" to R.drawable.oic_block,
            "history" to R.drawable.oic_history
        )
        val navItems = tabLabels.map { (id, label) ->
            OeaAppKit.NavItem(id, label, tabIcons.getValue(id))
        }
        val built = kit.appShell(
            title = "Call Blocker",
            navItems = navItems,
            initialNav = selectedTab
        ) { id ->
            selectedTab = id
            render()
        }
        built.bar.addAction(R.drawable.oic_add, "Block a number") {
            if (selectedTab == "allowed") addRule("allow") else showAddBlockSheet()
        }
        built.bar.addAction(R.drawable.oic_more_vert, "Call Blocker options") {
            showOptionsSheet()
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        built.body.addView(content, LinearLayout.LayoutParams(-1, -2))
        shell = built
        setContentView(built.root)
        render()
    }

    private fun render() {
        val currentShell = shell ?: return
        currentShell.nav?.select(selectedTab, notify = false)
        val screeningReady = roleHeld()
        val dialerReady = dialerRoleHeld()
        val anyRoleReady = screeningReady || dialerReady
        val enabled = OeaCallBlockRules.enabled(this)
        val exact = OeaCallBlockRules.getExact(this)
        val prefix = OeaCallBlockRules.getPrefix(this)
        val suffix = OeaCallBlockRules.getSuffix(this)
        val totalRules = exact.size + prefix.size + suffix.size
        val allowed = OeaCallBlockRules.getAllowList(this)
        val history = OeaCallBlockRules.blockedHistory(this)

        currentShell.bar.title.text = when (selectedTab) {
            "rules" -> "Rules"
            "allowed" -> "Allowed"
            "history" -> "History"
            else -> "Call Blocker"
        }
        currentShell.bar.setSubtitle(
            when (selectedTab) {
                "rules" -> "$totalRules active ${if (totalRules == 1) "rule" else "rules"}"
                "allowed" -> "${allowed.size} always-allowed ${if (allowed.size == 1) "number" else "numbers"}"
                "history" -> "${history.size} blocked ${if (history.size == 1) "call" else "calls"}"
                else -> when {
                    !anyRoleReady -> "Setup needed to screen incoming calls"
                    enabled -> "Protection active · $totalRules ${if (totalRules == 1) "rule" else "rules"}"
                    else -> "Protection paused"
                }
            }
        )

        content.removeAllViews()
        when (selectedTab) {
            "blocked" -> renderBlocked(screeningReady, dialerReady, enabled, exact.size, prefix.size, suffix.size, allowed.size, history.size)
            "allowed" -> renderAllowed(allowed.sorted())
            "rules" -> renderRules(exact.sorted(), prefix.sorted(), suffix.sorted())
            "history" -> renderHistory(history)
        }
    }

    private fun renderBlocked(
        screeningReady: Boolean,
        dialerReady: Boolean,
        enabled: Boolean,
        exactCount: Int,
        prefixCount: Int,
        suffixCount: Int,
        allowedCount: Int,
        historyCount: Int
    ) {
        val anyRoleReady = screeningReady || dialerReady
        if (!screeningReady) {
            kit.banner(
                content,
                if (dialerReady) R.drawable.oic_shield else R.drawable.oic_warning_fill,
                if (dialerReady) "Enable full call screening" else "Call screening is off",
                if (dialerReady) "OEA screens via the Phone role. Grant the Call Screening role for background filtering too."
                else "Set OEA as your Android Call Screening app so incoming calls are filtered automatically.",
                if (dialerReady) OeaKit.Tone.INFO else OeaKit.Tone.WARNING,
                "Enable"
            ) { requestRole() }
        }

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val totalRules = exactCount + prefixCount + suffixCount
        stats.addView(
            kit.statTile("Status", if (anyRoleReady && enabled) "Active" else if (!enabled) "Paused" else "Setup", if (anyRoleReady && enabled) palette.success else palette.warning),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
        )
        stats.addView(
            kit.statTile("Rules", totalRules.toString(), palette.text),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
        )
        stats.addView(
            kit.statTile("Blocked", historyCount.toString(), palette.text),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        kit.sectionTitle(content, "Protection")
        val protectGroup = kit.group(content)
        kit.switchRow(
            protectGroup,
            R.drawable.oic_shield_fill,
            "Block unwanted calls",
            "Automatically reject callers matching your rules",
            enabled
        ) { next ->
            OeaCallBlockRules.setEnabled(this, next)
            if (next && !roleHeld()) requestRole() else render()
        }
        kit.switchRow(
            protectGroup,
            R.drawable.oic_visibility_off,
            "Block private & unknown numbers",
            "Reject hidden, restricted or withheld caller IDs",
            OeaCallBlockRules.blockUnknown(this)
        ) { next ->
            OeaCallBlockRules.setBlockUnknown(this, next)
            if (next) OeaCallBlockRules.setEnabled(this, true)
            render()
        }

        kit.sectionTitle(content, "Exemptions")
        val contactsPermission = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val exemptGroup = kit.group(content)
        kit.switchRow(
            exemptGroup,
            R.drawable.oic_person,
            "Allow saved contacts",
            if (!contactsPermission && OeaCallBlockRules.allowContacts(this)) "Grant contacts access to activate"
            else "Saved contacts bypass prefix and suffix rules",
            OeaCallBlockRules.allowContacts(this)
        ) { next ->
            if (next && !contactsPermission) {
                OeaCallBlockRules.setAllowContacts(this, true)
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            } else {
                OeaCallBlockRules.setAllowContacts(this, next)
                render()
            }
        }
        kit.switchRow(
            exemptGroup,
            R.drawable.oic_star_fill,
            "Allow starred contacts",
            if (!contactsPermission && OeaCallBlockRules.allowStarred(this)) "Grant contacts access to activate"
            else "Favourite contacts always get through pattern rules",
            OeaCallBlockRules.allowStarred(this)
        ) { next ->
            if (next && !contactsPermission) {
                OeaCallBlockRules.setAllowStarred(this, true)
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            } else {
                OeaCallBlockRules.setAllowStarred(this, next)
                render()
            }
        }
        if (!contactsPermission) {
            kit.row(exemptGroup, R.drawable.oic_lock_open, "Grant contacts permission", "Needed for contact exemptions and contact picker", chevron = true) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            }
        }

        kit.sectionTitle(content, "Quick block")
        val quickGroup = kit.group(content)
        kit.row(quickGroup, R.drawable.oic_block, "Block a phone number", "Enter an exact number, prefix or suffix", chevron = true) {
            showAddBlockSheet()
        }
        kit.row(quickGroup, R.drawable.oic_history, "Block from recent calls", "Pick a caller from your call log", chevron = true) {
            chooseRecentCallToBlock()
        }
        kit.row(quickGroup, R.drawable.oic_person, "Block from contacts", "Choose a number from your contacts", chevron = true) {
            chooseContact(forBlock = true)
        }
        kit.row(quickGroup, R.drawable.oic_check_circle_fill, "Allowed numbers", "$allowedCount exempt from all rules", chevron = true) {
            selectedTab = "allowed"
            render()
        }
    }

    private fun renderAllowed(values: List<String>) {
        val actions = kit.buttonRow(
            kit.primaryButton("Add number", R.drawable.oic_add) { addRule("allow") },
            kit.tonalButton("From contacts", R.drawable.oic_person) { chooseContact(forBlock = false) }
        )
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        if (values.isEmpty()) {
            content.addView(
                kit.emptyState(
                    R.drawable.oic_check_circle_fill,
                    "No allowed numbers yet",
                    "Numbers on this list ring through even if they match a block rule.",
                    "Add number"
                ) { addRule("allow") },
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }

        kit.sectionTitle(content, "Always allowed")
        val group = kit.group(content)
        values.forEach { value ->
            kit.row(group, R.drawable.oic_check_circle_fill, value, "Always rings through", value = "Remove") {
                kit.confirm("Remove allowed number?", "$value will be checked against your block rules again.", "Remove", destructive = true) {
                    OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) - value)
                    render()
                }
            }
        }
    }

    private fun renderRules(exact: List<String>, prefix: List<String>, suffix: List<String>) {
        val actions = kit.buttonRow(
            kit.primaryButton("New rule", R.drawable.oic_add) { showAddBlockSheet() },
            kit.tonalButton("Recent calls", R.drawable.oic_history) { chooseRecentCallToBlock() }
        )
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        if (exact.isEmpty() && prefix.isEmpty() && suffix.isEmpty()) {
            content.addView(
                kit.emptyState(
                    R.drawable.oic_block,
                    "No block rules yet",
                    "Block exact numbers (matching local and country-code formats), starting prefixes or ending digits.",
                    "Add block rule"
                ) { showAddBlockSheet() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(6) }
            )
            return
        }

        ruleGroup("Exact numbers", exact, "exact", "Add exact number")
        ruleGroup("Number prefixes (1–5 digits)", prefix, "prefix", "Add prefix")
        ruleGroup("Number suffixes (1–5 digits)", suffix, "suffix", "Add suffix")
    }

    private fun ruleGroup(title: String, values: List<String>, type: String, addLabel: String) {
        kit.sectionTitle(content, title)
        val group = kit.group(content)
        values.forEach { value ->
            val hint = when (type) {
                "prefix" -> "Blocks numbers starting with $value"
                "suffix" -> "Blocks numbers ending in $value"
                else -> "Blocked number"
            }
            kit.row(group, R.drawable.oic_block, value, hint, value = "Remove") {
                kit.confirm("Remove rule?", "Stop blocking $value?", "Remove", destructive = true) {
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
            }
        }
        kit.row(group, R.drawable.oic_add, addLabel, null, chevron = true) { addRule(type) }
    }

    private fun renderHistory(history: List<OeaCallBlockRules.BlockedCall>) {
        kit.sectionTitle(content, "Service health")
        val lastScreened = OeaCallBlockRules.lastScreenedAt(this)
        val lastScreenedText = if (lastScreened <= 0L) "No calls screened yet"
        else "Last call screened " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastScreened))
        val healthGroup = kit.group(content)
        kit.row(
            healthGroup,
            R.drawable.oic_shield_fill,
            when {
                roleHeld() -> "Call Screening active"
                dialerRoleHeld() -> "Screening via Phone role"
                else -> "Call Screening inactive"
            },
            lastScreenedText,
            value = if (roleHeld()) "Ready" else "Setup",
            chevron = true
        ) { showDiagnosticsSheet() }

        val currentRetention = OeaCallBlockRules.retention(this)
        val retentionLabel = OeaCallBlockRules.RETENTIONS.firstOrNull { it.first == currentRetention }?.second ?: currentRetention
        kit.row(healthGroup, R.drawable.oic_schedule, "Keep history for", retentionLabel, chevron = true) {
            val labels = OeaCallBlockRules.RETENTIONS.map { it.second }
            val idx = OeaCallBlockRules.RETENTIONS.indexOfFirst { it.first == currentRetention }.coerceAtLeast(0)
            kit.choose("History retention", "Older blocked-call entries are pruned automatically", labels, idx) { pick ->
                OeaCallBlockRules.setRetention(this, OeaCallBlockRules.RETENTIONS[pick].first)
                OeaCallBlockRules.pruneHistory(this)
                render()
            }
        }

        kit.sectionTitle(content, "Blocked calls")
        if (history.isEmpty()) {
            content.addView(
                kit.emptyState(
                    R.drawable.oic_history,
                    "No blocked calls yet",
                    "Rejected calls appear here with the time they were blocked."
                ),
                LinearLayout.LayoutParams(-1, -2)
            )
        } else {
            val histGroup = kit.group(content)
            history.forEach { call ->
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(call.timestamp))
                kit.row(histGroup, R.drawable.oic_call_end, call.number, "Blocked · $date", chevron = true) {
                    kit.actions(call.number, "Blocked $date", listOf(
                        OeaAppKit.Action(R.drawable.oic_check_circle_fill, "Always allow this number", "Add to your allow-list") {
                            OeaCallBlockRules.setAllowList(this, OeaCallBlockRules.getAllowList(this) + call.number)
                            selectedTab = "allowed"
                            render()
                        },
                        OeaAppKit.Action(R.drawable.oic_call, "Open in dialer", "Place a call to ${call.number}") {
                            runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", call.number, null))) }
                        }
                    ))
                }
            }
            content.addView(
                kit.button("Clear blocked history", palette.cardHigh, palette.danger, R.drawable.oic_delete) {
                    kit.confirm("Clear blocked history?", "Removes saved call entries. Your block and allow rules stay intact.", "Clear", destructive = true) {
                        OeaCallBlockRules.clearBlockedHistory(this)
                        render()
                    }
                },
                LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) }
            )
        }

        val decisions = OeaCallBlockRules.recentDecisions(this)
        if (decisions.isNotEmpty()) {
            kit.sectionTitle(content, "Recent screening decisions")
            val decGroup = kit.group(content)
            decisions.take(8).forEach { d ->
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.timestamp))
                kit.row(
                    decGroup,
                    if (d.blocked) R.drawable.oic_block else R.drawable.oic_check_circle_fill,
                    d.number,
                    "${d.reason} · $date",
                    value = if (d.blocked) "Blocked" else "Allowed",
                    chevron = false
                )
            }
        }
    }

    private fun showAddBlockSheet() {
        kit.actions("Add block rule", "Choose how OEA should match callers", listOf(
            OeaAppKit.Action(R.drawable.oic_block, "Exact phone number", "Matches local and international formats") { addRule("exact") },
            OeaAppKit.Action(R.drawable.oic_dialpad, "Starting prefix", "Block numbers starting with 1–5 digits") { addRule("prefix") },
            OeaAppKit.Action(R.drawable.oic_dialpad, "Ending suffix", "Block numbers ending with 1–5 digits") { addRule("suffix") },
            OeaAppKit.Action(R.drawable.oic_history, "From recent calls", "Choose a caller from your call log") { chooseRecentCallToBlock() },
            OeaAppKit.Action(R.drawable.oic_person, "From contacts", "Choose a saved contact") { chooseContact(forBlock = true) }
        ))
    }

    private fun showOptionsSheet() {
        kit.actions("Call Blocker", null, listOf(
            OeaAppKit.Action(R.drawable.oic_shield_fill, "Call Screening role", if (roleHeld()) "Active" else "Grant Android Call Screening role") {
                requestRole()
            },
            OeaAppKit.Action(R.drawable.oic_info, "Troubleshooting", "What to check if unwanted calls still ring") {
                showDiagnosticsSheet()
            },
            OeaAppKit.Action(R.drawable.oic_cleaning_services, "Prune old history now", "Remove expired history entries") {
                val removed = OeaCallBlockRules.pruneHistory(this)
                toast(if (removed > 0) "$removed expired entries removed" else "History is already up to date")
                render()
            },
            OeaAppKit.Action(R.drawable.oic_settings, "Default apps settings", "Open Android's default-apps screen") {
                openSettings()
            }
        ))
    }

    private fun showDiagnosticsSheet() {
        kit.sheet("Screening diagnostics", "Keep call blocking reliable") { panel, dialog ->
            kit.note(
                panel,
                "Android invokes OEA's screening service while OEA holds the Call Screening role (or Default Phone role). " +
                    "If calls ring through without appearing in Recent decisions, re-enable the Call Screening role and allow Auto-launch and unrestricted battery for OEA in system settings."
            )
            panel.addView(kit.buttonRow(
                kit.quietButton("Close") { dialog.dismiss() },
                kit.primaryButton("Enable role") {
                    dialog.dismiss()
                    requestRole()
                }
            ))
        }
    }

    private fun addRule(type: String) {
        val (title, subtitle, hint) = when (type) {
            "allow" -> Triple("Add allowed number", "Always bypasses your block rules", "Phone number")
            "prefix" -> Triple("Block prefix", "Matches the first 1–5 digits of a number", "Prefix digits (e.g. 0803)")
            "suffix" -> Triple("Block suffix", "Matches the last 1–5 digits of a number", "Suffix digits (e.g. 4567)")
            else -> Triple("Block phone number", "Matches local and international formats", "Phone number")
        }
        kit.prompt(title, subtitle, hint, numeric = true, confirmLabel = "Save") { input ->
            val raw = input.filter(Char::isDigit)
            val value = if (type == "prefix" || type == "suffix") raw.take(5) else raw.takeLast(15)
            if (value.isEmpty()) return@prompt "Enter valid digits"
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
                toast("Rule saved. Enable Call Screening to start filtering calls.")
                requestRole()
            } else {
                toast("Rule saved")
                render()
            }
            null
        }
    }

    private fun chooseRecentCallToBlock() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG)
            return
        }
        val labels = mutableListOf<String>()
        val subtitles = mutableListOf<String>()
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
                    labels += name.ifEmpty { raw }
                    subtitles += if (name.isNotEmpty()) raw else "Recent call"
                    numbers += digits
                }
            }
        }
        if (numbers.isEmpty()) {
            toast("No recent calls found")
            return
        }
        kit.choose("Block a recent caller", null, labels, -1, subtitles) { which ->
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
                        names += name
                        numbers += num
                    }
                }
            }
        }
        if (names.isEmpty()) {
            toast("No contacts found")
            return
        }
        kit.choose(if (forBlock) "Block a contact" else "Allow a contact", null, names, -1, numbers) { which ->
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
                    toast("Allowed $number")
                    selectedTab = "allowed"
                }
                render()
            }
        }
    }

    private fun requestRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true) {
                if (manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    OeaCallBlockRules.setEnabled(this, true)
                    toast("Call Screening is active")
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
            .onFailure { toast("Open Settings › Default apps › Call screening") }
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
                toast("Call Blocker is ready")
            } else {
                toast("Call Screening role not granted yet")
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_ROLE = 9101
        private const val REQUEST_CONTACTS = 9102
        private const val REQUEST_CALL_LOG = 9103
    }
}
