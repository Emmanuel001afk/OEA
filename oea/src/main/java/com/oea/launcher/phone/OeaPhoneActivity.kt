package com.oea.launcher.phone

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.callblocker.OeaCallBlockRules
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * OEA Phone keeps Dial, Recent calls, Contacts, and Blocked calls as separate
 * sections. It deliberately falls back to ACTION_DIAL when direct-call permission
 * is unavailable, so tapping Call never silently fails or bypasses Android consent.
 */
class OeaPhoneActivity : Activity() {
    private lateinit var number: EditText
    private lateinit var dialPanel: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var search: EditText
    private var selectedTab = TAB_RECENT
    private var contactRows: List<ContactRow> = emptyList()
    private var pendingCallNumber: String? = null

    private data class ContactRow(val name: String, val phone: String)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedTab = state?.getString("phone_tab") ?: TAB_RECENT
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("phone_tab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = if (isDefaultDialer()) "Default phone app enabled"
                else "OEA Phone • choose Default Phone for full call handling"
            renderList()
        }
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(10))
            setBackgroundColor(0xFF0C0F15.toInt())
        }
        root.addView(TextView(this).apply {
            text = "OEA Phone"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(40)))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF9AA2B1.toInt())
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        listOf(TAB_DIAL to "Dial", TAB_RECENT to "Recent", TAB_CONTACTS to "Contacts", TAB_BLOCKED to "Blocked").forEach { (tab, label) ->
            tabs.addView(smallButton(label) {
                selectedTab = tab
                updateTabs()
                renderList()
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(50)))

        dialPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        number = EditText(this).apply {
            hint = "Phone number or USSD code"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9AA2B1.toInt())
            contentDescription = "Phone number or USSD code"
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        dialPanel.addView(number, LinearLayout.LayoutParams(-1, dp(62)))

        val keypad = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").forEachIndexed { index, digit ->
            keypad.addView(Button(this).apply {
                text = digit
                textSize = 20f
                contentDescription = "Dial $digit"
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0xFF1E2431.toInt(), 18)
                setOnClickListener { insertDigit(digit) }
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(dp(4), dp(3), dp(4), dp(3))
            })
        }
        dialPanel.addView(keypad, LinearLayout.LayoutParams(-1, dp(228)))

        val callActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        callActions.addView(actionButton("Call", true) { placeCall() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        callActions.addView(actionButton("Delete", false) {
            val end = number.selectionEnd.coerceAtLeast(0)
            if (end > 0 && number.text.isNotEmpty()) number.text.delete(end - 1, end)
        }, LinearLayout.LayoutParams(dp(100), dp(50)))
        dialPanel.addView(callActions, LinearLayout.LayoutParams(-1, dp(54)))
        val defaultPhone = smallButton("Set default phone") { requestDefaultDialer() }
        dialPanel.addView(defaultPhone, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(dialPanel, LinearLayout.LayoutParams(-1, -2))

        search = EditText(this).apply {
            hint = "Search contacts by name or number"
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9AA2B1.toInt())
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = rounded(0xFF1E2431.toInt(), 14)
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (selectedTab == TAB_CONTACTS) renderContacts()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val blocker = smallButton("Call Blocker settings") {
            runCatching {
                startActivity(Intent(this@OeaPhoneActivity, OeaSystemToolsActivity::class.java)
                    .putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "call_blocker"))
            }.onFailure { toast("Could not open Call Blocker settings.") }
        }
        root.addView(blocker, LinearLayout.LayoutParams(-1, dp(42)))
        setContentView(root)
        updateTabs()
        renderList()
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        dialPanel.visibility = if (selectedTab == TAB_DIAL) View.VISIBLE else View.GONE
        search.visibility = if (selectedTab == TAB_CONTACTS) View.VISIBLE else View.GONE
        list.removeAllViews()
        when (selectedTab) {
            TAB_DIAL -> addEmptyState("Enter a number, use the keypad, then tap Call.")
            TAB_RECENT -> loadCallLog()
            TAB_CONTACTS -> {
                loadContacts()
                renderContacts()
            }
            TAB_BLOCKED -> loadBlockedHistory()
        }
    }

    private fun insertDigit(digit: String) {
        val start = number.selectionStart.coerceAtLeast(0)
        val end = number.selectionEnd.coerceAtLeast(start)
        number.text.replace(start, end, digit)
        number.setSelection((start + digit.length).coerceAtMost(number.text.length))
    }

    private fun placeCall(value: String = number.text.toString().trim()) {
        val raw = value.trim()
        if (raw.isEmpty()) {
            number.error = "Enter a phone number or USSD code"
            selectedTab = TAB_DIAL
            updateTabs()
            renderList()
            return
        }
        val uri = Uri.parse("tel:" + Uri.encode(raw, "*#"))
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = raw
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE)
            } else {
                openDialer(uri)
            }
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_CALL, uri)) }
            .onFailure { openDialer(uri) }
    }

    private fun openDialer(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri)) }
            .onFailure { toast("Unable to open Android's dialer for this number.") }
    }

    private fun requestDefaultDialer() {
        if (isDefaultDialer()) {
            toast("OEA is already the default phone app.")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                runCatching { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQUEST_DIALER_ROLE) }
                    .onFailure { openDefaultAppsSettings() }
            } else openDefaultAppsSettings()
        } else {
            val request = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            runCatching { startActivityForResult(request, REQUEST_DIALER_ROLE) }
                .onFailure { openDefaultAppsSettings() }
        }
    }

    private fun isDefaultDialer(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
        }
    }.getOrDefault(false)

    private fun openDefaultAppsSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { toast("Open Android Settings and choose OEA as the default phone app.") }
    }

    private fun loadCallLog() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            addEmptyState("Call history permission is needed to show recent calls.", "Grant permission") {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG)
            }
            return
        }
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME)
        var count = 0
        runCatching {
            contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC")?.use { cursor ->
                while (cursor.moveToNext() && count < 100) {
                    count++
                    val phone = cursor.getString(0).orEmpty().ifBlank { "Unknown number" }
                    val type = when (cursor.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        else -> "Call"
                    }
                    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cursor.getLong(2)))
                    val name = cursor.getString(3).orEmpty().ifBlank { phone }
                    addEntry(name, "$phone • $type • $date", "Call", action = { placeCall(phone) }, rowClick = { fillNumber(phone) })
                }
            }
        }.onFailure { addEmptyState("Could not read call history on this device.") }
        if (count == 0 && list.childCount == 0) addEmptyState("No recent calls found.")
        addSectionAction("Open dial pad") {
            selectedTab = TAB_DIAL
            updateTabs()
            renderList()
        }
    }

    private fun loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            addEmptyState("Contacts permission is needed to show contacts.", "Grant permission") {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            }
            contactRows = emptyList()
            return
        }
        val rows = mutableListOf<ContactRow>()
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 500) {
                    rows += ContactRow(cursor.getString(0).orEmpty().ifBlank { "Unknown contact" }, cursor.getString(1).orEmpty())
                }
            }
        }.onFailure { addEmptyState("Could not read contacts on this device.") }
        contactRows = rows.distinctBy { it.name.lowercase(Locale.ROOT) + "|" + it.phone }
    }

    private fun renderContacts() {
        if (!::list.isInitialized || selectedTab != TAB_CONTACTS) return
        list.removeAllViews()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            addEmptyState("Grant Contacts permission to search your phone contacts.", "Grant permission") {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            }
            return
        }
        val query = search.text.toString().trim().lowercase(Locale.ROOT)
        val numericQuery = query.filter(Char::isDigit)
        val filtered = contactRows.filter {
            query.isEmpty() ||
                it.name.lowercase(Locale.ROOT).contains(query) ||
                (numericQuery.isNotEmpty() && it.phone.filter(Char::isDigit).contains(numericQuery))
        }
        if (filtered.isEmpty()) addEmptyState(if (query.isEmpty()) "No phone contacts found." else "No contacts match “$query”.")
        filtered.forEach { contact ->
            addEntry(contact.name, contact.phone, "Call", action = { placeCall(contact.phone) }, rowClick = { fillNumber(contact.phone) })
        }
        addSectionAction("Open dial pad") {
            selectedTab = TAB_DIAL
            updateTabs()
            renderList()
        }
    }

    private fun loadBlockedHistory() {
        val history = OeaCallBlockRules.blockedHistory(this)
        history.forEach { entry ->
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))
            addEntry(entry.number, "Blocked • $date", "Call", action = { placeCall(entry.number) }, rowClick = { fillNumber(entry.number) })
        }
        if (history.isEmpty()) addEmptyState("No blocked calls recorded by OEA.")
        addSectionAction("Open dial pad") {
            selectedTab = TAB_DIAL
            updateTabs()
            renderList()
        }
    }

    private fun addEntry(
        title: String,
        subtitle: String,
        actionLabel: String,
        action: () -> Unit,
        rowClick: (() -> Unit)? = null,
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = rounded(0xFF1E2431.toInt(), 14)
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@OeaPhoneActivity).apply {
                text = title
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@OeaPhoneActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(0xFF9AA2B1.toInt())
                maxLines = 2
            })
            isClickable = rowClick != null
            isFocusable = rowClick != null
            setOnClickListener { rowClick?.invoke() }
        }
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actionButton(actionLabel, true, action), LinearLayout.LayoutParams(dp(78), dp(40)))
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    private fun addSectionAction(label: String, action: () -> Unit) {
        list.addView(smallButton(label, action), LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
    }

    private fun addEmptyState(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        list.addView(TextView(this).apply {
            text = message + if (actionLabel != null) "\n\n$actionLabel" else ""
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9AA2B1.toInt())
            setPadding(dp(20), dp(24), dp(20), dp(24))
            if (action != null) {
                isClickable = true
                setOnClickListener { action() }
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun fillNumber(value: String) {
        selectedTab = TAB_DIAL
        updateTabs()
        renderList()
        number.setText(value)
        number.setSelection(number.text.length)
    }

    private fun updateTabs() {
        if (!::tabs.isInitialized) return
        for (i in 0 until tabs.childCount) {
            val button = tabs.getChildAt(i) as? Button ?: continue
            val tab = listOf(TAB_DIAL, TAB_RECENT, TAB_CONTACTS, TAB_BLOCKED).getOrNull(i)
            val active = tab == selectedTab
            button.alpha = 1f
            button.isSelected = active
            button.setTextColor(0xFFFFFFFF.toInt())
            button.background = rounded(if (active) 0xFF2D8C74.toInt() else 0xFF1E2431.toInt(), 16)
        }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(0xFFFFFFFF.toInt())
        background = rounded(0xFF1E2431.toInt(), 16)
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        contentDescription = label
        if (primary) {
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF2D8C74.toInt(), 18)
        }
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CALL_LOG, REQUEST_CONTACTS -> renderList()
            REQUEST_CALL_PHONE -> {
                val raw = pendingCallNumber
                pendingCallNumber = null
                if (raw != null) {
                    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) placeCall(raw)
                    else openDialer(Uri.parse("tel:" + Uri.encode(raw, "*#")))
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DIALER_ROLE) {
            status.text = if (isDefaultDialer()) "Default phone app enabled" else "Default phone role was not granted"
            renderList()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_DIAL = "dial"
        private const val TAB_RECENT = "recent"
        private const val TAB_CONTACTS = "contacts"
        private const val TAB_BLOCKED = "blocked"
        private const val REQUEST_DIALER_ROLE = 9002
        private const val REQUEST_CALL_LOG = 9003
        private const val REQUEST_CONTACTS = 9004
        private const val REQUEST_CALL_PHONE = 9005
    }
}
