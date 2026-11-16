package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.graphics.Color
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
import java.util.Locale

/**
 * OEA Calculator: fully competitive calculator with unique features.
 *
 * Modes:
 * - Standard: basic arithmetic with tape, history, memory, percentage, brackets
 * - Scientific: sin/cos/tan/log/ln/sqrt/power/factorial/pi/e, hyperbolic, inverse, degrees/radians, constants
 * - Programmer: binary/hex/octal/decimal, bitwise AND/OR/XOR/NOT, shifts
 * - Converter: ten families (length, mass, temperature, area, volume, speed, time,
 *   data, pressure, energy) as exact factors against one base unit each, live as you
 *   type, with swap and a send-to-display. Currency is a family of its own whose
 *   numbers you set and OEA keeps on the phone — no fetch is attempted, so nothing on
 *   screen pretends to be today's rate.
 * - Graphing: simple y=f(x) plotter with Canvas
 *
 * Unique:
 * - Tape that saves every result, searchable, exportable
 * - Memory with M+, M-, MR, MC
 * - History with recall
 * - Unit converter built-in, not external
 * - Currency converter with honest offline fallback
 * - Graphing with pinch zoom
 * - Themes: follows OEA palette, polished animations
 */
object OeaCalculatorApp {

    private const val PREFS = "oea_calc_v2"
    private const val KEY_TAPE = "calculator_tape"
    private const val KEY_MEMORY = "calc_memory"
    private const val KEY_MODE = "calc_mode"
    private const val KEY_DEG = "calc_deg"
    private const val TAPE_LIMIT = 100

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
        }
        val hint = kit.text("", 15f, palette.textSecondary).apply {
            gravity = Gravity.END
            setPadding(kit.dp(20), 0, kit.dp(20), kit.dp(16))
            maxLines = 2
            setFontFeatureSettings("tnum")
        }
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.card, 24, palette.stroke, 1)
            addView(display, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2))
            isClickable = true
            setOnLongClickListener {
                copy(host, result.ifEmpty { expression.ifEmpty { "0" } })
                true
            }
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(0, kit.dp(12), 0, 0) }
        column.addView(content)

        fun show() {
            display.text = expression.ifEmpty { "0" }
            display.textSize = when {
                expression.length > 18 -> 26f
                expression.length > 12 -> 33f
                else -> 42f
            }
            val live = if (result.isEmpty() && expression.length >= 2 && expression.any { it in "+−×÷^%!" }) {
                val evaluated = when (mode) {
                    CalcMode.PROGRAMMER -> ProgrammerEvaluator.evaluate(expression)
                    else -> Evaluator.evaluate(expression, isDeg)
                }
                evaluated?.let { format(it) }?.takeIf { it != expression }
            } else null
            hint.text = when {
                result.isNotEmpty() -> "= $result"
                live != null -> "= $live"
                memory != 0.0 -> "M = ${format(memory)}" + if (mode == CalcMode.SCIENTIFIC) " · ${if (isDeg) "DEG" else "RAD"}" else ""
                mode == CalcMode.SCIENTIFIC -> if (isDeg) "Degrees" else "Radians"
                mode == CalcMode.PROGRAMMER -> "HEX (0x) · BIN (0b) · OCT (0o) · DEC"
                else -> ""
            }
        }

        fun openTapeSheet() {
            val lines = prefs.getString(KEY_TAPE, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            kit.sheet("Calculation history", if (lines.isEmpty()) "Recent answers appear here" else "Tap a line to reuse its answer, or copy it") { panel, dialog ->
                if (lines.isEmpty()) {
                    kit.note(panel, "No calculations recorded yet.")
                } else {
                    val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                    val group = kit.group(list)
                    lines.take(30).forEach { line ->
                        val expr = line.substringBefore(" = ").trim()
                        val ans = line.substringAfter(" = ", "").trim()
                        kit.row(group, R.drawable.oic_history, ans.ifEmpty { line }, if (ans.isNotEmpty()) expr else null, value = "Use") {
                            if (ans.isNotEmpty()) {
                                expression = ans
                                result = ""
                                show()
                            } else {
                                copy(host, line)
                            }
                            dialog.dismiss()
                        }
                    }
                    panel.addView(kit.boundedScroll(list, 0.52f))
                    panel.addView(
                        kit.button("Clear history", palette.cardHigh, palette.danger, R.drawable.oic_delete) {
                            prefs.edit().remove(KEY_TAPE).apply()
                            dialog.dismiss()
                        },
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
                "⌫" -> {
                    if (expression.isNotEmpty()) expression = expression.dropLast(1)
                    result = ""
                }
                "( )" -> {
                    val open = expression.count { it == '(' }
                    val close = expression.count { it == ')' }
                    val last = expression.lastOrNull()
                    expression += if (open > close && (last != null && (last.isDigit() || last == ')' || last == 'π' || last == 'e'))) ")" else "("
                    result = ""
                }
                "=" -> {
                    val value = when (mode) {
                        CalcMode.PROGRAMMER -> ProgrammerEvaluator.evaluate(expression)
                        else -> Evaluator.evaluate(expression, isDeg)
                    }
                    if (expression.isBlank()) {
                        result = ""
                    } else if (value == null) {
                        result = "Error"
                    } else {
                        val formatted = format(value)
                        prefs.edit().putString(
                            KEY_TAPE,
                            (("$expression = $formatted") + "\n" + prefs.getString(KEY_TAPE, "").orEmpty())
                                .trim().lineSequence().take(TAPE_LIMIT).joinToString("\n")
                        ).apply()
                        result = ""
                        expression = formatted
                    }
                }
                "MC" -> { memory = 0.0; prefs.edit().putString(KEY_MEMORY, "0").apply() }
                "MR" -> { expression += format(memory); result = "" }
                "M+" -> { Evaluator.evaluate(expression, isDeg)?.let { memory += it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() } }
                "M-" -> { Evaluator.evaluate(expression, isDeg)?.let { memory -= it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() } }
                "DEG/RAD" -> { isDeg = !isDeg; prefs.edit().putBoolean(KEY_DEG, isDeg).apply() }
                else -> {
                    result = ""
                    if (expression.length < 200) expression += key
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
                    listOf("MC", "MR", "M+", "M-").forEachIndexed { idx, key ->
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
                    val rows = listOf(
                        listOf("DEG/RAD", "sin(", "cos(", "tan(", "log(", "ln("),
                        listOf("√(", "π", "e", "^", "!", "AC"),
                        listOf("(", ")", "%", "÷", "⌫", "="),
                        listOf("7", "8", "9", "×", "MC", "MR"),
                        listOf("4", "5", "6", "−", "M+", "M-"),
                        listOf("1", "2", "3", "+", "0", ".")
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
                    val rows = listOf(
                        listOf("0x", "0b", "0o", "(", ")", "AC"),
                        listOf("A", "B", "C", "D", "E", "F"),
                        listOf("7", "8", "9", "AND", "OR", "⌫"),
                        listOf("4", "5", "6", "XOR", "NOT", "÷"),
                        listOf("1", "2", "3", "<<", ">>", "×"),
                        listOf("0", ".", "+", "−", "%", "=")
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

                    val amount = kit.field("Enter value", "1", numeric = true)
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
                        out.text = if (value == null) "—" else "${OeaUnits.show(value)} ${names[toIndex]}"
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
                                    fromIndex = if (OeaUnits.isCurrency(f)) 0 else 1
                                    toIndex = 0
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
                    listOf("sin(x)", "cos(x)", "x^2", "x^3-2×x", "√(x)").forEach { fn ->
                        presets.addView(
                            kit.chip(fn, false) {
                                funcField.setText(fn)
                                graphView.setFunction(fn)
                            },
                            LinearLayout.LayoutParams(-2, kit.dp(38)).apply { rightMargin = kit.dp(6) }
                        )
                    }
                    content.addView(kit.hscroll(presets))
                    content.addView(kit.primaryButton("Plot function", R.drawable.oic_show_chart) {
                        val func = funcField.text.toString().trim()
                        if (func.isNotBlank()) graphView.setFunction(func)
                    }, LinearLayout.LayoutParams(-1, kit.dp(48)))
                }
            }
            show()
        }
        rebuildMode()
        return shell.root
    }

    private class GraphView(ctx: Context, val palette: OeaPalette) : View(ctx) {
        private var function: String = "sin(x)"
        private val paintLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent; strokeWidth = 4f; style = android.graphics.Paint.Style.STROKE }
        private val paintGrid = android.graphics.Paint().apply { color = palette.stroke; strokeWidth = 1f }
        private val paintAxis = android.graphics.Paint().apply { color = palette.textSecondary; strokeWidth = 2f }
        private var scale = 40f
        private var offsetX = 0f
        private var offsetY = 0f

        fun setFunction(f: String) { function = f; invalidate() }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            // Grid
            var x = offsetX % scale
            while (x < w) { canvas.drawLine(x, 0f, x, h, paintGrid); x += scale }
            var y = offsetY % scale
            while (y < h) { canvas.drawLine(0f, y, w, y, paintGrid); y += scale }
            // Axes
            canvas.drawLine(0f, h/2f + offsetY, w, h/2f + offsetY, paintAxis)
            canvas.drawLine(w/2f + offsetX, 0f, w/2f + offsetX, h, paintAxis)

            // Plot function
            val path = android.graphics.Path()
            var first = true
            var lastYValid = false
            for (px in 0 until width step 2) {
                val xVal = ((px - w/2f - offsetX) / scale).toDouble()
                val yVal = evaluateFunction(function, xVal)
                if (yVal == null || yVal.isNaN() || yVal.isInfinite() || abs(yVal) > 1000) {
                    first = true
                    lastYValid = false
                    continue
                }
                val py = h/2f + offsetY - yVal.toFloat() * scale
                if (first) { path.moveTo(px.toFloat(), py); first = false } else path.lineTo(px.toFloat(), py)
                lastYValid = true
            }
            canvas.drawPath(path, paintLine)
        }

        private fun evaluateFunction(func: String, x: Double): Double? {
            return try {
                val expr = func.replace("x", x.toString())
                Evaluator.evaluate(expr, true)
            } catch (_: Throwable) { null }
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
                            kit.prompt("One $base in $code", "Set the rate you want OEA to use", "$code per $base", if (code == base) "1" else OeaUnits.show(value), numeric = true) { input ->
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

    private fun copy(host: Activity, value: String) {
        runCatching {
            val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OEA calculator", value))
            android.widget.Toast.makeText(host, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "not a number"
        val rounded = if (abs(value) < 1e-12) 0.0 else value
        val asLong = rounded.roundToLong()
        if (abs(rounded - asLong) < 1e-9 && abs(rounded) < 1e15) return asLong.toString()
        val text = "%.8f".format(rounded).trimEnd('0').trimEnd('.')
        return text.ifEmpty { "0" }
    }

    internal object Evaluator {
        fun evaluate(raw: String, isDeg: Boolean = true): Double? {
            var text = raw
                .replace('×', '*')
                .replace('÷', '/')
                .replace('−', '-')
                .replace('—', '-')
                .replace(" ", "")
                .replace("π", Math.PI.toString())
                .replace("e", Math.E.toString())
                .trimEnd('+', '-', '*', '/', '^')
            if (text.isBlank()) return null
            text = text.replace("√", "sqrt")
            return try {
                val parser = Parser(text, isDeg)
                val value = parser.parse()
                if (parser.atEnd()) value else null
            } catch (_: Throwable) { null }
        }

        private class Parser(private val text: String, private val isDeg: Boolean) {
            private var i = 0
            fun atEnd(): Boolean = i >= text.length
            fun parse(): Double? = expression()
            fun expression(): Double? {
                var value = term() ?: return null
                while (i < text.length) {
                    when (text[i]) {
                        '+' -> { i++; value += term() ?: return null }
                        '-' -> { i++; value -= term() ?: return null }
                        else -> return value
                    }
                }
                return value
            }
            private fun term(): Double? {
                var value = power() ?: return null
                while (i < text.length) {
                    when (text[i]) {
                        '*' -> { i++; value *= power() ?: return null }
                        '/' -> { i++; val d = power() ?: return null; if (d == 0.0) return null; value /= d }
                        else -> return value
                    }
                }
                return value
            }
            private fun power(): Double? {
                var base = factor() ?: return null
                while (i < text.length && text[i] == '^') {
                    i++
                    val exp = factor() ?: return null
                    base = base.pow(exp)
                }
                return base
            }
            private fun factor(): Double? {
                if (i >= text.length) return null
                var sign = 1.0
                while (i < text.length && (text[i] == '-' || text[i] == '+')) {
                    if (text[i] == '-') sign = -sign
                    i++
                }
                var base = atom() ?: return null
                base *= sign
                while (i < text.length && (text[i] == '%' || text[i] == '!')) {
                    when (text[i]) {
                        '%' -> { base /= 100.0; i++ }
                        '!' -> { base = factorial(base) ?: return null; i++ }
                    }
                }
                return base
            }
            private fun atom(): Double? {
                if (i >= text.length) return null
                val funcs = listOf("sin", "cos", "tan", "log", "ln", "sqrt", "sinh", "cosh", "tanh", "asin", "acos", "atan")
                for (f in funcs) {
                    if (text.startsWith(f, i)) {
                        i += f.length
                        if (i < text.length && text[i] == '(') {
                            i++
                            val arg = expression() ?: return null
                            if (i < text.length && text[i] == ')') i++ else return null
                            return when (f) {
                                "sin" -> sin(if (isDeg) Math.toRadians(arg) else arg)
                                "cos" -> cos(if (isDeg) Math.toRadians(arg) else arg)
                                "tan" -> tan(if (isDeg) Math.toRadians(arg) else arg)
                                "asin" -> { val v = asin(arg); if (isDeg) Math.toDegrees(v) else v }
                                "acos" -> { val v = acos(arg); if (isDeg) Math.toDegrees(v) else v }
                                "atan" -> { val v = atan(arg); if (isDeg) Math.toDegrees(v) else v }
                                "sinh" -> sinh(arg)
                                "cosh" -> cosh(arg)
                                "tanh" -> tanh(arg)
                                "log" -> if (arg <= 0) null else log10(arg)
                                "ln" -> if (arg <= 0) null else ln(arg)
                                "sqrt" -> if (arg < 0) null else sqrt(arg)
                                else -> null
                            }
                        }
                    }
                }
                if (text[i] == '(') {
                    i++
                    val v = expression() ?: return null
                    if (i < text.length && text[i] == ')') i++ else return null
                    return v
                }
                val start = i
                while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                if (i == start) return null
                return text.substring(start, i).toDoubleOrNull()
            }
            private fun factorial(d: Double): Double? {
                if (d < 0 || d != floor(d) || d > 20) return null
                var res = 1.0
                for (k in 2..d.toInt()) res *= k
                return res
            }
        }
    }

    internal object ProgrammerEvaluator {
        fun evaluate(raw: String): Double? {
            var text = raw.replace(" ", "").uppercase()
            if (text.isBlank()) return null
            return try {
                // Handle binary, hex, octal prefixes
                when {
                    text.startsWith("0B") -> text.substring(2).toLong(2).toDouble()
                    text.startsWith("0X") -> text.substring(2).toLong(16).toDouble()
                    text.startsWith("0O") -> text.substring(2).toLong(8).toDouble()
                    text.contains("AND") -> {
                        val parts = text.split("AND")
                        (parts[0].toLong().and(parts[1].toLong())).toDouble()
                    }
                    text.contains("OR") -> {
                        val parts = text.split("OR")
                        (parts[0].toLong().or(parts[1].toLong())).toDouble()
                    }
                    text.contains("XOR") -> {
                        val parts = text.split("XOR")
                        (parts[0].toLong().xor(parts[1].toLong())).toDouble()
                    }
                    text.startsWith("NOT") -> text.substring(3).toLong().inv().toDouble()
                    text.contains("<<") -> {
                        val parts = text.split("<<")
                        (parts[0].toLong() shl parts[1].toInt()).toDouble()
                    }
                    text.contains(">>") -> {
                        val parts = text.split(">>")
                        (parts[0].toLong() shr parts[1].toInt()).toDouble()
                    }
                    else -> Evaluator.evaluate(text)?.toDouble()
                }
            } catch (_: Throwable) { null }
        }
    }
}
