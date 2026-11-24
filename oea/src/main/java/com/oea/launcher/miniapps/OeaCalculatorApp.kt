package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import kotlin.math.*

/**
 * OEA Calculator.
 *
 * The arithmetic lives in [OeaCalcEngine] — one evaluator, one formatter, one
 * programmer mode — and this file is the screen around it. What each mode is:
 *
 * - **Standard**: arithmetic, percent-of, brackets, memory (M+, M−, MR, MC) and `Ans`.
 * - **Scientific**: sin/cos/tan, log/ln/√, powers and factorial on the first page;
 *   a `2nd` key flips to the inverses, the hyperbolic functions, `10^x`, `e^x` and
 *   `x²`, which the evaluator could always do but no key could reach. `DEG`/`RAD` on
 *   screen, π and e as constants.
 * - **Programmer**: 64-bit integers, `0x`/`0b`/`0o` literals, AND/OR/XOR/NOT, shifts,
 *   `%` as modulo, and the current value shown in all four bases at once.
 * - **Converter**: ten families as exact factors against one base unit each, live as
 *   you type, decimals accepted, with swap and a send-to-display. Currency is a family
 *   of its own whose numbers you set and OEA keeps on the phone — no fetch is
 *   attempted, so nothing on screen pretends to be today's rate.
 * - **Graphing**: `y = f(x)` in radians, plotted on a grid anchored to the origin, with
 *   pinch zoom and drag.
 *
 * The tape behind all of it keeps the last hundred answers, searches them, reuses one
 * with a tap, shares them as text, and feeds the home-screen widget.
 */
object OeaCalculatorApp {

    private const val PREFS = "oea_calc_v2"
    private const val KEY_TAPE = "calculator_tape"
    private const val KEY_MEMORY = "calc_memory"
    private const val KEY_MODE = "calc_mode"
    private const val KEY_DEG = "calc_deg"
    private const val KEY_LAST = "calc_last"
    /** Read by the home-screen widget, which shows the newest answer. */
    private const val KEY_LAST_RESULT = "last_result"
    private const val TAPE_LIMIT = 100

    /**
     * Keys whose label is not their syntax. A keypad reads better as `1/x` and `x²`
     * than as `inv(` and `sqr(`, and the expression beside it still has to be
     * something the evaluator can parse.
     */
    private val KEY_INSERT = mapOf(
        "1/x" to "inv(",
        "x²" to "sqr(",
        "sin⁻¹(" to "asin(",
        "cos⁻¹(" to "acos(",
        "tan⁻¹(" to "atan("
    )

    enum class CalcMode(val label: String) {
        STANDARD("Standard"),
        SCIENTIFIC("Scientific"),
        PROGRAMMER("Programmer"),
        CONVERTER("Converter"),
        GRAPHING("Graphing")
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaAppKit(host, palette)
        val prefs = prefs(host)
        var expression = ""
        var result = ""
        var mode = runCatching { CalcMode.valueOf(prefs.getString(KEY_MODE, "STANDARD") ?: "STANDARD") }.getOrDefault(CalcMode.STANDARD)
        var isDeg = prefs.getBoolean(KEY_DEG, true)
        var memory = prefs.getString(KEY_MEMORY, "0")?.toDoubleOrNull() ?: 0.0
        var lastAnswer = prefs.getString(KEY_LAST, "")?.toDoubleOrNull()
        /** The scientific keypad's second page: inverse and hyperbolic functions. */
        var second = false
        var rebuildMode: () -> Unit = {}

        val indexBack = host.miniIndexBack()
        val navItems = listOf(
            OeaAppKit.NavItem(CalcMode.STANDARD.name, "Standard", R.drawable.oic_calculate),
            OeaAppKit.NavItem(CalcMode.SCIENTIFIC.name, "Scientific", R.drawable.oic_insights),
            OeaAppKit.NavItem(CalcMode.CONVERTER.name, "Convert", R.drawable.oic_splitscreen),
            OeaAppKit.NavItem(CalcMode.GRAPHING.name, "Graph", R.drawable.oic_show_chart)
        )
        val shell = kit.appShell(
            title = "Calculator",
            navItems = navItems,
            initialNav = if (mode == CalcMode.PROGRAMMER) CalcMode.STANDARD.name else mode.name,
            onBack = indexBack
        ) { id ->
            mode = runCatching { CalcMode.valueOf(id) }.getOrDefault(CalcMode.STANDARD)
            prefs.edit().putString(KEY_MODE, mode.name).apply()
            rebuildMode()
        }
        val column = shell.body

        host.miniBackHook = {
            if (mode == CalcMode.PROGRAMMER) {
                mode = CalcMode.STANDARD
                prefs.edit().putString(KEY_MODE, mode.name).apply()
                rebuildMode()
                true
            } else false
        }

        // Display
        val display = kit.text("0", 42f, palette.text, OeaType.light).apply {
            gravity = Gravity.END
            setPadding(kit.dp(20), kit.dp(22), kit.dp(20), kit.dp(4))
            setLineSpacing(kit.dpf(2f), 1f)
            setFontFeatureSettings("tnum")
            // A long expression wraps to two lines and then stops, instead of pushing
            // the keypad off the screen.
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        val hint = kit.text("", 15f, palette.textSecondary).apply {
            gravity = Gravity.END
            setPadding(kit.dp(20), 0, kit.dp(20), kit.dp(10))
            maxLines = 2
            setFontFeatureSettings("tnum")
        }
        // Programmer mode's whole point is the same number in four bases; the mode used
        // to promise HEX, BIN and OCT in its own hint and never show one of them.
        val basesView = kit.text("", 13f, palette.textSecondary).apply {
            gravity = Gravity.END
            setPadding(kit.dp(20), 0, kit.dp(20), kit.dp(16))
            setLineSpacing(kit.dpf(2f), 1f)
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
        }
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.card, 24, palette.stroke, 1)
            addView(display, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2))
            addView(basesView, LinearLayout.LayoutParams(-1, -2))
            isClickable = true
            setOnLongClickListener {
                copy(host, result.ifEmpty { expression.ifEmpty { "0" } })
                true
            }
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(0, kit.dp(12), 0, 0) }
        column.addView(content)

        /** The current expression as a number, in whichever arithmetic this mode uses. */
        fun evaluateNow(): Double? = when (mode) {
            CalcMode.PROGRAMMER -> OeaCalcEngine.programmer(expression)?.toDouble()
            else -> OeaCalcEngine.evaluate(expression, isDeg)
        }

        /** The programmer display: the same value in hex, decimal, octal and binary. */
        fun programmerBases(): String {
            val value = OeaCalcEngine.programmer(expression) ?: return ""
            return OeaCalcEngine.bases(value).joinToString("\n") { "${it.label}  ${it.text}" }
        }

        fun show() {
            display.text = expression.ifEmpty { "0" }
            display.textSize = when {
                expression.length > 18 -> 26f
                expression.length > 12 -> 33f
                else -> 42f
            }
            // A preview is a promise about what `=` will do, so it is only shown once
            // the expression can be one: while the last thing typed is an operator the
            // calculator used to preview `5×` as `= 5`, which is not what `=` gives.
            val typed = expression.lastOrNull()?.let { it !in "+−×÷^(" } == true
            val live = if (result.isEmpty() && typed && expression.length >= 2 && expression.any { it in "+−×÷^%!" }) {
                evaluateNow()?.let { format(it) }?.takeIf { it != expression }
            } else null
            val bases = if (mode == CalcMode.PROGRAMMER) programmerBases() else ""
            basesView.text = bases
            basesView.visibility = if (bases.isNotEmpty()) View.VISIBLE else View.GONE
            hint.text = when {
                result.isNotEmpty() -> "= $result"
                live != null -> "= $live"
                memory != 0.0 -> "M = ${format(memory)}" + if (mode == CalcMode.SCIENTIFIC) " · ${if (isDeg) "DEG" else "RAD"}" else ""
                mode == CalcMode.SCIENTIFIC -> (if (isDeg) "Degrees" else "Radians") + " · 2nd for inverses"
                mode == CalcMode.PROGRAMMER -> if (bases.isEmpty()) "HEX · DEC · OCT · BIN of the current value" else ""
                else -> ""
            }
        }

        /**
         * The tape, newest first, with the two things the app has always promised it
         * had and never did: a search box and a way to get the numbers out. It also
         * shows the whole tape — the sheet used to stop at thirty lines while the file
         * kept a hundred, so seventy answers were unreachable.
         */
        fun openTapeSheet() {
            val stored = prefs.getString(KEY_TAPE, "")
            val total = OeaCalcEngine.tapeLines(stored, "", TAPE_LIMIT).size
            kit.sheet(
                "Calculation history",
                if (total == 0) "Recent answers appear here" else "$total saved · search, reuse or share"
            ) { panel, dialog ->
                if (total == 0) {
                    kit.note(panel, "No calculations recorded yet.")
                } else {
                    val query = kit.field("Search history", "")
                    panel.addView(query, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

                    val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                    fun fill(text: String) {
                        list.removeAllViews()
                        val shown = OeaCalcEngine.tapeLines(stored, text, TAPE_LIMIT)
                        if (shown.isEmpty()) {
                            kit.note(list, "Nothing in the tape matches \"${text.trim()}\".")
                            return
                        }
                        val group = kit.group(list)
                        shown.forEach { line ->
                            val (expr, ans) = OeaCalcEngine.tapeParts(line)
                            kit.row(group, R.drawable.oic_history, ans.ifEmpty { line }, if (ans.isNotEmpty()) expr else null, value = if (ans.isNotEmpty()) "Use" else null) {
                                if (ans.isNotEmpty()) {
                                    expression = ans
                                    result = ""
                                    show()
                                    dialog.dismiss()
                                } else {
                                    copy(host, line)
                                }
                            }
                        }
                    }
                    fill("")
                    query.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(t: android.text.Editable?) = fill(t?.toString().orEmpty())
                    })
                    panel.addView(kit.boundedScroll(list, 0.46f))
                    panel.addView(
                        kit.buttonRow(
                            kit.button("Share tape", palette.cardHigh, palette.accent, R.drawable.oic_share_filled) {
                                shareTape(host, OeaCalcEngine.tapeLines(stored, "", TAPE_LIMIT))
                            },
                            kit.button("Clear", palette.cardHigh, palette.danger, R.drawable.oic_delete) {
                                prefs.edit().remove(KEY_TAPE).apply()
                                dialog.dismiss()
                            }
                        ),
                        LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(10) }
                    )
                }
            }
        }

        shell.bar.addAction(R.drawable.oic_file_copy, "Copy result") {
            copy(host, result.ifEmpty { expression.ifEmpty { "0" } })
        }
        shell.bar.addAction(R.drawable.oic_history, "History") { openTapeSheet() }
        shell.bar.addAction(R.drawable.oic_more_vert, "Calculator options") {
            kit.actions("Calculator options", null, listOf(
                OeaAppKit.Action(R.drawable.oic_developer_mode, "Programmer mode", "HEX, BIN, OCT and bitwise logic") {
                    mode = CalcMode.PROGRAMMER
                    prefs.edit().putString(KEY_MODE, mode.name).apply()
                    rebuildMode()
                },
                OeaAppKit.Action(R.drawable.oic_tune, "Angle unit", if (isDeg) "Degrees (DEG) · Tap for Radians" else "Radians (RAD) · Tap for Degrees") {
                    isDeg = !isDeg
                    prefs.edit().putBoolean(KEY_DEG, isDeg).apply()
                    show()
                },
                OeaAppKit.Action(R.drawable.oic_delete, "Clear memory", "Stored value: ${format(memory)}") {
                    memory = 0.0
                    prefs.edit().putString(KEY_MEMORY, "0").apply()
                    show()
                }
            ))
        }

        fun press(key: String) {
            when (key) {
                "AC", "C" -> { expression = ""; result = "" }
                "⌫" -> { expression = OeaCalcEngine.backspace(expression); result = "" }
                "( )" -> {
                    val open = expression.count { it == '(' }
                    val close = expression.count { it == ')' }
                    val last = expression.lastOrNull()
                    expression += if (open > close && (last != null && (last.isDigit() || last == ')' || last == 'π' || last == 'e'))) ")" else "("
                    result = ""
                }
                "=" -> {
                    val value = evaluateNow()
                    if (expression.isBlank()) {
                        result = ""
                    } else if (value == null) {
                        result = "Error"
                    } else {
                        val formatted = format(value)
                        val line = OeaCalcEngine.tapeLine(expression, value)
                        prefs.edit()
                            .putString(KEY_TAPE, OeaCalcEngine.pushTape(prefs.getString(KEY_TAPE, ""), line, TAPE_LIMIT))
                            .putString(KEY_LAST, value.toString())
                            .putString(KEY_LAST_RESULT, formatted)
                            .apply()
                        lastAnswer = value
                        result = ""
                        // Only hand the entry field a number it can continue from: the
                        // old code wrote the display string back unconditionally, which
                        // in a comma-decimal locale was a string the parser could not read.
                        if (OeaCalcEngine.reusable(formatted)) expression = formatted
                        runCatching { com.oea.launcher.widgets.OeaWidgetStudio.refreshWidgets(host, "calculator") }
                    }
                }
                "MC" -> { memory = 0.0; prefs.edit().putString(KEY_MEMORY, "0").apply() }
                "MR" -> { expression += format(memory); result = "" }
                "M+" -> evaluateNow()?.let { memory += it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() }
                "M-" -> evaluateNow()?.let { memory -= it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() }
                "DEG/RAD" -> { isDeg = !isDeg; prefs.edit().putBoolean(KEY_DEG, isDeg).apply() }
                "2nd" -> { second = !second; rebuildMode() }
                "Ans" -> {
                    val answer = lastAnswer
                    if (answer != null && expression.length < 200) {
                        result = ""
                        expression += format(answer)
                    }
                }
                else -> {
                    result = ""
                    if (expression.length < 200) expression += (KEY_INSERT[key] ?: key)
                }
            }
            show()
        }

        rebuildMode = {
            if (mode != CalcMode.PROGRAMMER) shell.nav?.select(mode.name, notify = false)
            shell.bar.setBack(
                if (mode == CalcMode.PROGRAMMER) ({
                    mode = CalcMode.STANDARD
                    prefs.edit().putString(KEY_MODE, mode.name).apply()
                    rebuildMode()
                }) else indexBack
            )
            shell.bar.title.text = when (mode) {
                CalcMode.STANDARD -> "Calculator"
                CalcMode.SCIENTIFIC -> "Scientific"
                CalcMode.PROGRAMMER -> "Programmer"
                CalcMode.CONVERTER -> "Convert"
                CalcMode.GRAPHING -> "Graph"
            }
            card.visibility = if (mode == CalcMode.CONVERTER || mode == CalcMode.GRAPHING) View.GONE else View.VISIBLE
            content.removeAllViews()
            when (mode) {
                CalcMode.STANDARD -> {
                    val memRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, kit.dp(4)) }
                    listOf("MC", "MR", "M+", "M-", "Ans").forEachIndexed { idx, key ->
                        memRow.addView(
                            keyButton(host, kit, key, small = true) { press(key) },
                            LinearLayout.LayoutParams(0, kit.dp(42), 1f).apply { if (idx > 0) leftMargin = kit.dp(8) }
                        )
                    }
                    content.addView(memRow)
                    val rows = listOf(
                        listOf("AC", "( )", "%", "÷"),
                        listOf("7", "8", "9", "×"),
                        listOf("4", "5", "6", "−"),
                        listOf("1", "2", "3", "+"),
                        listOf("0", ".", "⌫", "=")
                    )
                    rows.forEach { keys ->
                        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(8), 0, 0) }
                        keys.forEachIndexed { idx, key ->
                            row.addView(
                                keyButton(host, kit, key) { press(key) },
                                LinearLayout.LayoutParams(0, kit.dp(62), 1f).apply { if (idx > 0) leftMargin = kit.dp(8) }
                            )
                        }
                        content.addView(row)
                    }
                }
                CalcMode.SCIENTIFIC -> {
                    // The second page is what makes this a scientific calculator: the
                    // parser had inverse and hyperbolic functions all along, but no key
                    // could reach them, and "2nd" is how every real one offers them.
                    val trig = if (second) listOf("sin⁻¹(", "cos⁻¹(", "tan⁻¹(") else listOf("sin(", "cos(", "tan(")
                    val expo = if (second) listOf("10^(", "e^(") else listOf("log(", "ln(")
                    val root = if (second) "x²" else "√("
                    val rows = listOf(
                        listOf("2nd", "DEG/RAD", trig[0], trig[1], trig[2], "AC"),
                        listOf(expo[0], expo[1], root, "π", "e", "^"),
                        listOf("sinh(", "cosh(", "tanh(", "1/x", "Ans", "!"),
                        listOf("(", ")", "%", "÷", "×", "⌫"),
                        listOf("7", "8", "9", "−", "MC", "MR"),
                        listOf("4", "5", "6", "+", "M+", "M-"),
                        listOf("1", "2", "3", "0", ".", "=")
                    )
                    rows.forEach { keys ->
                        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(6), 0, 0) }
                        keys.forEachIndexed { idx, key ->
                            row.addView(
                                keyButton(host, kit, key, small = true) { press(key) },
                                LinearLayout.LayoutParams(0, kit.dp(52), 1f).apply { if (idx > 0) leftMargin = kit.dp(5) }
                            )
                        }
                        content.addView(row)
                    }
                }
                CalcMode.PROGRAMMER -> {
                    // Integer arithmetic: no decimal point (a Long has none), and `%` is
                    // modulo here because that is what a programmer reaches for.
                    val rows = listOf(
                        listOf("0x", "0b", "0o", "(", ")", "AC"),
                        listOf("A", "B", "C", "D", "E", "F"),
                        listOf("7", "8", "9", "AND", "OR", "⌫"),
                        listOf("4", "5", "6", "XOR", "NOT", "÷"),
                        listOf("1", "2", "3", "<<", ">>", "×"),
                        listOf("0", "Ans", "+", "−", "%", "=")
                    )
                    rows.forEach { keys ->
                        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(6), 0, 0) }
                        keys.forEachIndexed { idx, key ->
                            val label = if (key == "C") "C" else key
                            row.addView(
                                keyButton(host, kit, label, small = true) {
                                    if (key == "C") {
                                        result = ""
                                        if (expression.length < 200) expression += "C"
                                        show()
                                    } else press(key)
                                },
                                LinearLayout.LayoutParams(0, kit.dp(50), 1f).apply { if (idx > 0) leftMargin = kit.dp(5) }
                            )
                        }
                        content.addView(row)
                    }
                }
                CalcMode.CONVERTER -> {
                    var fam = OeaUnits.LENGTH
                    var fromIndex = 1
                    var toIndex = 0

                    // `numeric = true` alone gives a digits-only keyboard, so the converter
                    // could not accept 1.5 — the single most common thing to convert.
                    val amount = kit.field("Enter value", "1", numeric = true, decimal = true)
                    val out = TextView(host).apply {
                        textSize = 34f
                        typeface = OeaType.light
                        setTextColor(palette.text)
                        gravity = Gravity.END
                        setPadding(kit.dp(18), kit.dp(16), kit.dp(18), kit.dp(16))
                        background = kit.rounded(palette.card, 22, palette.stroke, 1)
                        setFontFeatureSettings("tnum")
                    }
                    val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

                    fun names(f: OeaUnits.Family): List<String> =
                        if (OeaUnits.isCurrency(f)) OeaUnits.CURRENCY_CODES else f.all.map { it.symbol }

                    fun convert(): Double? {
                        val value = amount.text.toString().replace(',', '.').toDoubleOrNull() ?: return null
                        val names = names(fam)
                        return when {
                            OeaUnits.isCurrency(fam) ->
                                OeaUnits.convertCurrency(host, names[fromIndex], names[toIndex], value)
                            OeaUnits.isTemperature(fam) ->
                                OeaUnits.temperature(value, names[fromIndex], names[toIndex])
                            else -> value * fam.all[fromIndex].toBase / fam.all[toIndex].toBase
                        }
                    }

                    fun refreshConv() {
                        val value = convert()
                        val names = names(fam)
                        val text = if (value == null) "—" else "${OeaUnits.show(value)} ${names[toIndex]}"
                        // Long conversions (a light year in nanometres) stay on two lines
                        // instead of pushing the pickers off the screen.
                        out.text = text
                        out.textSize = if (text.length > 18) 22f else 34f
                        out.maxLines = 2
                        out.ellipsize = android.text.TextUtils.TruncateAt.END
                    }

                    fun buildConv() {
                        body.removeAllViews()
                        val chips = LinearLayout(host).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 0, 0, kit.dp(10))
                        }
                        OeaUnits.families.forEach { f ->
                            chips.addView(
                                kit.chip(f.name, f.name == fam.name) {
                                    fam = f
                                    // Currency opens on USD → EUR: a pair someone can read,
                                    // not "1 USD = 1 USD".
                                    fromIndex = if (OeaUnits.isCurrency(f)) 0 else 1
                                    toIndex = if (OeaUnits.isCurrency(f)) 1 else 0
                                    buildConv()
                                },
                                LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(6) }
                            )
                        }
                        body.addView(kit.hscroll(chips))
                        body.addView(out, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
                        body.addView(amount, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
                        val group = kit.group(body)
                        kit.row(group, R.drawable.oic_arrow_back, "From", fam.name, value = names(fam)[fromIndex.coerceIn(0, names(fam).lastIndex)], chevron = true) {
                            val names = names(fam)
                            kit.choose("Convert from", fam.name, names, fromIndex.coerceIn(0, names.lastIndex)) { index ->
                                fromIndex = index
                                buildConv()
                            }
                        }
                        kit.row(group, R.drawable.oic_chevron_right, "To", fam.name, value = names(fam)[toIndex.coerceIn(0, names(fam).lastIndex)], chevron = true) {
                            val names = names(fam)
                            kit.choose("Convert to", fam.name, names, toIndex.coerceIn(0, names.lastIndex)) { index ->
                                toIndex = index
                                buildConv()
                            }
                        }
                        kit.row(group, R.drawable.oic_splitscreen, "Swap units", null, chevron = false) {
                            val t = fromIndex
                            fromIndex = toIndex
                            toIndex = t
                            buildConv()
                        }
                        kit.row(group, R.drawable.oic_calculate, "Send to Calculator", "Use result in Standard mode", chevron = true) {
                            val value = convert()
                            if (value == null) {
                                android.widget.Toast.makeText(host, "Enter a valid number first", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                expression = OeaUnits.show(value)
                                result = ""
                                mode = CalcMode.STANDARD
                                prefs.edit().putString(KEY_MODE, mode.name).apply()
                                rebuildMode()
                            }
                        }
                        if (OeaUnits.isCurrency(fam)) {
                            kit.row(group, R.drawable.oic_tune, "Edit exchange rates", "Saved offline on this device", chevron = true) {
                                editCurrencyRates(host, kit, palette)
                            }
                            kit.note(body, "Currency rates are saved offline — update them whenever you need current bank figures.")
                        }
                        refreshConv()
                    }

                    amount.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(t: android.text.Editable?) = refreshConv()
                    })
                    content.addView(body)
                    buildConv()
                }
                CalcMode.GRAPHING -> {
                    val graphCard = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        background = kit.rounded(palette.card, 22, palette.stroke, 1)
                        setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8))
                    }
                    val graphView = GraphView(host, palette)
                    graphCard.addView(graphView, LinearLayout.LayoutParams(-1, kit.dp(250)))
                    content.addView(graphCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })
                    val funcField = kit.field("y = f(x), e.g. sin(x) or x^2", "sin(x)")
                    content.addView(funcField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                    val presets = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, kit.dp(8)) }
                    listOf("sin(x)", "cos(x)", "tan(x)", "x^2", "x^3-2×x", "√(x)", "1/x").forEach { fn ->
                        presets.addView(
                            kit.chip(fn, false) {
                                funcField.setText(fn)
                                graphView.setFunction(fn)
                            },
                            LinearLayout.LayoutParams(-2, kit.dp(38)).apply { rightMargin = kit.dp(6) }
                        )
                    }
                    content.addView(kit.hscroll(presets))
                    val graphState = kit.text("", 12.5f, palette.textTertiary).apply {
                        setPadding(kit.dp(4), 0, kit.dp(4), kit.dp(8))
                    }
                    fun plot(text: String) {
                        val func = text.trim()
                        if (func.isEmpty()) {
                            graphState.text = "Type a function of x."
                            return
                        }
                        graphView.setFunction(func)
                        // Say so when the expression cannot be read, instead of quietly
                        // drawing yesterday's curve.
                        graphState.text = if (OeaCalcEngine.sample(func, 1.0) == null) {
                            "This is not something the calculator can plot."
                        } else {
                            "x is in radians · pinch to zoom, drag to pan"
                        }
                    }
                    content.addView(graphState)
                    content.addView(kit.primaryButton("Plot function", R.drawable.oic_show_chart) {
                        plot(funcField.text.toString())
                    }, LinearLayout.LayoutParams(-1, kit.dp(48)))
                    content.addView(
                        kit.button("Reset the view", palette.cardHigh, palette.accent, R.drawable.oic_refresh) {
                            graphView.reset()
                        },
                        LinearLayout.LayoutParams(-1, kit.dp(44)).apply { topMargin = kit.dp(8) }
                    )
                    funcField.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(t: android.text.Editable?) = plot(t?.toString().orEmpty())
                    })
                    plot(funcField.text.toString())
                }
            }
            show()
        }
        rebuildMode()
        return shell.root
    }

    /**
     * A plot of `y = f(x)`.
     *
     * Three things were wrong in the first version and are why this was rewritten:
     *
     * 1. the curve was evaluated in degrees, so the default `sin(x)` — a dozen degrees
     *    across the whole screen — was a nearly flat line, not a wave;
     * 2. the grid started at the view's corner while the curve was drawn from the
     *    centre, so a gridline was never where it claimed to be, and its spacing was
     *    decorative rather than a unit;
     * 3. the header advertised pinch zoom on a view that had no touch handler at all.
     */
    private class GraphView(ctx: Context, private val palette: OeaPalette) : View(ctx) {
        private var function: String = "sin(x)"
        /** Pixels per unit. */
        private var scale = 40f
        /** Where the origin sits, as an offset from the middle of the view, in pixels. */
        private var offsetX = 0f
        private var offsetY = 0f

        private val paintLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            strokeWidth = 4f
            style = android.graphics.Paint.Style.STROKE
        }
        private val paintGrid = android.graphics.Paint().apply { color = palette.stroke; strokeWidth = 1f }
        private val paintAxis = android.graphics.Paint().apply { color = palette.textSecondary; strokeWidth = 2f }
        private val paintLabel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textTertiary
            textSize = 22f
        }

        fun setFunction(f: String) {
            function = f.trim()
            invalidate()
        }

        fun reset() {
            scale = 40f
            offsetX = 0f
            offsetY = 0f
            invalidate()
        }

        private val scaler = android.view.ScaleGestureDetector(
            ctx,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val next = (scale * detector.scaleFactor).coerceIn(4f, 600f)
                    if (next == scale) return false
                    // Zoom about the fingers: the point under them must not move.
                    val focalX = detector.focusX - (width / 2f + offsetX)
                    val focalY = detector.focusY - (height / 2f + offsetY)
                    val ratio = next / scale
                    offsetX -= focalX * (ratio - 1f)
                    offsetY -= focalY * (ratio - 1f)
                    scale = next
                    invalidate()
                    return true
                }
            }
        )

        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            scaler.onTouchEvent(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lastX = event.x
                    lastY = event.y
                    // The graph scrolls and zooms itself; the screen must not steal the drag.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragging && !scaler.isInProgress && event.pointerCount == 1) {
                        offsetX += event.x - lastX
                        offsetY += event.y - lastY
                        lastX = event.x
                        lastY = event.y
                        invalidate()
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val ox = w / 2f + offsetX
            val oy = h / 2f + offsetY

            // Grid, anchored on the origin so a square is a unit and the curve crosses
            // the lines where the numbers say it does.
            var gx = ox - floor(ox / scale) * scale
            while (gx <= w) {
                canvas.drawLine(gx, 0f, gx, h, paintGrid)
                labelX(canvas, gx, ox, oy, h)
                gx += scale
            }
            var gy = oy - floor(oy / scale) * scale
            while (gy <= h) {
                canvas.drawLine(0f, gy, w, gy, paintGrid)
                gy += scale
            }
            if (oy in 0f..h) canvas.drawLine(0f, oy, w, oy, paintAxis)
            if (ox in 0f..w) canvas.drawLine(ox, 0f, ox, h, paintAxis)

            val path = android.graphics.Path()
            var pen = false
            var px = 0
            while (px <= width) {
                val x = (px - ox) / scale
                val y = OeaCalcEngine.sample(function, x.toDouble())
                val py = if (y == null || !y.isFinite() || abs(y) > 1e6) null else (oy - y.toFloat() * scale)
                // A break in the curve (a pole, a gap in the domain) starts a new path
                // instead of drawing a straight line across the discontinuity.
                if (py == null || py < -h * 3 || py > h * 4) {
                    pen = false
                } else if (pen) {
                    path.lineTo(px.toFloat(), py)
                } else {
                    path.moveTo(px.toFloat(), py)
                    pen = true
                }
                px += 2
            }
            canvas.drawPath(path, paintLine)
        }

        /** A tick label on the x axis: the world value of that gridline. */
        private fun labelX(canvas: android.graphics.Canvas, gx: Float, ox: Float, oy: Float, h: Float) {
            if (scale < 26f) return
            val value = (gx - ox) / scale
            if (abs(value) < 0.5f) return
            val text = OeaCalcEngine.format(value.roundToInt().toDouble())
            val baseline = (oy + 26f).coerceIn(26f, h - 6f)
            canvas.drawText(text, gx - paintLabel.measureText(text) / 2f, baseline, paintLabel)
        }
    }

    /**
     * Currency editor. Every number here is one the user typed and OEA kept, and
     * the screen says so: this build has no network call for rates, so it would be
     * a lie to show a "live" figure. Pick the base, then set what one unit of it
     * buys in each other currency.
     */
    private fun editCurrencyRates(host: Activity, kit: OeaKit, palette: OeaPalette) {
        fun showRates() {
            val rates = OeaUnits.rates(host)
            val base = OeaUnits.base(host)
            kit.sheet("Currency rates", "One $base = … · kept on this phone") { panel, _ ->
                kit.note(panel, "Nothing is fetched. These are the figures stored here — set them from your bank or a rate you trust, and they stay until you change them.")
                kit.row(kit.group(panel), R.drawable.oic_public, "Base currency", "Everything is measured against this", value = base, chevron = false) {
                    kit.choose("Base currency", null, OeaUnits.CURRENCY_CODES, OeaUnits.CURRENCY_CODES.indexOf(base).coerceAtLeast(0)) { index ->
                        val newBase = OeaUnits.CURRENCY_CODES[index]
                        val perOld = rates[base] ?: 1.0
                        val perNew = rates[newBase] ?: 1.0
                        OeaUnits.CURRENCY_CODES.forEach { code ->
                            OeaUnits.setRate(host, code, (rates[code] ?: perOld) / perOld * perNew)
                        }
                        OeaUnits.setBase(host, newBase)
                        showRates()
                    }
                }
                val group = kit.group(panel)
                OeaUnits.CURRENCY_CODES.forEach { code ->
                    val value = rates[code] ?: 1.0
                    kit.row(
                        group,
                        if (code == base) R.drawable.oic_star_fill else R.drawable.oic_circle,
                        code,
                        if (code == base) "the base — always 1" else "per $base",
                        value = if (code == base) "1" else OeaUnits.show(value),
                        chevron = false
                    ) {
                        if (code == base) {
                            android.widget.Toast.makeText(host, "$code is the base", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            kit.prompt("One $base in $code", "Set the rate you want OEA to use", "$code per $base", if (code == base) "1" else OeaUnits.show(value), numeric = true, decimal = true) { input ->
                                val parsed = input.replace(",", ".").toDoubleOrNull()
                                if (parsed == null || parsed <= 0.0) "That is not a rate" else {
                                    OeaUnits.setRate(host, code, parsed)
                                    null
                                }
                            }
                        }
                    }
                }
            }
        }
        showRates()
    }

    private fun keyButton(host: Activity, kit: OeaKit, key: String, small: Boolean = false, onClick: () -> Unit): View {
        val isEquals = key == "="
        val isClear = key == "AC"
        val isOperator = key in setOf("÷", "×", "−", "+", "( )", "(", ")", "%", "^", "!", "⌫", "AND", "OR", "XOR", "NOT", "<<", ">>") ||
            key.startsWith("sin") || key.startsWith("cos") || key.startsWith("tan") || key.startsWith("log") || key.startsWith("ln") || key.startsWith("√")
        val isMemory = key.startsWith("M") || key == "DEG/RAD" || key in setOf("0x", "0b", "0o")
        val text = TextView(host).apply {
            this.text = key
            gravity = Gravity.CENTER
            textSize = when {
                small && key.length > 3 -> 11.5f
                small -> 13.5f
                key.length > 3 -> 13f
                key.length > 1 -> 16f
                else -> 22f
            }
            typeface = if (isEquals || isOperator || isClear) OeaType.medium else OeaType.regular
            setTextColor(
                when {
                    isEquals -> kit.palette.onAccent
                    isClear -> kit.palette.danger
                    isMemory -> kit.palette.text
                    isOperator -> kit.palette.onAccentSoft
                    else -> kit.palette.text
                }
            )
            background = kit.ripple(
                kit.rounded(
                    when {
                        isEquals -> kit.palette.accent
                        isMemory || isClear -> kit.palette.cardHigh
                        isOperator -> kit.palette.accentSoft
                        else -> kit.palette.card
                    },
                    20,
                    if (isEquals) 0 else kit.palette.stroke,
                    if (isEquals) 0 else 1
                ),
                20
            )
            isClickable = true
            isFocusable = true
            contentDescription = when (key) {
                "⌫" -> "Backspace"
                "AC" -> "Clear all"
                "( )" -> "Brackets"
                else -> key
            }
            setOnClickListener { onClick() }
        }
        return text
    }

    /**
     * Hands the tape to any app that takes text. The header has promised an exportable
     * tape for as long as it has promised a searchable one; a share sheet is the export
     * that needs no storage permission and no new file to manage.
     */
    private fun shareTape(host: Activity, lines: List<String>) {
        if (lines.isEmpty()) {
            android.widget.Toast.makeText(host, "Nothing to share yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val body = "OEA calculator — ${lines.size} calculation${if (lines.size == 1) "" else "s"}\n\n" + lines.joinToString("\n")
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, body)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "OEA calculator history")
            }
            host.startActivity(android.content.Intent.createChooser(send, "Share history"))
        }
    }

    private fun copy(host: Activity, value: String) {
        runCatching {
            val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OEA calculator", value))
            android.widget.Toast.makeText(host, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One number formatter for the whole calculator. */
    private fun format(value: Double): String = OeaCalcEngine.format(value)
}
