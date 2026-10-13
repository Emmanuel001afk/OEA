package com.oea.launcher.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
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
import com.oea.launcher.callblocker.OeaCallBlockRules
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * OEA Phone keeps Dial, Recent calls, Missed calls, Contacts, and Blocked calls as
 * dedicated sections with full USSD (* / #) URI encoding and polished SIM selection.
 */
class OeaPhoneActivity : Activity() {
    private lateinit var number: EditText
    private lateinit var dialScroll: ScrollView
    private lateinit var dialPanel: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var search: EditText
    private var selectedTab = TAB_DIAL
    private var contactRows: List<ContactRow> = emptyList()
    private var pendingCallNumber: String? = null
    private var pendingDeleteCallId: Long? = null
    private var pendingContactHistory: ContactRow? = null
    private val phonePrefs by lazy { getSharedPreferences("oea_phone_prefs", Context.MODE_PRIVATE) }

    private data class ContactRow(val id: Long, val name: String, val phone: String)
    private data class CallLogRow(val id: Long, val name: String, val number: String, val type: Int, val date: Long, val durationSeconds: Long)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedTab = state?.getString("phone_tab") ?: TAB_DIAL
        build()
        consumeDialIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDialIntent(intent)
    }

    private fun consumeDialIntent(source: Intent?) {
        val action = source?.action ?: return
        if (action != Intent.ACTION_DIAL && action != Intent.ACTION_VIEW && action != Intent.ACTION_CALL) return
        val data = source.data ?: return
        if (data.scheme != "tel") return
        val base = data.schemeSpecificPart.orEmpty()
        val fragment = data.fragment
        val raw = (if (fragment != null) "$base#$fragment" else base).takeIf { it.isNotBlank() } ?: return
        if (action == Intent.ACTION_CALL) {
            placeCall(raw)
        } else {
            fillNumber(raw)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("phone_tab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            val defaultDialer = isDefaultDialer()
            status.text = if (defaultDialer) {
                "● Default phone app active • USSD & call protection ready"
            } else {
                "○ Tap to set OEA as Default Phone for full call & screening control"
            }
            status.setTextColor(if (defaultDialer) 0xFF7BE0B1.toInt() else 0xFFFFC477.toInt())
            renderList()
        }
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(0xFF0C0F15.toInt())
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(smallButton("Back") { finish() }, LinearLayout.LayoutParams(dp(58), dp(36)).apply { rightMargin = dp(8) })
        headerRow.addView(TextView(this).apply {
            text = "OEA Phone"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(smallButton("Call Blocker") {
            runCatching {
                startActivity(Intent(this@OeaPhoneActivity, com.oea.launcher.callblocker.OeaCallBlockerActivity::class.java))
            }.onFailure { toast("Could not open OEA Call Blocker.") }
        }, LinearLayout.LayoutParams(dp(106), dp(36)))
        root.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF9AA2B1.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xFF151B26.toInt(), 12)
            setOnClickListener { requestDefaultDialer() }
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }
        listOf(
            TAB_DIAL to "Dial",
            TAB_RECENT to "Recent",
            TAB_MISSED to "Missed",
            TAB_CONTACTS to "Contacts",
            TAB_BLOCKED to "Blocked"
        ).forEach { (tab, label) ->
            tabs.addView(smallButton(label) {
                selectedTab = tab
                updateTabs()
                renderList()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))

        dialPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }

        val numberCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFF151B26.toInt(), 20)
        }
        number = EditText(this).apply {
            hint = "Phone number or USSD code"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            filters = emptyArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                showSoftInputOnFocus = false
            }
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF6E7889.toInt())
            background = ColorDrawable(Color.TRANSPARENT)
            contentDescription = "Phone number or USSD code"
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        numberCard.addView(number, LinearLayout.LayoutParams(-1, dp(56)))
        dialPanel.addView(numberCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val keypad = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val keyDefs = listOf(
            "1" to "VOICEMAIL",
            "2" to "ABC",
            "3" to "DEF",
            "4" to "GHI",
            "5" to "JKL",
            "6" to "MNO",
            "7" to "PQRS",
            "8" to "TUV",
            "9" to "WXYZ",
            "*" to "STAR",
            "0" to "+",
            "#" to "USSD"
        )
        keyDefs.forEachIndexed { index, (digit, sub) ->
            keypad.addView(Button(this).apply {
                text = "$digit\n$sub"
                textSize = 16f
                isAllCaps = false
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
                minHeight = 0
                minimumHeight = 0
                includeFontPadding = false
                contentDescription = "Dial $digit"
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0xFF1B2230.toInt(), 18)
                setOnClickListener { insertDigit(digit) }
                if (digit == "0") {
                    setOnLongClickListener {
                        insertDigit("+")
                        true
                    }
                }
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(58)
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(dp(5), dp(4), dp(5), dp(4))
            })
        }
        dialPanel.addView(keypad, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val callActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        callActions.addView(
            actionButton("Call", true) { placeCall() },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(8) }
        )
        callActions.addView(
            actionButton("⌫ Delete", false) { deleteDigit() }.apply {
                setOnLongClickListener {
                    number.setText("")
                    true
                }
            },
            LinearLayout.LayoutParams(dp(112), dp(54))
        )
        dialPanel.addView(callActions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolsRow.addView(
            smallButton("Default phone") { requestDefaultDialer() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) }
        )
        toolsRow.addView(
            smallButton("Call display") { showCallOptions() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) }
        )
        toolsRow.addView(
            smallButton("Incoming-call flash alert") { showFlashOptions() },
            LinearLayout.LayoutParams(0, dp(40), 1.3f).apply { leftMargin = dp(4) }
        )
        dialPanel.addView(toolsRow, LinearLayout.LayoutParams(-1, -2))

        dialScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(dialPanel, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(dialScroll, LinearLayout.LayoutParams(-1, 0, 1f))

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
        root.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        listScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        updateTabs()
        renderList()
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        val onDial = selectedTab == TAB_DIAL
        dialScroll.visibility = if (onDial) View.VISIBLE else View.GONE
        listScroll.visibility = if (onDial) View.GONE else View.VISIBLE
        search.visibility = if (selectedTab == TAB_CONTACTS) View.VISIBLE else View.GONE
        list.removeAllViews()
        when (selectedTab) {
            TAB_DIAL -> Unit
            TAB_RECENT -> loadCallLog()
            TAB_MISSED -> loadCallLog(missedOnly = true)
            TAB_CONTACTS -> {
                loadContacts()
                renderContacts()
            }
            TAB_BLOCKED -> loadBlockedHistory()
        }
    }

    private fun insertDigit(digit: String) {
        val current = number.text?.toString().orEmpty()
        val hasCursor = number.hasFocus() && number.selectionStart >= 0 && number.selectionEnd >= 0
        val start = if (hasCursor) number.selectionStart.coerceIn(0, current.length) else current.length
        val end = if (hasCursor) number.selectionEnd.coerceIn(start, current.length) else current.length
        val updated = current.substring(0, start) + digit + current.substring(end)
        number.setText(updated)
        number.setSelection((start + digit.length).coerceIn(0, number.text.length))
    }

    private fun deleteDigit() {
        val current = number.text?.toString().orEmpty()
        if (current.isEmpty()) return
        val hasCursor = number.hasFocus() && number.selectionStart >= 0 && number.selectionEnd >= 0
        val start = if (hasCursor) number.selectionStart.coerceIn(0, current.length) else current.length
        val end = if (hasCursor) number.selectionEnd.coerceIn(start, current.length) else current.length
        if (start != end) {
            val updated = current.substring(0, start) + current.substring(end)
            number.setText(updated)
            number.setSelection(start.coerceIn(0, number.text.length))
        } else if (end > 0) {
            val updated = current.substring(0, end - 1) + current.substring(end)
            number.setText(updated)
            number.setSelection((end - 1).coerceIn(0, number.text.length))
        }
    }

    /**
     * Builds a tel: URI that preserves '#' as '%23' in the encoded scheme-specific part.
     * Previously, Uri.encode(raw, "*#") left '#' unencoded, causing Uri.parse("tel:*323#")
     * to strip '#' as a URI fragment delimiter so only '*323' was sent to Android Telecom.
     */
    private fun buildTelUri(raw: String): Uri = Uri.fromParts("tel", raw, null)

    private fun placeCall(value: String = number.text.toString().trim()) {
        val raw = value.trim()
        if (raw.isEmpty()) {
            toast("Enter a phone number or USSD code first.")
            selectedTab = TAB_DIAL
            updateTabs()
            renderList()
            return
        }
        val uri = buildTelUri(raw)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = raw
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = raw
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_PHONE_STATE)
            return
        }
        val telecom = getSystemService(TelecomManager::class.java)
        val accounts = runCatching { telecom?.callCapablePhoneAccounts.orEmpty() }.getOrDefault(emptyList())
        if (accounts.isEmpty()) {
            runCatching { telecom?.placeCall(uri, Bundle()) ?: openDialer(uri) }
                .onFailure { openDialer(uri) }
            return
        }
        if (accounts.size == 1) {
            startCallWithAccount(raw, accounts.first())
            return
        }
        showPolishedSimDialog(raw, uri, telecom, accounts)
    }

    private fun showPolishedSimDialog(
        raw: String,
        uri: Uri,
        telecom: TelecomManager?,
        accounts: List<PhoneAccountHandle>
    ) {
        val savedId = phonePrefs.getString("preferred_phone_account", null)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = rounded(0xFF131924.toInt(), 22)
        }
        container.addView(TextView(this).apply {
            text = "Choose SIM for this call"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        container.addView(TextView(this).apply {
            text = if (raw.endsWith("#")) "USSD / MMI code • $raw" else "Dialing $raw"
            textSize = 13f
            setTextColor(0xFF7BE0B1.toInt())
            setPadding(0, 0, 0, dp(12))
        }, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        accounts.forEachIndexed { index, handle ->
            val account = runCatching { telecom?.getPhoneAccount(handle) }.getOrNull()
            val label = account?.label?.toString().orEmpty().ifBlank { "SIM ${index + 1}" }
            val address = account?.shortDescription?.toString().orEmpty()
            val isPreferred = handle.id == savedId
            val simCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(if (isPreferred) 0xFF1F3834.toInt() else 0xFF1C2433.toInt(), 16)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    phonePrefs.edit().putString("preferred_phone_account", handle.id).apply()
                    dialog.dismiss()
                    startCallWithAccount(raw, handle)
                }
            }
            simCard.addView(TextView(this).apply {
                text = "${index + 1}"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0xFF2D8C74.toInt(), 12)
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) })

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@OeaPhoneActivity).apply {
                    text = label + if (isPreferred) "  • Last used" else ""
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                })
                if (address.isNotBlank() && address != label) {
                    addView(TextView(this@OeaPhoneActivity).apply {
                        text = address
                        textSize = 12f
                        setTextColor(0xFF9AA2B1.toInt())
                    })
                }
            }
            simCard.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            container.addView(simCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        footer.addView(smallButton("Use Android's choice") {
            dialog.dismiss()
            runCatching { telecom?.placeCall(uri, Bundle()) ?: openDialer(uri) }
                .onFailure { openDialer(uri) }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        footer.addView(smallButton("Cancel") {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(dp(96), dp(40)))
        container.addView(footer, LinearLayout.LayoutParams(-1, -2))
        dialog.show()
    }

    private fun startCallWithAccount(raw: String, handle: PhoneAccountHandle) {
        val uri = buildTelUri(raw)
        runCatching {
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            getSystemService(TelecomManager::class.java).placeCall(uri, extras)
        }.onFailure {
            toast("Could not start the call through the selected SIM. Opening Android's dialer.")
            openDialer(uri)
        }
    }

    private fun showCallOptions() {
        val modes = arrayOf("Full screen", "Popup window", "Compact floating card")
        val details = arrayOf(
            "Full screen • over your wallpaper",
            "Popup window • a card over the app you're using",
            "Compact floating card • a small card at the top; keep using your phone",
        )
        val keys = arrayOf("fullscreen", "popup", "compact")
        val current = phonePrefs.getString("call_ui_mode", "fullscreen") ?: "fullscreen"
        AlertDialog.Builder(this)
            .setTitle("Incoming and active call display")
            .setSingleChoiceItems(details, keys.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                phonePrefs.edit().putString("call_ui_mode", keys[which]).apply()
                dialog.dismiss()
                toast("${modes[which]} selected. Locked phones always show calls full screen; you can switch during a call.")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFlashOptions() {
        AlertDialog.Builder(this)
            .setTitle("Incoming-call flash alert")
            .setMessage("Blink the camera flash while an incoming call is ringing. This needs Camera permission and may be unavailable while another app uses the camera.")
            .setPositiveButton(if (phonePrefs.getBoolean("flash_alert", false)) "Turn off" else "Turn on") { _, _ ->
                if (phonePrefs.getBoolean("flash_alert", false)) {
                    phonePrefs.edit().putBoolean("flash_alert", false).apply()
                    toast("Call flash alert disabled.")
                } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    phonePrefs.edit().putBoolean("flash_alert", true).apply()
                    toast("Call flash alert enabled.")
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun hasCallScreeningAccess(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true || isDefaultDialer()
        } else {
            isDefaultDialer()
        }
    }.getOrDefault(false)

    private fun hasConfiguredBlockRule(number: String): Boolean {
        val variants = OeaCallBlockRules.numberVariants(number)
        if (variants.isEmpty()) return false
        if (variants.any { it in OeaCallBlockRules.getExact(this) }) return true
        return OeaCallBlockRules.getPrefix(this).any { rule -> variants.any { it.startsWith(rule) } } ||
            OeaCallBlockRules.getSuffix(this).any { rule -> variants.any { it.endsWith(rule) } }
    }

    private fun callBlockStatus(number: String): String? {
        if (!hasConfiguredBlockRule(number)) return null
        return when {
            !OeaCallBlockRules.enabled(this) -> "Block rule • protection off"
            !hasCallScreeningAccess() -> "Block rule • Android screening access needed"
            OeaCallBlockRules.shouldBlock(this, number) -> "Blocked"
            else -> "Rule matches, but an allow-list/contact exception applies"
        }
    }

    private fun openDefaultAppsSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { toast("Open Android Settings and choose OEA as the default phone app.") }
    }

    private fun loadCallLog(missedOnly: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            addEmptyState("Call history permission is needed to show recent calls.", "Grant permission") {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG)
            }
            return
        }
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
        var count = 0
        runCatching {
            contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC")?.use { cursor ->
                while (cursor.moveToNext() && count < 100) {
                    val callType = cursor.getInt(2)
                    if (missedOnly && callType != CallLog.Calls.MISSED_TYPE) continue
                    count++
                    val phone = cursor.getString(1).orEmpty().ifBlank { "Unknown number" }
                    val dateMillis = cursor.getLong(3)
                    val duration = cursor.getLong(4).coerceAtLeast(0L)
                    val name = cursor.getString(5).orEmpty().ifBlank { phone }
                    val entry = CallLogRow(cursor.getLong(0), name, phone, callType, dateMillis, duration)
                    val callTypeText = callTypeLabel(callType)
                    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(dateMillis))
                    val blockStatus = callBlockStatus(phone)
                    val subtitle = "$phone • $callTypeText • ${durationLabel(duration)} • $date" +
                        (blockStatus?.let { " • $it" } ?: "")
                    addEntry(
                        name,
                        subtitle,
                        "Call",
                        action = { placeCall(phone) },
                        rowClick = { showCallDetails(entry) },
                    )
                }
            }
        }.onFailure { addEmptyState("Could not read call history on this device.") }
        if (count == 0 && list.childCount == 0) addEmptyState(if (missedOnly) "No missed calls found." else "No recent calls found.")
        addSectionAction("Open dial pad") {
            selectedTab = TAB_DIAL
            updateTabs()
            renderList()
        }
    }

    private fun callTypeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        else -> "Call"
    }

    private fun durationLabel(seconds: Long): String = when {
        seconds <= 0L -> "No duration"
        seconds < 60L -> "${seconds}s"
        else -> "${seconds / 60L}m ${seconds % 60L}s"
    }

    private fun showCallDetails(entry: CallLogRow) {
        val date = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.MEDIUM).format(Date(entry.date))
        val number = entry.number
        val defaultDialer = isDefaultDialer()
        val exactBlocked = OeaCallBlockRules.numberVariants(number)
            .any { it in OeaCallBlockRules.getExact(this) }
        val blocked = hasConfiguredBlockRule(number)
        val blockStatus = callBlockStatus(number)
        val blockAction = when {
            exactBlocked -> "Unblock"
            blocked -> "Edit block rules"
            else -> "Block"
        }
        val message = buildString {
            append(callTypeLabel(entry.type)).append(" call\n")
            append(number).append("\n")
            append(date).append("\n")
            append("Duration: ").append(durationLabel(entry.durationSeconds)).append("\n\n")
            append("Call recording is not provided by OEA. Recording availability depends on Android, the dialer and local rules.")
            blockStatus?.let { append("\n\nCall protection: ").append(it) }
            if (!defaultDialer) append("\n\nAndroid only allows call-log deletion to the default phone app when it also grants write access.")
        }
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(message)
            .setPositiveButton("Call") { _, _ -> placeCall(number) }
            .setNeutralButton(blockAction) { _, _ -> toggleCallBlock(number) }
            .setNegativeButton(if (defaultDialer) "Delete entry" else "Close") { _, _ ->
                if (defaultDialer) deleteCallHistoryEntry(entry.id)
            }
            .show()
    }

    private fun toggleCallBlock(number: String) {
        val normalized = OeaCallBlockRules.normalizeNumber(number)
        val variants = OeaCallBlockRules.numberVariants(number)
        if (normalized.isBlank() || variants.isEmpty()) {
            toast("This number can't be added to the block list.")
            return
        }
        val exact = OeaCallBlockRules.getExact(this).toMutableSet()
        val hasExactRule = variants.any(exact::contains)
        if (hasExactRule) {
            exact.removeAll(variants.toSet())
            OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
            val stillMatches = hasConfiguredBlockRule(number)
            toast(if (stillMatches) "Exact block removed; $number still matches another rule." else "Removed the exact block rule for $number.")
        } else if (hasConfiguredBlockRule(number)) {
            runCatching {
                startActivity(Intent(this, com.oea.launcher.callblocker.OeaCallBlockerActivity::class.java))
            }.onFailure { toast("Could not open Call Blocker rules.") }
            return
        } else {
            exact.add(normalized)
            OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
            OeaCallBlockRules.setEnabled(this, true)
            if (hasCallScreeningAccess()) {
                toast("Call block rule added for $number.")
            } else {
                toast("Rule saved. Enable OEA call screening before it can reject calls.")
                runCatching { startActivity(Intent(this, com.oea.launcher.callblocker.OeaCallBlockerActivity::class.java)) }
                    .onFailure { android.util.Log.w("OeaPhoneActivity", "Could not open Call Blocker rules", it) }
            }
        }
        renderList()
    }

    private fun deleteCallHistoryEntry(id: Long) {
        if (!isDefaultDialer()) {
            toast("Android requires OEA to be the default phone app before it can request call-history write access.")
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            pendingDeleteCallId = id
            requestPermissions(arrayOf(Manifest.permission.WRITE_CALL_LOG), REQUEST_WRITE_CALL_LOG)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete call history entry?")
            .setMessage("This removes this entry from Android's call log. It does not delete the contact or change call-blocking rules.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val deleted = runCatching {
                    contentResolver.delete(Uri.withAppendedPath(CallLog.Calls.CONTENT_URI, id.toString()), null, null) > 0
                }.getOrDefault(false)
                toast(if (deleted) "Call history entry deleted." else "Android did not allow deleting this entry.")
                renderList()
            }
            .show()
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
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 500) {
                    rows += ContactRow(cursor.getLong(0), cursor.getString(1).orEmpty().ifBlank { "Unknown contact" }, cursor.getString(2).orEmpty())
                }
            }
        }.onFailure { addEmptyState("Could not read contacts on this device.") }
        contactRows = rows.distinctBy { it.name.lowercase(Locale.ROOT) + "|" + it.phone }
    }

    private fun showContactDetails(contact: ContactRow) {
        val canReadCallLog = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val recentCalls = if (canReadCallLog) {
            runCatching {
                val uri = Uri.withAppendedPath(CallLog.Calls.CONTENT_FILTER_URI, Uri.encode(contact.phone))
                contentResolver.query(
                    uri,
                    arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                    null,
                    null,
                    CallLog.Calls.DATE + " DESC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext() && size < 5) {
                            val type = callTypeLabel(cursor.getInt(0))
                            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cursor.getLong(1)))
                            val duration = durationLabel(cursor.getLong(2))
                            add("$type • $duration • $date")
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
        } else emptyList()
        val details = buildString {
            append(contact.phone)
            append("\n\nRecent calls\n")
            if (!canReadCallLog) {
                append("Grant call-history access to see calls with this contact.")
            } else if (recentCalls.isEmpty()) {
                append("No recent calls found for this number.")
            } else {
                append(recentCalls.joinToString("\n"))
            }
        }
        val options = arrayOf("Call", "Edit in dial pad", "Send text message", "Open in Contacts")
        val dialog = AlertDialog.Builder(this)
            .setTitle(contact.name)
            .setMessage(details)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> placeCall(contact.phone)
                    1 -> fillNumber(contact.phone)
                    2 -> runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.phone, null))) }
                        .onFailure { toast("No messaging app is available.") }
                    3 -> runCatching {
                        val contactUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contact.id.toString())
                        startActivity(Intent(Intent.ACTION_VIEW, contactUri))
                    }.onFailure { toast("Could not open this contact in Android Contacts.") }
                }
            }
            .setNegativeButton("Close", null)
        if (!canReadCallLog) {
            dialog.setNeutralButton("Allow call history") { _, _ ->
                pendingContactHistory = contact
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG)
            }
        }
        dialog.show()
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
            addEntry(contact.name, contact.phone, "Call", action = { placeCall(contact.phone) }, rowClick = { showContactDetails(contact) })
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
        if (history.isNotEmpty()) addSectionAction("Clear OEA blocked-call history") {
            AlertDialog.Builder(this)
                .setTitle("Clear blocked-call history?")
                .setMessage("Only OEA's saved history will be removed. Your blocking rules and Android call log stay unchanged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    OeaCallBlockRules.clearBlockedHistory(this)
                    renderList()
                }
                .show()
        }
        addSectionAction("Open Call Blocker Rules") {
            runCatching {
                startActivity(Intent(this@OeaPhoneActivity, com.oea.launcher.callblocker.OeaCallBlockerActivity::class.java))
            }
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
            background = rounded(0xFF171E29.toInt(), 14)
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@OeaPhoneActivity).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
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
            background = rounded(0xFF151B26.toInt(), 16)
            if (action != null) {
                isClickable = true
                setOnClickListener { action() }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
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
            val tab = listOf(TAB_DIAL, TAB_RECENT, TAB_MISSED, TAB_CONTACTS, TAB_BLOCKED).getOrNull(i)
            val active = tab == selectedTab
            button.alpha = 1f
            button.isSelected = active
            button.setTextColor(0xFFFFFFFF.toInt())
            button.background = rounded(if (active) 0xFF2D8C74.toInt() else 0xFF1A212E.toInt(), 14)
        }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setPadding(dp(6), 0, dp(6), 0)
        minHeight = 0
        minimumHeight = 0
        setTextColor(0xFFFFFFFF.toInt())
        background = rounded(0xFF1E2431.toInt(), 14)
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        contentDescription = label
        setTextColor(0xFFFFFFFF.toInt())
        background = rounded(if (primary) 0xFF249E78.toInt() else 0xFF222A3A.toInt(), 18)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CALL_LOG -> {
                val contact = pendingContactHistory
                pendingContactHistory = null
                renderList()
                if (contact != null) {
                    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showContactDetails(contact)
                    else toast("Call-history access was not granted.")
                }
            }
            REQUEST_CONTACTS -> renderList()
            REQUEST_WRITE_CALL_LOG -> {
                val id = pendingDeleteCallId
                pendingDeleteCallId = null
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && id != null) deleteCallHistoryEntry(id)
                else toast("Android did not grant call-history write access.")
            }
            REQUEST_CALL_PHONE, REQUEST_PHONE_STATE -> {
                val raw = pendingCallNumber
                pendingCallNumber = null
                if (raw != null) {
                    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) placeCall(raw)
                    else openDialer(buildTelUri(raw))
                }
            }
            REQUEST_CAMERA -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    phonePrefs.edit().putBoolean("flash_alert", true).apply()
                    toast("Call flash alert enabled.")
                } else toast("Camera permission denied; flash alert remains off.")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DIALER_ROLE) {
            val defaultDialer = isDefaultDialer()
            status.text = if (defaultDialer) {
                "● Default phone app active • USSD & call protection ready"
            } else {
                "○ Default phone role was not granted"
            }
            renderList()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_DIAL = "dial"
        private const val TAB_RECENT = "recent"
        private const val TAB_MISSED = "missed"
        private const val TAB_CONTACTS = "contacts"
        private const val TAB_BLOCKED = "blocked"
        private const val REQUEST_DIALER_ROLE = 9002
        private const val REQUEST_CALL_LOG = 9003
        private const val REQUEST_CONTACTS = 9004
        private const val REQUEST_WRITE_CALL_LOG = 9008
        private const val REQUEST_CALL_PHONE = 9005
        private const val REQUEST_PHONE_STATE = 9006
        private const val REQUEST_CAMERA = 9007
    }
}
