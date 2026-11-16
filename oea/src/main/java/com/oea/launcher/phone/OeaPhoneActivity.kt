package com.oea.launcher.phone

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.callblocker.OeaCallBlockRules
import com.oea.launcher.callblocker.OeaCallBlockerActivity
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * OEA Phone provides Keypad (with USSD-safe URI encoding, live contact matching, and dual-SIM
 * selection), Recents (All & Missed), Contacts search, and Blocked call history.
 */
class OeaPhoneActivity : Activity() {
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaAppKit
    private lateinit var appBar: OeaAppKit.AppBar
    private lateinit var bottomNav: OeaAppKit.BottomNav
    private lateinit var number: EditText
    private lateinit var matchChip: LinearLayout
    private lateinit var matchLabel: TextView
    private lateinit var backspaceBtn: FrameLayout
    private lateinit var roleBannerHost: LinearLayout
    private lateinit var dialScroll: ScrollView
    private lateinit var listPane: LinearLayout
    private lateinit var recentsFilterHost: FrameLayout
    private lateinit var searchField: OeaAppKit.SearchField
    private lateinit var listScroll: ScrollView
    private lateinit var list: LinearLayout

    private var selectedTab = TAB_DIAL
    private var missedOnly = false
    private var contactQuery = ""
    private var contactRows: List<ContactRow> = emptyList()
    private var contactsLoaded = false
    private var pendingCallNumber: String? = null
    private var pendingDeleteCallId: Long? = null
    private var pendingContactHistory: ContactRow? = null
    private val phonePrefs by lazy { getSharedPreferences("oea_phone_prefs", Context.MODE_PRIVATE) }

    private data class ContactRow(val id: Long, val name: String, val phone: String, val digits: String, val t9: String)
    private data class CallLogRow(val id: Long, val name: String, val number: String, val type: Int, val date: Long, val durationSeconds: Long)

    override fun onCreate(state: Bundle?) {
        OeaFaultShield.guard("phone:create") {
            super.onCreate(state)
            val saved = state?.getString("phone_tab") ?: TAB_DIAL
            if (saved == TAB_MISSED) {
                selectedTab = TAB_RECENT
                missedOnly = true
            } else {
                selectedTab = saved
                missedOnly = state?.getBoolean("phone_missed", false) ?: false
            }
            contactQuery = state?.getString("phone_query").orEmpty()
            buildUi(state?.getString("phone_number").orEmpty())
            consumeDialIntent(intent)
        }
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
        outState.putString("phone_tab", if (selectedTab == TAB_RECENT && missedOnly) TAB_MISSED else selectedTab)
        outState.putBoolean("phone_missed", missedOnly)
        outState.putString("phone_query", contactQuery)
        if (::number.isInitialized) outState.putString("phone_number", number.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        OeaFaultShield.guard("phone:resume") {
            val fresh = OeaTheme.resolve(this)
            if (!::palette.isInitialized || fresh != palette) {
                val keepNumber = if (::number.isInitialized) number.text?.toString().orEmpty() else ""
                buildUi(keepNumber)
            } else {
                contactsLoaded = false
                refreshRoleBanner()
                renderActiveTab()
                updateDialAssist()
            }
        }
    }

    private fun buildUi(initialNumber: String) {
        palette = OeaTheme.resolve(this)
        kit = OeaAppKit(this, palette)
        OeaInsets.edgeToEdge(window, lightBars = !palette.dark)
        if (selectedTab !in setOf(TAB_DIAL, TAB_RECENT, TAB_CONTACTS, TAB_BLOCKED)) selectedTab = TAB_DIAL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        appBar = kit.appBar("Phone", insetTop = false)
        appBar.addAction(R.drawable.oic_shield, "Call Blocker") { openCallBlocker() }
        appBar.addAction(R.drawable.oic_more_vert, "Phone settings") { showPhoneSettingsSheet() }
        root.addView(appBar.root, LinearLayout.LayoutParams(-1, -2))

        val bodyFrame = FrameLayout(this)
        dialScroll = buildDialPane(initialNumber)
        listPane = buildListPane()
        bodyFrame.addView(dialScroll, FrameLayout.LayoutParams(-1, -1))
        bodyFrame.addView(listPane, FrameLayout.LayoutParams(-1, -1))
        root.addView(bodyFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        bottomNav = kit.bottomNav(
            items = listOf(
                OeaAppKit.NavItem(TAB_DIAL, "Keypad", R.drawable.oic_dialpad),
                OeaAppKit.NavItem(TAB_RECENT, "Recents", R.drawable.oic_history),
                OeaAppKit.NavItem(TAB_CONTACTS, "Contacts", R.drawable.oic_person),
                OeaAppKit.NavItem(TAB_BLOCKED, "Blocked", R.drawable.oic_block)
            ),
            initial = selectedTab
        ) { id ->
            val changed = selectedTab != id
            selectedTab = id
            if (changed && ::listScroll.isInitialized) kit.kit.resetScroll(listScroll)
            if (id != TAB_CONTACTS && ::searchField.isInitialized) kit.hideKeyboard(searchField.editor)
            renderActiveTab()
        }
        root.addView(bottomNav.root, LinearLayout.LayoutParams(-1, -2))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            root.setPadding(bars[0], bars[1], bars[2], 0)
            bottomNav.root.setPadding(0, 0, 0, bars[3])
            insets
        }

        setContentView(root)
        refreshRoleBanner()
        renderActiveTab()
        updateDialAssist()
    }

    private fun buildDialPane(initialNumber: String): ScrollView {
        val dialPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(18), kit.dp(4), kit.dp(18), kit.dp(14))
        }

        roleBannerHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialPanel.addView(roleBannerHost, LinearLayout.LayoutParams(-1, -2))

        dialPanel.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // Live contact match chip shown as user enters digits
        matchChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(14), kit.dp(8), kit.dp(14), kit.dp(8))
            background = kit.ripple(kit.rounded(palette.accentSoft, 20, OeaTheme.alpha(palette.accent, 0x44), 1), 20)
            visibility = View.INVISIBLE
            isClickable = true
            isFocusable = true
        }
        matchChip.addView(
            kit.iconView(R.drawable.oic_person, palette.onAccentSoft, 16),
            LinearLayout.LayoutParams(kit.dp(16), kit.dp(16)).apply { rightMargin = kit.dp(8) }
        )
        matchLabel = kit.text("", 13.5f, palette.onAccentSoft, OeaType.medium).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        matchChip.addView(matchLabel, LinearLayout.LayoutParams(-2, -2))
        dialPanel.addView(matchChip, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = kit.dp(6)
        })

        // Dialed number readout
        number = EditText(this).apply {
            hint = "Enter number or *code#"
            setText(initialNumber)
            setSelection(initialNumber.length)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE
            filters = emptyArray()
            showSoftInputOnFocus = false
            textSize = 30f
            typeface = OeaType.medium
            fontFeatureSettings = "tnum"
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            setHintTextColor(palette.textTertiary)
            background = ColorDrawable(Color.TRANSPARENT)
            contentDescription = "Phone number or USSD code"
            setPadding(kit.dp(8), kit.dp(6), kit.dp(8), kit.dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val len = s?.length ?: 0
                    textSize = when {
                        len > 15 -> 22f
                        len > 11 -> 26f
                        else -> 30f
                    }
                    updateDialAssist()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        dialPanel.addView(number, LinearLayout.LayoutParams(-1, kit.dp(56)).apply { bottomMargin = kit.dp(8) })

        // 3x4 tactile keypad
        val keypad = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
        }
        val keyDefs = listOf(
            "1" to "OO",
            "2" to "ABC",
            "3" to "DEF",
            "4" to "GHI",
            "5" to "JKL",
            "6" to "MNO",
            "7" to "PQRS",
            "8" to "TUV",
            "9" to "WXYZ",
            "*" to "",
            "0" to "+",
            "#" to "USSD"
        )
        keyDefs.forEachIndexed { index, (digit, sub) ->
            val key = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = kit.ripple(kit.rounded(palette.card, 26, palette.stroke, 1), 26)
                isClickable = true
                isFocusable = true
                contentDescription = when (digit) {
                    "*" -> "Dial star"
                    "#" -> "Dial pound or USSD"
                    "0" -> "Dial 0, hold for plus"
                    else -> if (sub.isNotEmpty() && sub != "OO") "Dial $digit, $sub" else "Dial $digit"
                }
                addView(kit.text(digit, 25f, palette.text, OeaType.medium).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    fontFeatureSettings = "tnum"
                })
                if (sub.isNotEmpty()) {
                    addView(kit.text(sub, 10f, palette.textSecondary, OeaType.medium).apply {
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        letterSpacing = 0.12f
                    })
                }
                setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    insertDigit(digit)
                }
                if (digit == "0") {
                    setOnLongClickListener { view ->
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        insertDigit("+")
                        true
                    }
                }
            }
            keypad.addView(key, GridLayout.LayoutParams().apply {
                width = 0
                height = kit.dp(62)
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(kit.dp(5), kit.dp(4), kit.dp(5), kit.dp(4))
            })
        }
        dialPanel.addView(keypad, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(14) })

        // Bottom call bar: [Paste]  ( CALL )  [Backspace]
        val callRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pasteBtn = FrameLayout(this).apply {
            background = kit.ripple(kit.oval(palette.card, palette.stroke, 1), 26)
            contentDescription = "Paste number"
            isClickable = true
            isFocusable = true
            addView(
                kit.iconView(R.drawable.oic_file_copy, palette.textSecondary, 20),
                FrameLayout.LayoutParams(kit.dp(20), kit.dp(20), Gravity.CENTER)
            )
            setOnClickListener { pasteFromClipboard() }
        }
        val leftSlot = FrameLayout(this).apply {
            addView(pasteBtn, FrameLayout.LayoutParams(kit.dp(50), kit.dp(50), Gravity.CENTER))
        }

        val callColor = palette.success
        val callBtn = FrameLayout(this).apply {
            background = kit.ripple(kit.oval(callColor), 34)
            elevation = kit.dpf(3f)
            contentDescription = "Place call"
            isClickable = true
            isFocusable = true
            addView(
                kit.iconView(R.drawable.oic_call, OeaTheme.onColor(callColor), 28),
                FrameLayout.LayoutParams(kit.dp(28), kit.dp(28), Gravity.CENTER)
            )
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                placeCall()
            }
        }
        val centerSlot = FrameLayout(this).apply {
            addView(callBtn, FrameLayout.LayoutParams(kit.dp(68), kit.dp(68), Gravity.CENTER))
        }

        backspaceBtn = FrameLayout(this).apply {
            background = kit.ripple(kit.oval(palette.card, palette.stroke, 1), 26)
            contentDescription = "Delete digit, hold to clear"
            isClickable = true
            isFocusable = true
            visibility = if (initialNumber.isEmpty()) View.INVISIBLE else View.VISIBLE
            addView(
                kit.iconView(R.drawable.oic_backspace, palette.text, 21),
                FrameLayout.LayoutParams(kit.dp(21), kit.dp(21), Gravity.CENTER)
            )
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                deleteDigit()
            }
            setOnLongClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                number.setText("")
                true
            }
        }
        val rightSlot = FrameLayout(this).apply {
            addView(backspaceBtn, FrameLayout.LayoutParams(kit.dp(50), kit.dp(50), Gravity.CENTER))
        }

        callRow.addView(leftSlot, LinearLayout.LayoutParams(0, kit.dp(72), 1f))
        callRow.addView(centerSlot, LinearLayout.LayoutParams(0, kit.dp(72), 1f))
        callRow.addView(rightSlot, LinearLayout.LayoutParams(0, kit.dp(72), 1f))
        dialPanel.addView(callRow, LinearLayout.LayoutParams(-1, -2))

        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(dialPanel, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildListPane(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        recentsFilterHost = FrameLayout(this).apply {
            setPadding(kit.dp(16), kit.dp(2), kit.dp(16), kit.dp(6))
            visibility = View.GONE
        }
        pane.addView(recentsFilterHost, LinearLayout.LayoutParams(-1, -2))

        searchField = kit.searchField("Search contacts or numbers", contactQuery, onQuery = { q ->
            contactQuery = q
            if (selectedTab == TAB_CONTACTS) renderContacts()
        })
        searchField.root.visibility = View.GONE
        pane.addView(searchField.root, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = kit.dp(16)
            rightMargin = kit.dp(16)
            topMargin = kit.dp(2)
            bottomMargin = kit.dp(8)
        })

        listScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
        }
        list = kit.kit.scrollSafeColumn(listScroll).apply {
            setPadding(kit.dp(16), kit.dp(4), kit.dp(16), kit.dp(20))
        }
        listScroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        pane.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return pane
    }

    private fun refreshRoleBanner() {
        if (!::roleBannerHost.isInitialized) return
        roleBannerHost.removeAllViews()
        if (!isDefaultDialer()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(14), kit.dp(10), kit.dp(12), kit.dp(10))
                background = kit.ripple(
                    kit.rounded(
                        OeaTheme.blend(palette.card, palette.accent, if (palette.dark) 0.14f else 0.08f),
                        18,
                        OeaTheme.alpha(palette.accent, 0x3A),
                        1
                    ),
                    18
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { requestDefaultDialer() }
            }
            row.addView(
                kit.iconView(R.drawable.oic_call, palette.accent, 18),
                LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)).apply { rightMargin = kit.dp(10) }
            )
            row.addView(
                kit.text("Set as default Phone app", 13f, palette.text, OeaType.medium),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(kit.text("Enable", 13f, palette.accent, OeaType.medium))
            roleBannerHost.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun renderActiveTab() {
        if (!::list.isInitialized) return
        bottomNav.select(selectedTab, notify = false)
        val onDial = selectedTab == TAB_DIAL
        dialScroll.visibility = if (onDial) View.VISIBLE else View.GONE
        listPane.visibility = if (onDial) View.GONE else View.VISIBLE
        recentsFilterHost.visibility = if (selectedTab == TAB_RECENT) View.VISIBLE else View.GONE
        searchField.root.visibility = if (selectedTab == TAB_CONTACTS) View.VISIBLE else View.GONE

        appBar.title.text = when (selectedTab) {
            TAB_RECENT -> "Recents"
            TAB_CONTACTS -> "Contacts"
            TAB_BLOCKED -> "Blocked"
            else -> "Phone"
        }
        appBar.setSubtitle(
            when (selectedTab) {
                TAB_DIAL -> if (isDefaultDialer()) "Default phone · USSD ready" else null
                TAB_RECENT -> if (missedOnly) "Missed calls" else "All recent calls"
                TAB_CONTACTS -> null
                TAB_BLOCKED -> "Screened & rejected calls"
                else -> null
            }
        )

        list.removeAllViews()
        when (selectedTab) {
            TAB_DIAL -> ensureContactsLoadedQuietly()
            TAB_RECENT -> {
                recentsFilterHost.removeAllViews()
                recentsFilterHost.addView(
                    kit.chipBar(
                        listOf("all" to "All calls", "missed" to "Missed"),
                        if (missedOnly) "missed" else "all"
                    ) { key ->
                        missedOnly = key == "missed"
                        renderActiveTab()
                    },
                    FrameLayout.LayoutParams(-1, -2)
                )
                loadCallLog(missedOnly)
            }
            TAB_CONTACTS -> {
                loadContacts()
                renderContacts()
            }
            TAB_BLOCKED -> loadBlockedHistory()
        }
    }

    private fun ensureContactsLoadedQuietly() {
        if (contactsLoaded) return
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        loadContacts()
    }

    private fun updateDialAssist() {
        if (!::number.isInitialized || !::backspaceBtn.isInitialized) return
        val raw = number.text?.toString().orEmpty()
        backspaceBtn.visibility = if (raw.isEmpty()) View.INVISIBLE else View.VISIBLE
        val digits = raw.filter(Char::isDigit)
        if (digits.length < 2 || raw.contains('*') || raw.contains('#')) {
            matchChip.visibility = View.INVISIBLE
            matchChip.setOnClickListener(null)
            return
        }
        ensureContactsLoadedQuietly()
        val match = contactRows.firstOrNull { row ->
            row.digits.contains(digits) || (digits.length >= 3 && row.t9.startsWith(digits))
        }
        if (match == null) {
            matchChip.visibility = View.INVISIBLE
            matchChip.setOnClickListener(null)
        } else {
            matchLabel.text = "${match.name} · ${match.phone}"
            matchChip.visibility = View.VISIBLE
            matchChip.setOnClickListener {
                number.setText(match.phone)
                number.setSelection(number.text.length)
            }
        }
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        val cleaned = text.filter { it.isDigit() || it in "+*#;, ()-" }.trim()
        if (cleaned.isEmpty()) {
            toast("No phone number on clipboard")
            return
        }
        number.setText(cleaned)
        number.setSelection(number.text.length)
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

    /** Builds a tel: URI preserving '#' as '%23' so USSD codes (*323#, *312#, etc.) reach Telecom intact. */
    private fun buildTelUri(raw: String): Uri = Uri.fromParts("tel", raw, null)

    private fun placeCall(value: String = number.text.toString().trim()) {
        val raw = value.trim()
        if (raw.isEmpty()) {
            toast("Enter a phone number or USSD code")
            selectedTab = TAB_DIAL
            renderActiveTab()
            return
        }
        val uri = buildTelUri(raw)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = raw
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE)
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
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
        showSimSheet(raw, uri, telecom, accounts)
    }

    private fun showSimSheet(
        raw: String,
        uri: Uri,
        telecom: TelecomManager?,
        accounts: List<PhoneAccountHandle>
    ) {
        val savedId = phonePrefs.getString("preferred_phone_account", null)
        val subtitle = if (raw.endsWith("#")) "USSD / MMI code · $raw" else "Calling $raw"
        kit.sheet("Choose SIM for this call", subtitle) { panel, dialog ->
            accounts.forEachIndexed { index, handle ->
                val account = runCatching { telecom?.getPhoneAccount(handle) }.getOrNull()
                val label = account?.label?.toString().orEmpty().ifBlank { "SIM ${index + 1}" }
                val address = account?.shortDescription?.toString().orEmpty()
                val isPreferred = handle.id == savedId
                val simCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = kit.dp(60)
                    setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12))
                    background = kit.ripple(
                        kit.rounded(
                            if (isPreferred) palette.accentSoft else palette.card,
                            20,
                            if (isPreferred) palette.accent else palette.stroke,
                            1
                        ),
                        20
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        phonePrefs.edit().putString("preferred_phone_account", handle.id).apply()
                        dialog.dismiss()
                        startCallWithAccount(raw, handle)
                    }
                }
                val badge = FrameLayout(this).apply {
                    background = kit.rounded(palette.accent, 13)
                    addView(kit.text("${index + 1}", 15f, palette.onAccent, OeaType.medium).apply {
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                }
                simCard.addView(badge, LinearLayout.LayoutParams(kit.dp(36), kit.dp(36)).apply { rightMargin = kit.dp(12) })

                val fg = if (isPreferred) palette.onAccentSoft else palette.text
                val subFg = if (isPreferred) OeaTheme.alpha(palette.onAccentSoft, 0xCC) else palette.textSecondary
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text(if (isPreferred) "$label · Last used" else label, 15.5f, fg, OeaType.medium))
                    if (address.isNotBlank() && address != label) {
                        addView(kit.text(address, 12.5f, subFg))
                    }
                }
                simCard.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
                simCard.addView(kit.iconView(R.drawable.oic_call, if (isPreferred) palette.onAccentSoft else palette.accent, 20),
                    LinearLayout.LayoutParams(kit.dp(20), kit.dp(20)))
                panel.addView(simCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            }
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.tonalButton("System default") {
                    dialog.dismiss()
                    runCatching { telecom?.placeCall(uri, Bundle()) ?: openDialer(uri) }
                        .onFailure { openDialer(uri) }
                }
            ))
        }
    }

    private fun startCallWithAccount(raw: String, handle: PhoneAccountHandle) {
        val uri = buildTelUri(raw)
        runCatching {
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            getSystemService(TelecomManager::class.java).placeCall(uri, extras)
        }.onFailure {
            toast("Could not start call on selected SIM; opening system dialer")
            openDialer(uri)
        }
    }

    private fun showPhoneSettingsSheet() {
        val modeKey = phonePrefs.getString("call_ui_mode", "fullscreen") ?: "fullscreen"
        val modeLabel = when (modeKey) {
            "popup" -> "Popup window"
            "compact" -> "Compact floating card"
            else -> "Full screen"
        }
        val flashOn = phonePrefs.getBoolean("flash_alert", false)
        kit.actions("Phone settings", null, listOf(
            OeaAppKit.Action(
                R.drawable.oic_call,
                "Default phone app",
                if (isDefaultDialer()) "OEA is active as your default dialer" else "Tap to set OEA as default phone app"
            ) { requestDefaultDialer() },
            OeaAppKit.Action(
                R.drawable.oic_open_in_full,
                "Call display style",
                modeLabel
            ) { showCallOptions() },
            OeaAppKit.Action(
                R.drawable.oic_flash_on,
                "Incoming-call flash alert",
                if (flashOn) "Enabled — camera flash blinks while ringing" else "Off — tap to turn on"
            ) { showFlashOptions() },
            OeaAppKit.Action(
                R.drawable.oic_shield,
                "Call Blocker",
                "Manage blocked numbers, prefixes and allow-list"
            ) { openCallBlocker() }
        ))
    }

    private fun showCallOptions() {
        val modes = listOf("Full screen", "Popup window", "Compact floating card")
        val details = listOf(
            "Over your wallpaper (always used when phone is locked)",
            "A floating card over the app you're using",
            "Minimal banner at the top so you can keep working"
        )
        val keys = listOf("fullscreen", "popup", "compact")
        val current = phonePrefs.getString("call_ui_mode", "fullscreen") ?: "fullscreen"
        kit.choose("Call display style", "You can also switch views during a call", modes, keys.indexOf(current).coerceAtLeast(0), details) { which ->
            phonePrefs.edit().putString("call_ui_mode", keys[which]).apply()
            toast("${modes[which]} selected")
        }
    }

    private fun showFlashOptions() {
        val currentlyOn = phonePrefs.getBoolean("flash_alert", false)
        kit.confirm(
            "Incoming-call flash alert",
            "Blink the camera flash while an incoming call rings. Needs Camera permission and pauses while another app uses the camera.",
            if (currentlyOn) "Turn off" else "Turn on"
        ) {
            if (currentlyOn) {
                phonePrefs.edit().putBoolean("flash_alert", false).apply()
                toast("Call flash alert turned off")
            } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                phonePrefs.edit().putBoolean("flash_alert", true).apply()
                toast("Call flash alert enabled")
            } else {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            }
        }
    }

    private fun openCallBlocker() {
        runCatching { startActivity(Intent(this, OeaCallBlockerActivity::class.java)) }
            .onFailure { toast("Could not open Call Blocker") }
    }

    private fun openDialer(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri)) }
            .onFailure { toast("Unable to open dialer for this number") }
    }

    private fun requestDefaultDialer() {
        if (isDefaultDialer()) {
            toast("OEA is already your default phone app")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
        }
    }.getOrDefault(false)

    private fun hasCallScreeningAccess(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            !OeaCallBlockRules.enabled(this) -> "Rule paused"
            !hasCallScreeningAccess() -> "Screening setup needed"
            OeaCallBlockRules.shouldBlock(this, number) -> "Blocked"
            else -> "Allowed by exception"
        }
    }

    private fun openDefaultAppsSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { toast("Open Settings › Default apps › Phone") }
    }

    private fun loadCallLog(onlyMissed: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            list.addView(
                kit.emptyState(
                    R.drawable.oic_history,
                    "See your call history",
                    "Grant call-log permission to browse recent and missed calls.",
                    "Grant access"
                ) { requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG) },
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )
        val rows = mutableListOf<CallLogRow>()
        runCatching {
            contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC")?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 100) {
                    val callType = cursor.getInt(2)
                    if (onlyMissed && callType != CallLog.Calls.MISSED_TYPE) continue
                    val phone = cursor.getString(1).orEmpty().ifBlank { "Unknown number" }
                    val dateMillis = cursor.getLong(3)
                    val duration = cursor.getLong(4).coerceAtLeast(0L)
                    val name = cursor.getString(5).orEmpty().ifBlank { phone }
                    rows += CallLogRow(cursor.getLong(0), name, phone, callType, dateMillis, duration)
                }
            }
        }.onFailure {
            list.addView(kit.emptyState(R.drawable.oic_warning_fill, "Could not read call history", "Try again or check Phone permissions."))
            return
        }

        if (rows.isEmpty()) {
            list.addView(
                kit.emptyState(
                    R.drawable.oic_history,
                    if (onlyMissed) "No missed calls" else "No recent calls",
                    if (onlyMissed) "You're all caught up." else "Calls you make and receive appear here.",
                    "Open keypad"
                ) {
                    selectedTab = TAB_DIAL
                    renderActiveTab()
                },
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }

        val group = kit.group(list)
        rows.forEachIndexed { idx, entry ->
            val isMissed = entry.type == CallLog.Calls.MISSED_TYPE
            val isRejected = entry.type == CallLog.Calls.REJECTED_TYPE
            val badgeColor = when {
                isMissed -> palette.danger
                isRejected -> palette.warning
                entry.type == CallLog.Calls.INCOMING_TYPE -> palette.success
                else -> palette.accent
            }
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.date))
            val blockStatus = callBlockStatus(entry.number)
            val meta = buildString {
                append(callTypeLabel(entry.type))
                if (entry.durationSeconds > 0L) append(" · ").append(durationLabel(entry.durationSeconds))
                append(" · ").append(date)
                blockStatus?.let { append(" · ").append(it) }
            }
            addCallRow(
                parent = group,
                showDivider = idx > 0,
                iconRes = if (isMissed || isRejected) R.drawable.oic_call_end else R.drawable.oic_call,
                badgeTone = badgeColor,
                title = entry.name,
                titleColor = if (isMissed) palette.danger else palette.text,
                subtitle = if (entry.name != entry.number) "${entry.number} · $meta" else meta,
                onRowClick = { showCallDetails(entry) },
                onCallClick = if (entry.number != "Unknown number") ({ placeCall(entry.number) }) else null
            )
        }
    }

    private fun callTypeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        else -> "Call"
    }

    private fun durationLabel(seconds: Long): String = when {
        seconds <= 0L -> "0s"
        seconds < 60L -> "${seconds}s"
        else -> "${seconds / 60L}m ${seconds % 60L}s"
    }

    private fun showCallDetails(entry: CallLogRow) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.date))
        val target = entry.number
        val canDial = target.isNotBlank() && target != "Unknown number"
        val defaultDialer = isDefaultDialer()
        val exactBlocked = OeaCallBlockRules.numberVariants(target).any { it in OeaCallBlockRules.getExact(this) }
        val blocked = hasConfiguredBlockRule(target)
        val blockStatus = callBlockStatus(target)
        val subtitle = buildString {
            append(callTypeLabel(entry.type))
            if (entry.durationSeconds > 0L) append(" · ").append(durationLabel(entry.durationSeconds))
            append(" · ").append(date)
            if (entry.name != target) append("\n").append(target)
            blockStatus?.let { append(" · ").append(it) }
        }
        val items = mutableListOf<OeaAppKit.Action>()
        if (canDial) {
            items += OeaAppKit.Action(R.drawable.oic_call, "Call", target) { placeCall(target) }
            items += OeaAppKit.Action(R.drawable.oic_send, "Send message", "Open SMS conversation") { sendSms(target) }
            items += OeaAppKit.Action(R.drawable.oic_dialpad, "Edit in keypad", "Modify before calling") { fillNumber(target) }
            items += OeaAppKit.Action(R.drawable.oic_file_copy, "Copy number") { copyNumber(target) }
            items += OeaAppKit.Action(
                R.drawable.oic_block,
                when {
                    exactBlocked -> "Unblock number"
                    blocked -> "Manage block rules"
                    else -> "Block number"
                },
                blockStatus ?: "Reject future calls from this number"
            ) { toggleCallBlock(target) }
        }
        if (defaultDialer) {
            items += OeaAppKit.Action(R.drawable.oic_delete, "Delete call entry", "Remove from call history", destructive = true) {
                deleteCallHistoryEntry(entry.id)
            }
        }
        kit.actions(entry.name, subtitle, items)
    }

    private fun toggleCallBlock(target: String) {
        val normalized = OeaCallBlockRules.normalizeNumber(target)
        val variants = OeaCallBlockRules.numberVariants(target)
        if (normalized.isBlank() || variants.isEmpty()) {
            toast("This number can't be added to the block list")
            return
        }
        val exact = OeaCallBlockRules.getExact(this).toMutableSet()
        val hasExactRule = variants.any(exact::contains)
        if (hasExactRule) {
            exact.removeAll(variants.toSet())
            OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
            val stillMatches = hasConfiguredBlockRule(target)
            toast(if (stillMatches) "Exact block removed; $target still matches a pattern rule" else "Unblocked $target")
        } else if (hasConfiguredBlockRule(target)) {
            openCallBlocker()
            return
        } else {
            exact.add(normalized)
            OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
            OeaCallBlockRules.setEnabled(this, true)
            if (hasCallScreeningAccess()) {
                toast("Blocked $target")
            } else {
                toast("Rule saved. Enable Call Screening to start filtering calls.")
                runCatching { startActivity(Intent(this, OeaCallBlockerActivity::class.java)) }
                    .onFailure { Log.w("OeaPhoneActivity", "Could not open Call Blocker rules", it) }
            }
        }
        renderActiveTab()
    }

    private fun deleteCallHistoryEntry(id: Long) {
        if (!isDefaultDialer()) {
            toast("Set OEA as your default Phone app to delete call-log entries")
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            pendingDeleteCallId = id
            requestPermissions(arrayOf(Manifest.permission.WRITE_CALL_LOG), REQUEST_WRITE_CALL_LOG)
            return
        }
        kit.confirm(
            "Delete call entry?",
            "Removes this call from your history. Saved contacts and block rules stay unchanged.",
            "Delete",
            destructive = true
        ) {
            val deleted = runCatching {
                contentResolver.delete(Uri.withAppendedPath(CallLog.Calls.CONTENT_URI, id.toString()), null, null) > 0
            }.getOrDefault(false)
            toast(if (deleted) "Call entry deleted" else "Could not delete this entry")
            renderActiveTab()
        }
    }

    private fun loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            contactRows = emptyList()
            contactsLoaded = false
            return
        }
        val rows = mutableListOf<ContactRow>()
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 500) {
                    val name = cursor.getString(1).orEmpty().ifBlank { "Unnamed contact" }
                    val phone = cursor.getString(2).orEmpty().trim()
                    if (phone.isNotEmpty()) {
                        rows += ContactRow(cursor.getLong(0), name, phone, phone.filter(Char::isDigit), toT9(name))
                    }
                }
            }
        }
        contactRows = rows.distinctBy { it.name.lowercase(Locale.ROOT) + "|" + it.digits.ifEmpty { it.phone } }
        contactsLoaded = true
    }

    private fun renderContacts() {
        if (!::list.isInitialized || selectedTab != TAB_CONTACTS) return
        list.removeAllViews()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            list.addView(
                kit.emptyState(
                    R.drawable.oic_person,
                    "Browse your contacts",
                    "Grant contacts permission to search and call saved numbers.",
                    "Grant access"
                ) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS) },
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }
        val query = contactQuery.trim().lowercase(Locale.ROOT)
        val numericQuery = query.filter(Char::isDigit)
        val filtered = contactRows.filter {
            query.isEmpty() ||
                it.name.lowercase(Locale.ROOT).contains(query) ||
                (numericQuery.isNotEmpty() && it.digits.contains(numericQuery))
        }
        appBar.setSubtitle(
            if (query.isEmpty()) "${contactRows.size} ${if (contactRows.size == 1) "contact" else "contacts"}"
            else "${filtered.size} matching"
        )
        if (filtered.isEmpty()) {
            list.addView(
                kit.emptyState(
                    R.drawable.oic_person,
                    if (query.isEmpty()) "No contacts found" else "No matches for \"$contactQuery\"",
                    if (query.isEmpty()) "Saved phone contacts appear here." else "Try a different name or dial directly.",
                    if (numericQuery.isNotEmpty()) "Dial $numericQuery" else null
                ) { fillNumber(numericQuery) },
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }
        val group = kit.group(list)
        filtered.forEachIndexed { idx, contact ->
            addContactRow(
                group,
                idx > 0,
                contact,
                action = { placeCall(contact.phone) },
                rowClick = { showContactDetails(contact) }
            )
        }
    }

    private fun addContactRow(
        parent: LinearLayout,
        showDivider: Boolean,
        contact: ContactRow,
        action: () -> Unit,
        rowClick: (() -> Unit)? = null
    ) {
        if (showDivider) parent.addView(kit.divider(64))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = kit.dp(62)
            setPadding(kit.dp(14), kit.dp(10), kit.dp(10), kit.dp(10))
            background = kit.ripple(ColorDrawable(Color.TRANSPARENT), 18)
            isClickable = rowClick != null
            isFocusable = rowClick != null
            setOnClickListener { rowClick?.invoke() }
        }
        val initial = contact.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        val avatar = FrameLayout(this).apply {
            background = kit.oval(palette.accentSoft)
            addView(kit.text(initial, 15f, palette.onAccentSoft, OeaType.medium).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        row.addView(avatar, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(12) })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.text(contact.name, 15.5f, palette.text, OeaType.medium).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(kit.text(contact.phone, 12.5f, palette.textSecondary).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(callIconButton("Call ${contact.name}", action), LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)))
        parent.addView(row)
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
                    CallLog.Calls.DATE + " DESC"
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext() && size < 3) {
                            val type = callTypeLabel(cursor.getInt(0))
                            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cursor.getLong(1)))
                            val dur = cursor.getLong(2)
                            add(if (dur > 0L) "$type · ${durationLabel(dur)} · $date" else "$type · $date")
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
        } else emptyList()

        val subtitle = if (recentCalls.isEmpty()) contact.phone else contact.phone + "\nLatest: " + recentCalls.first()
        val items = mutableListOf(
            OeaAppKit.Action(R.drawable.oic_call, "Call", contact.phone) { placeCall(contact.phone) },
            OeaAppKit.Action(R.drawable.oic_send, "Send text message") { sendSms(contact.phone) },
            OeaAppKit.Action(R.drawable.oic_dialpad, "Edit in keypad") { fillNumber(contact.phone) },
            OeaAppKit.Action(R.drawable.oic_file_copy, "Copy number") { copyNumber(contact.phone) },
            OeaAppKit.Action(R.drawable.oic_open_in_new, "Open in Contacts") {
                runCatching {
                    val contactUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contact.id.toString())
                    startActivity(Intent(Intent.ACTION_VIEW, contactUri))
                }.onFailure { toast("Could not open system Contacts") }
            }
        )
        if (!canReadCallLog) {
            items += OeaAppKit.Action(R.drawable.oic_history, "Show call history", "Grant call-log permission") {
                pendingContactHistory = contact
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQUEST_CALL_LOG)
            }
        }
        kit.actions(contact.name, subtitle, items)
    }

    private fun loadBlockedHistory() {
        if (!hasCallScreeningAccess() || !OeaCallBlockRules.enabled(this)) {
            kit.banner(
                list,
                R.drawable.oic_shield,
                if (!hasCallScreeningAccess()) "Call screening needs setup" else "Call blocking is paused",
                "Open Call Blocker to manage block rules, prefixes and screening permissions.",
                OeaKit.Tone.WARNING,
                "Configure"
            ) { openCallBlocker() }
        } else {
            val rulesCount = OeaCallBlockRules.getExact(this).size +
                OeaCallBlockRules.getPrefix(this).size +
                OeaCallBlockRules.getSuffix(this).size
            val topGroup = kit.group(list)
            kit.row(topGroup, R.drawable.oic_shield_fill, "Call Blocker active", "$rulesCount ${if (rulesCount == 1) "rule" else "rules"} protecting incoming calls", value = "Manage") {
                openCallBlocker()
            }
        }

        val history = OeaCallBlockRules.blockedHistory(this)
        if (history.isEmpty()) {
            list.addView(
                kit.emptyState(
                    R.drawable.oic_block,
                    "No blocked calls yet",
                    "Calls rejected by OEA Call Blocker appear here.",
                    "Manage rules"
                ) { openCallBlocker() },
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }

        kit.sectionTitle(list, "Blocked history")
        val group = kit.group(list)
        history.forEachIndexed { idx, entry ->
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))
            addCallRow(
                parent = group,
                showDivider = idx > 0,
                iconRes = R.drawable.oic_block,
                badgeTone = palette.danger,
                title = entry.number,
                titleColor = palette.text,
                subtitle = "Blocked · $date",
                onRowClick = { fillNumber(entry.number) },
                onCallClick = { placeCall(entry.number) }
            )
        }
        list.addView(
            kit.button("Clear blocked history", palette.cardHigh, palette.danger, R.drawable.oic_delete) {
                kit.confirm(
                    "Clear blocked-call history?",
                    "Removes saved blocked-call entries. Your block rules and Android call log stay intact.",
                    "Clear",
                    destructive = true
                ) {
                    OeaCallBlockRules.clearBlockedHistory(this)
                    renderActiveTab()
                }
            },
            LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) }
        )
    }

    private fun addCallRow(
        parent: LinearLayout,
        showDivider: Boolean,
        iconRes: Int,
        badgeTone: Int,
        title: String,
        titleColor: Int,
        subtitle: String,
        onRowClick: () -> Unit,
        onCallClick: (() -> Unit)?
    ) {
        if (showDivider) parent.addView(kit.divider(64))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = kit.dp(64)
            setPadding(kit.dp(14), kit.dp(10), kit.dp(10), kit.dp(10))
            background = kit.ripple(ColorDrawable(Color.TRANSPARENT), 18)
            isClickable = true
            isFocusable = true
            setOnClickListener { onRowClick() }
        }
        val badge = FrameLayout(this).apply {
            background = kit.oval(OeaTheme.alpha(badgeTone, if (palette.dark) 0x2E else 0x1E))
            addView(
                kit.iconView(iconRes, badgeTone, 19),
                FrameLayout.LayoutParams(kit.dp(19), kit.dp(19), Gravity.CENTER)
            )
        }
        row.addView(badge, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(12) })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.text(title, 15.5f, titleColor, OeaType.medium).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(kit.text(subtitle, 12.5f, palette.textSecondary).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        if (onCallClick != null) {
            row.addView(callIconButton("Call $title", onCallClick), LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)))
        }
        parent.addView(row)
    }

    private fun callIconButton(description: String, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        val circle = FrameLayout(context).apply {
            background = kit.ripple(kit.oval(OeaTheme.alpha(palette.success, if (palette.dark) 0x2C else 0x1C)), 20)
            addView(
                ImageView(context).apply { setImageDrawable(kit.icon(R.drawable.oic_call, palette.success)) },
                FrameLayout.LayoutParams(kit.dp(19), kit.dp(19), Gravity.CENTER)
            )
        }
        addView(circle, FrameLayout.LayoutParams(kit.dp(38), kit.dp(38), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun sendSms(target: String) {
        runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", target, null))) }
            .onFailure { toast("No messaging app available") }
    }

    private fun copyNumber(target: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Phone number", target))
        toast("Number copied")
    }

    private fun fillNumber(value: String) {
        selectedTab = TAB_DIAL
        renderActiveTab()
        number.setText(value)
        number.setSelection(number.text.length)
    }

    private fun toT9(name: String): String = buildString(name.length) {
        for (ch in name.lowercase(Locale.ROOT)) {
            when (ch) {
                in 'a'..'c' -> append('2')
                in 'd'..'f' -> append('3')
                in 'g'..'i' -> append('4')
                in 'j'..'l' -> append('5')
                in 'm'..'o' -> append('6')
                in 'p'..'s' -> append('7')
                in 't'..'v' -> append('8')
                in 'w'..'z' -> append('9')
                in '0'..'9' -> append(ch)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CALL_LOG -> {
                val contact = pendingContactHistory
                pendingContactHistory = null
                renderActiveTab()
                if (contact != null) {
                    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showContactDetails(contact)
                    else toast("Call-history access not granted")
                }
            }
            REQUEST_CONTACTS -> {
                contactsLoaded = false
                renderActiveTab()
                updateDialAssist()
            }
            REQUEST_WRITE_CALL_LOG -> {
                val id = pendingDeleteCallId
                pendingDeleteCallId = null
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && id != null) deleteCallHistoryEntry(id)
                else toast("Call-history write access not granted")
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
                    toast("Call flash alert enabled")
                } else toast("Camera permission denied; flash alert remains off")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DIALER_ROLE) {
            toast(if (isDefaultDialer()) "OEA is now your default phone app" else "Default phone role not granted")
            refreshRoleBanner()
            renderActiveTab()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

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
