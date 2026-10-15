package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import kotlin.math.*

/**
 * OEA Calculator: full calculator with polished interface and competitive calculations.
 *
 * Features:
 * - Basic arithmetic with tape (existing)
 * - Scientific functions: sin, cos, tan, log, ln, sqrt, power, factorial, pi, e
 * - History, memory, percentage, brackets
 * - Unit conversions and constants
 * - Polished animations and error handling
 */
object OeaCalculatorApp {

    private const val KEY_TAPE = "calculator_tape"
    private const val KEY_MEMORY = "calc_memory"
    private const val TAPE_LIMIT = 40

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val prefs = prefs(host)
        var expression = ""
        var result = ""
        var scientific = prefs.getBoolean("calc_scientific", false)

        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        // Header with mode toggle
        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("OEA Calculator", 20f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.chip(if (scientific) "Scientific" else "Basic", true) {
            scientific = !scientific
            prefs.edit().putBoolean("calc_scientific", scientific).apply()
            (host as? OeaMiniAppActivity)?.refresh()
        }, LinearLayout.LayoutParams(-2, kit.dp(36)))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val display = kit.text("", 34f, palette.text, com.oea.launcher.ui.OeaType.light).apply {
            gravity = Gravity.END
            setPadding(kit.dp(18), kit.dp(16), kit.dp(18), kit.dp(4))
            setLineSpacing(kit.dpf(2f), 1f)
        }
        val hint = kit.text("", 15f, palette.textTertiary).apply {
            gravity = Gravity.END
            setPadding(kit.dp(18), 0, kit.dp(18), kit.dp(10))
            maxLines = 3
        }
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.card, 22, palette.stroke, 1)
            addView(display, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        val tapeBox = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var memory = prefs.getString(KEY_MEMORY, "0")?.toDoubleOrNull() ?: 0.0

        fun show() {
            display.text = expression.ifEmpty { "0" }
            hint.text = if (result.isEmpty()) "Scientific: sin, cos, log, √, ^, π, e, ! — tape saves every result" else "= $result"
        }

        fun renderTape() {
            tapeBox.removeAllViews()
            val lines = prefs.getString(KEY_TAPE, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) {
                kit.note(tapeBox, "Nothing on tape yet. Every result is kept here. Tap line to copy. Memory: ${format(memory)}")
                return
            }
            val group = kit.group(tapeBox)
            lines.forEach { line ->
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
            kit.note(tapeBox, "Tap line to copy • Memory: ${format(memory)}")
        }

        fun press(key: String) {
            when (key) {
                "C" -> { expression = ""; result = "" }
                "⌫" -> if (expression.isNotEmpty()) expression = expression.dropLast(1)
                "=" -> {
                    val value = Evaluator.evaluate(expression)
                    result = when {
                        expression.isBlank() -> ""
                        value == null -> "incomplete"
                        else -> format(value)
                    }
                    if (value != null) {
                        prefs.edit().putString(KEY_TAPE, (("$expression = ${format(value)}") + "\n" + prefs.getString(KEY_TAPE, "").orEmpty()).trim().lineSequence().take(TAPE_LIMIT).joinToString("\n")).apply()
                    }
                }
                "MC" -> { memory = 0.0; prefs.edit().putString(KEY_MEMORY, "0").apply() }
                "MR" -> { expression += format(memory); }
                "M+" -> {
                    Evaluator.evaluate(expression)?.let { memory += it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() }
                }
                "M-" -> {
                    Evaluator.evaluate(expression)?.let { memory -= it; prefs.edit().putString(KEY_MEMORY, memory.toString()).apply() }
                }
                else -> {
                    if (expression.length < 120) expression += key
                }
            }
            show()
            renderTape()
        }

        // Keypad
        val basicRows = listOf(
            listOf("MC", "MR", "M+", "M-"),
            listOf("C", "(", ")", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "%", "="),
        )
        val scientificRows = listOf(
            listOf("sin(", "cos(", "tan(", "log(", "ln("),
            listOf("√(", "π", "e", "^", "!"),
            listOf("MC", "MR", "M+", "M-", "C"),
            listOf("(", ")", "%", "÷", "⌫"),
            listOf("7", "8", "9", "×", " "),
            listOf("4", "5", "6", "−", " "),
            listOf("1", "2", "3", "+", " "),
            listOf("0", ".", " ", "=", " "),
        )

        val rows = if (scientific) scientificRows else basicRows
        rows.forEach { keys ->
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, kit.dp(5), 0, 0)
            }
            keys.forEachIndexed { index, key ->
                if (key.trim().isEmpty()) {
                    row.addView(View(host), LinearLayout.LayoutParams(0, kit.dp(58), 1f).apply { if (index > 0) leftMargin = kit.dp(6) })
                } else {
                    row.addView(keyButton(host, kit, key) { press(key) }, LinearLayout.LayoutParams(0, kit.dp(58), 1f).apply {
                        if (index > 0) leftMargin = kit.dp(6)
                    })
                }
            }
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, kit.dp(14), 0, 0)
        }
        actions.addView(kit.tonalButton("Backspace", com.oea.launcher.R.drawable.oic_arrow_back) { press("⌫") }, LinearLayout.LayoutParams(-2, kit.dp(46)))
        actions.addView(kit.quietButton("Clear tape", com.oea.launcher.R.drawable.oic_delete) {
            prefs.edit().remove(KEY_TAPE).apply()
            renderTape()
        }, LinearLayout.LayoutParams(-2, kit.dp(46)).apply { leftMargin = kit.dp(8) })
        column.addView(actions, LinearLayout.LayoutParams(-1, -2))

        kit.sectionTitle(column, "Tape & Memory")
        column.addView(tapeBox, LinearLayout.LayoutParams(-1, -2))
        renderTape()
        show()
        return column
    }

    private fun keyButton(host: Activity, kit: OeaKit, key: String, onClick: () -> Unit): View {
        val isEquals = key == "="
        val isOperator = key in setOf("÷", "×", "−", "+", "C", "(", ")", "%", "^", "!", "⌫") || key.startsWith("sin") || key.startsWith("cos") || key.startsWith("tan") || key.startsWith("log") || key.startsWith("ln") || key.startsWith("√")
        val isMemory = key.startsWith("M")
        val text = TextView(host).apply {
            this.text = key
            gravity = Gravity.CENTER
            textSize = when {
                key.length > 3 -> 13f
                key.length > 1 -> 15f
                else -> 21f
            }
            typeface = if (isEquals) com.oea.launcher.ui.OeaType.medium else com.oea.launcher.ui.OeaType.regular
            setTextColor(
                when {
                    isEquals -> kit.palette.onAccent
                    isMemory -> kit.palette.accent
                    isOperator -> kit.palette.accent
                    else -> kit.palette.text
                },
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
                    if (isEquals) 0 else 1,
                ),
                18,
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
            android.widget.Toast.makeText(host, "Copied.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("oea_miniapps", Context.MODE_PRIVATE)

    private fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "not a number"
        val rounded = if (abs(value) < 1e-12) 0.0 else value
        val asLong = rounded.roundToLong()
        if (abs(rounded - asLong) < 1e-9 && abs(rounded) < 1e15) return asLong.toString()
        val text = "%.8f".format(rounded).trimEnd('0').trimEnd('.')
        return text.ifEmpty { "0" }
    }

    internal object Evaluator {
        fun evaluate(raw: String): Double? {
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
            // Handle sqrt symbol
            text = text.replace("√", "sqrt")
            // Handle ln -> log with base e
            // Our parser supports functions
            return try {
                val parser = Parser(text)
                val value = parser.parse()
                if (parser.atEnd()) value else null
            } catch (_: Throwable) { null }
        }

        private class Parser(private val text: String) {
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
                        '/' -> {
                            i++
                            val d = power() ?: return null
                            if (d == 0.0) return null
                            value /= d
                        }
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
                // postfix % and !
                while (i < text.length && (text[i] == '%' || text[i] == '!')) {
                    when (text[i]) {
                        '%' -> { base /= 100.0; i++ }
                        '!' -> {
                            base = factorial(base) ?: return null
                            i++
                        }
                    }
                }
                return base
            }

            private fun atom(): Double? {
                if (i >= text.length) return null
                // functions
                val funcs = listOf("sin", "cos", "tan", "log", "ln", "sqrt")
                for (f in funcs) {
                    if (text.startsWith(f, i)) {
                        i += f.length
                        if (i < text.length && text[i] == '(') {
                            i++
                            val arg = expression() ?: return null
                            if (i < text.length && text[i] == ')') i++ else return null
                            return when (f) {
                                "sin" -> sin(Math.toRadians(arg))
                                "cos" -> cos(Math.toRadians(arg))
                                "tan" -> tan(Math.toRadians(arg))
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
}
