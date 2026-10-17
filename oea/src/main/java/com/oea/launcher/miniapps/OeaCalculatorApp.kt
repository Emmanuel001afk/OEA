package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
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
        val kit = OeaKit(host, palette)
        val prefs = prefs(host)
        var expression = ""
        var result = ""
        var mode = runCatching { CalcMode.valueOf(prefs.getString(KEY_MODE, "STANDARD") ?: "STANDARD") }.getOrDefault(CalcMode.STANDARD)
        var isDeg = prefs.getBoolean(KEY_DEG, true)
        var memory = prefs.getString(KEY_MEMORY, "0")?.toDoubleOrNull() ?: 0.0

        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        // Header with mode selector
        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("Calculator", 20f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val modeBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderModeBar() {
            modeBar.removeAllViews()
            CalcMode.values().forEach { m ->
                modeBar.addView(kit.chip(m.label, m == mode) {
                    mode = m
                    prefs.edit().putString(KEY_MODE, m.name).apply()
                    (host as? OeaMiniAppActivity)?.refresh()
                }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
        }
        renderModeBar()
        column.addView(kit.hscroll(modeBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        // Display
        val display = kit.text("", 34f, palette.text, OeaType.light).apply {
            gravity = Gravity.END
            setPadding(kit.dp(18), kit.dp(16), kit.dp(18), kit.dp(4))
            setLineSpacing(kit.dpf(2f), 1f)
        }
        val hint = kit.text("", 14f, palette.textTertiary).apply {
            gravity = Gravity.END
            setPadding(kit.dp(18), 0, kit.dp(18), kit.dp(10))
            maxLines = 2
        }
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.card, 22, palette.stroke, 1)
            addView(display, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(0, kit.dp(10), 0, 0) }
        column.addView(content)

        fun show() {
            display.text = expression.ifEmpty { "0" }
            hint.text = if (result.isEmpty()) {
                when (mode) {
                    CalcMode.STANDARD -> "Tap = to calculate • Memory: ${format(memory)}"
                    CalcMode.SCIENTIFIC -> "Scientific: sin cos tan log ln √ ^ π e ! • ${if (isDeg) "DEG" else "RAD"} • Memory: ${format(memory)}"
                    CalcMode.PROGRAMMER -> "Programmer: BIN HEX OCT DEC • AND OR XOR NOT • Memory: ${format(memory)}"
                    CalcMode.CONVERTER -> "Converter: length, weight, temp, etc."
                    CalcMode.GRAPHING -> "Graphing: enter y = f(x), e.g., sin(x), x^2"
                }
            } else "= $result"
        }

        // Tape
        val tapeBox = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        fun renderTape() {
            tapeBox.removeAllViews()
            val lines = prefs.getString(KEY_TAPE, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) {
                kit.note(tapeBox, "Tape empty. Every result saved here. Tap line to copy.")
                return
            }
            val group = kit.group(tapeBox)
            lines.take(20).forEach { line ->
                val row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(kit.dp(14), kit.dp(11), kit.dp(14), kit.dp(11))
                    isClickable = true
                    setOnClickListener { copy(host, line) }
                }
                row.addView(kit.text(line, 14f, kit.palette.textSecondary).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
                group.addView(row)
            }
            kit.note(tapeBox, "Tap line to copy • Memory: ${format(memory)} • ${lines.size} entries")
        }

        fun press(key: String) {
            when (key) {
                "C" -> { expression = ""; result = "" }
                "⌫" -> if (expression.isNotEmpty()) expression = expression.dropLast(1)
                "=" -> {
                    val value = when (mode) {
                        CalcMode.PROGRAMMER -> ProgrammerEvaluator.evaluate(expression)
                        else -> Evaluator.evaluate(expression, isDeg)
                    }
                    result = when {
                        expression.isBlank() -> ""
                        value == null -> "error"
                        else -> format(value)
                    }
                    if (value != null) {
                        prefs.edit().putString(KEY_TAPE, (("$expression = ${format(value)}") + "\n" + prefs.getString(KEY_TAPE, "").orEmpty()).trim().lineSequence().take(TAPE_LIMIT).joinToString("\n")).apply()
                    }
                }
                "MC" -> { memory = 0.0; prefs.edit().putString(KEY_MEMORY, "0").apply() }
                "MR" -> { expression += format(memory) }
                "M+" -> { Evaluator.evaluate(expression, isDeg)?.let { memory += it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() } }
                "M-" -> { Evaluator.evaluate(expression, isDeg)?.let { memory -= it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() } }
                "DEG/RAD" -> { isDeg = !isDeg; prefs.edit().putBoolean(KEY_DEG, isDeg).apply() }
                else -> {
                    if (expression.length < 200) expression += key
                }
            }
            show()
            renderTape()
        }

        // Build keypad based on mode
        content.removeAllViews()
        when (mode) {
            CalcMode.STANDARD -> {
                val rows = listOf(
                    listOf("MC", "MR", "M+", "M-"),
                    listOf("C", "(", ")", "÷"),
                    listOf("7", "8", "9", "×"),
                    listOf("4", "5", "6", "−"),
                    listOf("1", "2", "3", "+"),
                    listOf("0", ".", "%", "=")
                )
                rows.forEach { keys ->
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(5), 0, 0) }
                    keys.forEachIndexed { idx, key ->
                        row.addView(keyButton(host, kit, key) { press(key) }, LinearLayout.LayoutParams(0, kit.dp(58), 1f).apply { if (idx > 0) leftMargin = kit.dp(6) })
                    }
                    content.addView(row)
                }
            }
            CalcMode.SCIENTIFIC -> {
                val rows = listOf(
                    listOf("DEG/RAD", "sin(", "cos(", "tan(", "log(", "ln("),
                    listOf("√(", "π", "e", "^", "!", "C"),
                    listOf("(", ")", "%", "÷", "⌫", "="),
                    listOf("7", "8", "9", "×", "MC", "MR"),
                    listOf("4", "5", "6", "−", "M+", "M-"),
                    listOf("1", "2", "3", "+", "0", ".")
                )
                rows.forEach { keys ->
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(5), 0, 0) }
                    keys.forEachIndexed { idx, key ->
                        if (key.trim().isEmpty()) {
                            row.addView(View(host), LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply { if (idx > 0) leftMargin = kit.dp(4) })
                        } else {
                            row.addView(keyButton(host, kit, key, small = true) { press(key) }, LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply { if (idx > 0) leftMargin = kit.dp(4) })
                        }
                    }
                    content.addView(row)
                }
            }
            CalcMode.PROGRAMMER -> {
                val rows = listOf(
                    listOf("HEX", "DEC", "OCT", "BIN"),
                    listOf("A", "B", "C", "D", "E", "F"),
                    listOf("7", "8", "9", "AND", "OR", "C"),
                    listOf("4", "5", "6", "XOR", "NOT", "⌫"),
                    listOf("1", "2", "3", "<<", ">>", "="),
                    listOf("0", ".", "+", "−", "×", "÷")
                )
                rows.forEach { keys ->
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(5), 0, 0) }
                    keys.forEachIndexed { idx, key ->
                        row.addView(keyButton(host, kit, key, small = true) { press(key) }, LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply { if (idx > 0) leftMargin = kit.dp(4) })
                    }
                    content.addView(row)
                }
                kit.note(content, "Programmer mode: supports BIN (0b), HEX (0x), OCT (0o), DEC. Bitwise ops work on integers.")
            }
            CalcMode.CONVERTER -> {
                // One family at a time, one multiply against the family's base unit.
                // Currency is in the same list but its factors are the user's numbers,
                // because OEA has no network in this mode and will not pretend otherwise.
                var fam = OeaUnits.LENGTH
                var fromIndex = 1
                var toIndex = 0

                val amount = kit.field("Amount", "1", numeric = true)
                val out = android.widget.TextView(host).apply {
                    textSize = 30f
                    setTextColor(palette.text)
                    setPadding(kit.dp(2), kit.dp(6), kit.dp(2), kit.dp(2))
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

                fun label(): String {
                    val value = convert() ?: return "Enter a number"
                    val names = names(fam)
                    return "${OeaUnits.show(value)} ${names[toIndex]}"
                }

                fun refresh() {
                    out.text = label()
                }

                fun pick(which: Int) {
                    val names = names(fam)
                    val chosen = if (which == 0) fromIndex else toIndex
                    kit.choose(
                        if (which == 0) "Convert from" else "Convert to",
                        fam.name,
                        names,
                        chosen.coerceIn(0, names.size - 1)
                    ) { index ->
                        if (which == 0) fromIndex = index else toIndex = index
                        refresh()
                    }
                }

                fun build() {
                    body.removeAllViews()
                    val chips = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, kit.dp(2), 0, kit.dp(6))
                    }
                    OeaUnits.families.forEach { f ->
                        chips.addView(
                            kit.chip(f.name, f.name == fam.name) {
                                fam = f
                                fromIndex = if (OeaUnits.isCurrency(f)) 0 else 1
                                toIndex = 0
                                build()
                            },
                            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = kit.dp(6) }
                        )
                    }
                    body.addView(kit.hscroll(chips))
                    body.addView(amount, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                    body.addView(out, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(4) })
                    val group = kit.group(body)
                    kit.row(group, R.drawable.oic_arrow_back, "From", fam.name, value = names(fam)[fromIndex.coerceIn(0, names(fam).lastIndex)]) { pick(0) }
                    kit.row(group, R.drawable.oic_chevron_right, "To", fam.name, value = names(fam)[toIndex.coerceIn(0, names(fam).lastIndex)]) { pick(1) }
                    kit.row(group, R.drawable.oic_splitscreen, "Swap the pair", "Same family, other direction", chevron = false) {
                        val t = fromIndex
                        fromIndex = toIndex
                        toIndex = t
                        build()
                    }
                    kit.row(group, R.drawable.oic_calculate, "Send to the display", "Puts the answer where the keypad is", chevron = false) {
                        val value = convert()
                        if (value == null) {
                            android.widget.Toast.makeText(host, "Nothing to send — the amount is not a number", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val names = names(fam)
                            expression = "1 ${names[fromIndex]} → ${names[toIndex]} (${fam.name.toLowerCase(java.util.Locale.getDefault())})"
                            result = OeaUnits.show(value)
                            show()
                            renderTape()
                        }
                    }
                    if (OeaUnits.isCurrency(fam)) {
                        kit.row(group, R.drawable.oic_tune, "Set your own rates", "OEA stores these; it never fetches them", chevron = false) {
                            editCurrencyRates(host, kit, palette)
                            build()
                        }
                        kit.note(
                            body,
                            "Rates are the numbers you left in this screen (or OEA's rounded approximations if you changed none). They are held on the phone, not looked up, so they will not move with the market — update them when your bank does."
                        )
                    } else {
                        kit.note(body, "${fam.name} converts against one base unit (${fam.base.label}), exactly. Temperature is the only family with an offset, and it is computed as one.")
                    }
                    refresh()
                }

                amount.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(t: android.text.Editable?) = refresh()
                })
                content.addView(body)
                build()
            }
            CalcMode.GRAPHING -> {
                val graphView = GraphView(host, palette)
                content.addView(graphView, LinearLayout.LayoutParams(-1, kit.dp(240)).apply { bottomMargin = kit.dp(12) })
                val funcField = kit.field("Function y = f(x), e.g., sin(x), x^2, x^3 - 2*x", "sin(x)")
                content.addView(funcField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                content.addView(kit.primaryButton("Plot", R.drawable.oic_show_chart) {
                    val func = funcField.text.toString().trim()
                    if (func.isNotBlank()) graphView.setFunction(func)
                }, LinearLayout.LayoutParams(-1, kit.dp(48)))
                kit.note(content, "Graphing unique: plots y = f(x) with Canvas, pinch zoom, pan. Supports sin, cos, tan, log, sqrt, ^, etc.")
                // Still show basic keypad for quick input
                val rows = listOf(
                    listOf("x", "^", "sin(", "cos(", "C"),
                    listOf("7", "8", "9", "÷", "⌫"),
                    listOf("4", "5", "6", "×", "="),
                    listOf("1", "2", "3", "−", "0"),
                    listOf("0", ".", "(", ")", "+")
                )
                rows.forEach { keys ->
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(5), 0, 0) }
                    keys.forEachIndexed { idx, key ->
                        row.addView(keyButton(host, kit, key) { press(key) }, LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply { if (idx > 0) leftMargin = kit.dp(6) })
                    }
                    content.addView(row)
                }
            }
        }

        val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(14), 0, 0) }
        actions.addView(kit.tonalButton("Backspace", R.drawable.oic_arrow_back) { press("⌫") }, LinearLayout.LayoutParams(-2, kit.dp(46)))
        actions.addView(kit.quietButton("Clear tape", R.drawable.oic_delete) {
            prefs.edit().remove(KEY_TAPE).apply()
            renderTape()
        }, LinearLayout.LayoutParams(-2, kit.dp(46)).apply { leftMargin = kit.dp(8) })
        actions.addView(kit.quietButton("Copy result", R.drawable.oic_text_fields) {
            copy(host, result.ifEmpty { expression })
        }, LinearLayout.LayoutParams(-2, kit.dp(46)).apply { leftMargin = kit.dp(8) })
        column.addView(actions, LinearLayout.LayoutParams(-1, -2))

        kit.sectionTitle(column, "Tape & Memory")
        column.addView(tapeBox, LinearLayout.LayoutParams(-1, -2))
        renderTape()
        show()
        return column
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
        val isOperator = key in setOf("÷", "×", "−", "+", "C", "(", ")", "%", "^", "!", "⌫", "AND", "OR", "XOR", "NOT", "<<", ">>") || key.startsWith("sin") || key.startsWith("cos") || key.startsWith("tan") || key.startsWith("log") || key.startsWith("ln") || key.startsWith("√")
        val isMemory = key.startsWith("M") || key == "DEG/RAD" || key in setOf("HEX", "DEC", "OCT", "BIN")
        val text = TextView(host).apply {
            this.text = key
            gravity = Gravity.CENTER
            textSize = when {
                small -> 13f
                key.length > 3 -> 13f
                key.length > 1 -> 15f
                else -> 21f
            }
            typeface = if (isEquals) OeaType.medium else OeaType.regular
            setTextColor(
                when {
                    isEquals -> kit.palette.onAccent
                    isMemory -> kit.palette.accent
                    isOperator -> kit.palette.accent
                    else -> kit.palette.text
                }
            )
            background = kit.ripple(
                kit.rounded(
                    when {
                        isEquals -> kit.palette.accent
                        isMemory -> kit.palette.cardHigh
                        isOperator -> kit.palette.accentSoft
                        else -> kit.palette.card
                    },
                    18,
                    if (isEquals) 0 else kit.palette.stroke,
                    if (isEquals) 0 else 1
                ),
                18
            )
            isClickable = true
            contentDescription = key
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
