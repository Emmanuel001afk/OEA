package com.oea.launcher.phone

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
import android.telecom.TelecomManager
import android.view.Gravity
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

class OeaPhoneActivity : Activity() {
    private lateinit var number: EditText
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var selectedTab = TAB_RECENT

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = if (isDefaultDialer()) "OEA is the default phone app"
                else "OEA Phone • choose Default Phone to enable full call handling"
            renderList()
        }
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
            setBackgroundColor(0xFF0C0F15.toInt())
        }
        root.addView(TextView(this).apply {
            text = "OEA Phone"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(42)))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF9AA2B1.toInt())
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        number = EditText(this).apply {
            hint = "Phone number or USSD code"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 21f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9AA2B1.toInt())
            contentDescription = "Phone number or USSD code"
        }
        root.addView(number, LinearLayout.LayoutParams(-1, dp(54)))

        val keypad = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            setPadding(0, dp(4), 0, dp(4))
        }
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").forEachIndexed { index, digit ->
            keypad.addView(Button(this).apply {
                text = digit
                textSize = 19f
                contentDescription = "Dial $digit"
                setOnClickListener { insertDigit(digit) }
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(42)
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(dp(2), dp(1), dp(2), dp(1))
            })
        }
        root.addView(keypad, LinearLayout.LayoutParams(-1, dp(180)))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "CALL"
            setOnClickListener { placeCall() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply {
            text = "⌫"
            contentDescription = "Delete last digit"
            setOnClickListener {
                val end = number.selectionEnd.coerceAtLeast(0)
                if (end > 0 && number.text.isNotEmpty()) number.text.delete(end - 1, end)
            }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(50)))

        val shortcuts = LinearLayout(this).apply { gravity = Gravity.CENTER }
        shortcuts.addView(smallButton("Default Phone") { requestDefaultDialer() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        shortcuts.addView(smallButton("Call Blocker") {
            runCatching {
                startActivity(Intent(this@OeaPhoneActivity, OeaSystemToolsActivity::class.java)
                    .putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "call_blocker"))
            }.onFailure { toast("Could not open Call Blocker.") }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(shortcuts, LinearLayout.LayoutParams(-1, dp(46)))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        listOf(TAB_RECENT to "Recent", TAB_MISSED to "Missed", TAB_CONTACTS to "Contacts", TAB_BLOCKED to "Blocked").forEach { (tab, label) ->
            tabs.addView(smallButton(label) {
                selectedTab = tab
                updateTabs(tabs)
                renderList()
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateTabs(tabs)
        renderList()
    }

    private fun insertDigit(digit: String) {
        val start = number.selectionStart.coerceAtLeast(0)
        val end = number.selectionEnd.coerceAtLeast(start)
        number.text.replace(start, end, digit)
        number.setSelection((start + digit.length).coerceAtMost(number.text.length))
    }

    private fun placeCall() {
        val raw = number.text.toString().trim()
        if (raw.isEmpty()) {
            number.error = "Enter a phone number or USSD code"
            return
        }
        val uri = Uri.parse("tel:" + Uri.encode(raw, "*#"))
        val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        runCatching { startActivity(intent) }
            .onFailure { toast("Unable to open the phone dialer for this number.") }
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

    private fun renderList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        when (selectedTab) {
            TAB_RECENT, TAB_MISSED -> loadCallLog(selectedTab == TAB_MISSED)
            TAB_CONTACTS -> loadContacts()
            TAB_BLOCKED -> loadBlockedHistory()
        }
    }

    private fun loadCallLog(missedOnly: Boolean) {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            addEmptyState("Call history permission is needed to show calls.", "Grant permission") {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG)
            }
            return
        }
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME)
        val selection = if (missedOnly) "${CallLog.Calls.TYPE} = ?" else null
        val args = if (missedOnly) arrayOf(CallLog.Calls.MISSED_TYPE.toString()) else null
        var count = 0
        runCatching {
            contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, CallLog.Calls.DATE + " DESC")?.use { cursor ->
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
                    addEntry(name, "$phone • $type • $date") { fillNumber(phone) }
                }
            }
        }.onFailure { addEmptyState("Could not read call history on this device.") }
        if (count == 0 && list.childCount == 0) addEmptyState(if (missedOnly) "No missed calls found." else "No recent calls found.")
    }

    private fun loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            addEmptyState("Contacts permission is needed to show contacts.", "Grant permission") {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
            }
            return
        }
        var count = 0
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && count < 300) {
                    val name = cursor.getString(0).orEmpty().ifBlank { "Unknown contact" }
                    val phone = cursor.getString(1).orEmpty()
                    count++
                    addEntry(name, phone) { fillNumber(phone) }
                }
            }
        }.onFailure { addEmptyState("Could not read contacts on this device.") }
        if (count == 0 && list.childCount == 0) addEmptyState("No phone contacts found.")
    }

    private fun loadBlockedHistory() {
        val history = OeaCallBlockRules.blockedHistory(this)
        history.forEach { entry ->
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))
            addEntry(entry.number, "Blocked • $date") { fillNumber(entry.number) }
        }
        if (history.isEmpty()) addEmptyState("No blocked calls recorded by OEA.")
    }

    private fun fillNumber(value: String) {
        number.setText(value)
        number.setSelection(number.text.length)
    }

    private fun addEntry(title: String, subtitle: String, action: () -> Unit) {
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF1E2431.toInt())
            }
            addView(TextView(this@OeaPhoneActivity).apply {
                text = title
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@OeaPhoneActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(0xFF9AA2B1.toInt())
                maxLines = 2
            })
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
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

    private fun updateTabs(tabs: LinearLayout) {
        for (i in 0 until tabs.childCount) {
            val button = tabs.getChildAt(i) as? Button ?: continue
            val active = listOf(TAB_RECENT, TAB_MISSED, TAB_CONTACTS, TAB_BLOCKED).getOrNull(i) == selectedTab
            button.alpha = if (active) 1f else 0.68f
        }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setOnClickListener { action() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALL_LOG || requestCode == REQUEST_CONTACTS) renderList()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DIALER_ROLE) {
            status.text = if (isDefaultDialer()) "OEA is the default phone app" else "Default phone role was not granted"
            renderList()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_RECENT = "recent"
        private const val TAB_MISSED = "missed"
        private const val TAB_CONTACTS = "contacts"
        private const val TAB_BLOCKED = "blocked"
        private const val REQUEST_DIALER_ROLE = 9002
        private const val REQUEST_CALL_LOG = 9003
        private const val REQUEST_CONTACTS = 9004
    }
}
