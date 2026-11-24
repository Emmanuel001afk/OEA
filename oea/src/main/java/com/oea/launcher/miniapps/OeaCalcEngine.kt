package com.oea.launcher.miniapps

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The arithmetic and the number formatting behind OEA's calculator, in one place.
 *
 * Everything a calculator can get quietly wrong is decided here, once, and pinned by
 * `OeaCalcEngineTest`:
 *
 * - **Precedence and associativity.** `^` is right associative (`2^3^2` is 512, not
 *   64) and binds tighter than a minus sign, so `-2²` is `-4` the way mathematics
 *   writes it, not `4`. Postfix `!` and `%` bind to the number, so `-3!` is `-6`.
 * - **Percent.** A trailing `%` divides by a hundred: `200×10%` is 20. Inside `+` or
 *   `-` it means *percent of the value on the left*, which is what a phone calculator
 *   does: `200+10%` is 220, `200−10%` is 180. Thresholds, discounts and VAT are the
 *   reason people reach for a calculator, so this has to be the useful meaning.
 * - **Honest errors.** A result that is not a finite number — `tan(90°)`, `√(-1)`,
 *   `1÷0` — is `null`, and the screen says so. It never prints `1.6e16` for a tangent
 *   and never prints `NaN`.
 * - **Angles.** Degrees or radians, chosen by the caller, with degrees the default
 *   because the keypad says "DEG". The graph plots in radians (a function plot is a
 *   mathematical curve, not a survey).
 * - **Numbers.** [format] is locale independent, rounds to twelve significant digits,
 *   and always emits a plain decimal string that [evaluate] can read back, so a
 *   result can be used as the start of the next calculation.
 * - **Programmer mode is integers.** `Long` arithmetic with 64-bit wraparound,
 *   truncating division, `%` as modulo, hex/binary/octal literals anywhere in the
 *   expression, and the four bases of the current value for the display.
 */
internal object OeaCalcEngine {

    /** Twelve significant digits: more than a phone screen shows, fewer than a double's noise. */
    private val SIGNIFICANT = MathContext(12, RoundingMode.HALF_UP)

    /** Below this, a value is floating point dust from an identity (`sin(180°)`), not an answer. */
    private const val DUST = 1e-12

    /** The largest factorial a double holds exactly enough to be worth printing. */
    private const val MAX_FACTORIAL = 170

    // ------------------------------------------------------------------ formatting

    /**
     * A display string for [value]: plain digits, no exponent, no group separators,
     * `.` as the decimal mark whatever the phone's locale is.
     *
     * The output always parses back through [evaluate], so `=` can leave a usable
     * number in the entry field instead of a pretty dead end.
     */
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        if (value == 0.0) return "0"
        if (abs(value) < DUST) return "0"
        val rounded = BigDecimal(value).round(SIGNIFICANT).stripTrailingZeros()
        val text = rounded.toPlainString()
        return text.ifEmpty { "0" }
    }

    /** True when [text] can be read back as an expression, i.e. it is safe to reuse. */
    fun reusable(text: String): Boolean = evaluate(text) != null

    // ------------------------------------------------------------------ evaluation

    /**
     * Evaluates arithmetic written with the calculator's symbols (`×`, `÷`, `−`), or
     * with plain ASCII, with `π` and `e` as constants. Returns null — never a
     * meaningless number — when the expression is incomplete, malformed, or does not
     * have a finite answer.
     *
     * A trailing operator is treated as "not typed yet" (`5+` evaluates as 5), which
     * is what the `=` key needs; the live preview applies its own stricter rule.
     */
    fun evaluate(raw: String, isDeg: Boolean = true): Double? {
        val text = normalise(raw).trimEnd('+', '-', '*', '/', '^')
        if (text.isBlank()) return null
        val parser = Parser(text, isDeg)
        val value = runCatching { parser.parse() }.getOrNull() ?: return null
        if (!parser.atEnd()) return null
        return value.takeIf { it.isFinite() }
    }

    private fun normalise(raw: String): String = raw
        .replace('×', '*')
        .replace('÷', '/')
        .replace('−', '-')
        .replace('—', '-')
        .replace(" ", "")

    /**
     * Programmer-mode evaluation: 64-bit integers, `0x`/`0b`/`0o` literals, `AND`,
     * `OR`, `XOR`, `NOT`, `<<`, `>>`, the C symbols `&`, `|`, `^`, `~`, and `%` as
     * modulo rather than percent. Division truncates towards zero and overflow wraps
     * around, because this mode is about bit patterns, not about magnitudes.
     *
     * Precedence, loosest first: `OR`/`|`, then `XOR`/`^`, then `AND`/`&`, then the
     * shifts, then `+ -`, then `* / %`, then unary `NOT`/`~`/`-`, then brackets.
     */
    fun programmer(raw: String): Long? {
        val text = normalise(raw)
            .replace(" ", "")
            .uppercase()
        if (text.isBlank()) return null
        val parser = ProgrammerParser(text.trimEnd('+', '-', '*', '/', '&', '|', '^'))
        val value = runCatching { parser.parse() }.getOrNull() ?: return null
        if (!parser.atEnd()) return null
        return value
    }

    // ------------------------------------------------------------- programmer bases

    /** One line of the programmer display: the same number in another base. */
    data class Base(val label: String, val text: String)

    /**
     * The current value in the four bases this mode promises. Negative values are
     * shown as their 64-bit two's complement, which is what the bitwise operators
     * actually act on.
     */
    fun bases(value: Long): List<Base> = listOf(
        Base("HEX", "0x" + java.lang.Long.toUnsignedString(value, 16).uppercase()),
        Base("DEC", value.toString()),
        Base("OCT", "0o" + java.lang.Long.toUnsignedString(value, 8)),
        Base("BIN", "0b" + nibbles(java.lang.Long.toUnsignedString(value, 2)))
    )

    /** Splits a binary string into groups of four bits, from the right, so it can be read. */
    fun nibbles(bits: String): String {
        if (bits.length <= 4) return bits
        val out = StringBuilder()
        val pad = (4 - bits.length % 4) % 4
        val padded = if (pad == 0) bits else "0".repeat(pad) + bits
        padded.forEachIndexed { index, c ->
            if (index > 0 && index % 4 == 0) out.append(' ')
            out.append(c)
        }
        return out.toString()
    }

    // ------------------------------------------------------------------- graphing

    /**
     * The value of `y = f(x)` at [x] for the graph.
     *
     * [x] is bracketed before substitution so a negative sample cannot change the
     * meaning of the expression (`x^2` at `x = -3` is 9 either way, but `-x^2` is
     * not), and the angle unit is radians: `sin(x)` must have a period of 2π on the
     * plot or the picture is a lie.
     */
    fun sample(function: String, x: Double): Double? {
        if (function.isBlank()) return null
        val body = function.replace('×', '*').replace('÷', '/').replace('−', '-').replace("X", "x")
        val substituted = body.replace("x", "(" + format(x) + ")")
        return evaluate(substituted, isDeg = false)
    }

    // ------------------------------------------------------------------ editing

    /**
     * The tokens a backspace should remove whole. Deleting one character from `sin(`
     * leaves `si`, which is not an expression and looks like a bug; deleting the token
     * removes the function the user actually typed.
     */
    private val TOKENS = listOf(
        "sin⁻¹(", "cos⁻¹(", "tan⁻¹(", "sinh(", "cosh(", "tanh(",
        "asin(", "acos(", "atan(", "sqrt(", "sin(", "cos(", "tan(", "log(", "ln(",
        "sqr(", "inv(", "abs(", "10^(", "e^(", "abs(",
        "AND", "XOR", "NOT", "Ans", "0x", "0b", "0o", "<<", ">>"
    ).distinct().sortedByDescending { it.length }

    /** Removes one whole token from the end of [text], or one character if none matches. */
    fun backspace(text: String): String {
        val token = TOKENS.firstOrNull { text.endsWith(it) } ?: return text.dropLast(1)
        return text.dropLast(token.length)
    }

    // ---------------------------------------------------------------- the tape

    /** One recorded answer, written as `expression = value`. */
    fun tapeLine(expression: String, value: Double): String = "${expression.trim()} = ${format(value)}"

    /** Splits a tape line back into its expression and its answer. */
    fun tapeParts(line: String): Pair<String, String> {
        val at = line.lastIndexOf(" = ")
        if (at < 0) return line.trim() to ""
        return line.substring(0, at).trim() to line.substring(at + 3).trim()
    }

    /** Prepends [line] to the stored tape and keeps the newest [limit] lines. */
    fun pushTape(stored: String?, line: String, limit: Int = 100): String {
        val existing = stored.orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        return (listOf(line) + existing).take(limit.coerceAtLeast(1)).joinToString("\n")
    }

    /**
     * The tape's lines, newest first, optionally filtered by [query]. A blank query
     * returns everything: the sheet shows its whole history, not the first thirty
     * lines with the rest unreachable.
     */
    fun tapeLines(stored: String?, query: String = "", limit: Int = 100): List<String> {
        val q = query.trim()
        return stored.orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    // ------------------------------------------------------------------- parser

    /**
     * Recursive descent over doubles. The method per precedence level is the whole
     * grammar:
     * `expression := term (('+'|'-') term)*`, `term := unary (('*'|'/') unary)*`,
     * `unary := ('-'|'+')* power`, `power := postfix ('^' unary)?`,
     * `postfix := atom ('%'|'!')*`, `atom := number | 'π' | 'e' | func '(' expression ')' | '(' expression ')'`.
     */
    private class Parser(private val text: String, private val isDeg: Boolean) {
        private var i = 0
        /** True when the last postfix parsed was a bare percentage, e.g. the `10%` of `200+10%`. */
        private var barePercent = false

        fun atEnd(): Boolean = i >= text.length

        fun parse(): Double? = expression()

        private fun expression(): Double? {
            var value = term() ?: return null
            while (i < text.length) {
                val op = text[i]
                if (op != '+' && op != '-') return value
                i++
                val right = term() ?: return null
                val operand = if (barePercent) value * right else right
                value = if (op == '+') value + operand else value - operand
            }
            return value
        }

        private fun term(): Double? {
            var value = unary() ?: return null
            var factors = 1
            while (i < text.length) {
                when (text[i]) {
                    '*' -> { i++; factors++; value *= unary() ?: return null }
                    '/' -> {
                        i++
                        factors++
                        val divisor = unary() ?: return null
                        if (divisor == 0.0) return null
                        value /= divisor
                    }
                    else -> break
                }
            }
            // `200+10%` is a percentage of the left side, but `2+3×10%` is a product
            // that merely ends in a percentage; only a term that is one percentage
            // takes the first meaning.
            if (factors > 1) barePercent = false
            return value
        }

        private fun unary(): Double? {
            var sign = 1.0
            while (i < text.length && (text[i] == '-' || text[i] == '+')) {
                if (text[i] == '-') sign = -sign
                i++
            }
            val value = power() ?: return null
            return sign * value
        }

        private fun power(): Double? {
            val base = postfix() ?: return null
            if (i < text.length && text[i] == '^') {
                i++
                val exponent = unary() ?: return null
                return base.pow(exponent)
            }
            return base
        }

        private fun postfix(): Double? {
            barePercent = false
            var value = atom() ?: return null
            while (i < text.length && (text[i] == '%' || text[i] == '!')) {
                when (text[i]) {
                    '%' -> { value /= 100.0; barePercent = true; i++ }
                    '!' -> { value = factorial(value) ?: return null; barePercent = false; i++ }
                }
            }
            return value
        }

        private fun atom(): Double? {
            if (i >= text.length) return null
            for (name in FUNCTIONS) {
                if (!text.startsWith(name, i)) continue
                i += name.length
                if (i >= text.length || text[i] != '(') return null
                i++
                val arg = expression() ?: return null
                if (i >= text.length || text[i] != ')') return null
                i++
                return apply(name, arg)
            }
            when (text[i]) {
                'π' -> { i++; return Math.PI }
                'e' -> { i++; return Math.E }
                '(' -> {
                    i++
                    val value = expression() ?: return null
                    if (i >= text.length || text[i] != ')') return null
                    i++
                    return value
                }
            }
            val start = i
            while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
            if (i == start) return null
            return text.substring(start, i).toDoubleOrNull()
        }

        private fun factorial(value: Double): Double? {
            if (value < 0 || value != floor(value) || value > OeaCalcEngine.MAX_FACTORIAL) return null
            var result = 1.0
            for (k in 2..value.toInt()) result *= k
            return result
        }

        /**
         * Applies one function to its argument, honouring the degree/radian choice.
         *
         * `tan` is the one function with a value it cannot show: at a right angle the
         * cosine is zero and the true tangent does not exist, so the identity's
         * 1.6e16 dust is reported as an error rather than printed as an answer.
         */
        private fun apply(name: String, argument: Double): Double? {
            val angle = if (isDeg) Math.toRadians(argument) else argument
            return when (name) {
                "sin" -> sin(angle)
                "cos" -> cos(angle)
                "tan" -> if (abs(cos(angle)) < OeaCalcEngine.DUST) null else tan(angle)
                "sinh" -> sinh(argument)
                "cosh" -> cosh(argument)
                "tanh" -> tanh(argument)
                "asin" -> fromRadians(asin(argument))
                "acos" -> fromRadians(acos(argument))
                "atan" -> fromRadians(atan(argument))
                "log" -> log10(argument)
                "ln" -> ln(argument)
                "sqrt", "√" -> sqrt(argument)
                "sqr" -> argument * argument
                "inv" -> 1.0 / argument
                "abs" -> abs(argument)
                else -> null
            }
        }

        private fun fromRadians(angle: Double): Double =
            if (isDeg) Math.toDegrees(angle) else angle

        private companion object {
            /**
             * The vocabulary, longest name first so `sinh` is never read as `sin`
             * followed by a stray `h`. `√` is `sqrt`; `log` is base ten and `ln` is
             * natural, as on every scientific calculator.
             */
            val FUNCTIONS = listOf(
                "sinh", "cosh", "tanh", "asin", "acos", "atan",
                "sin", "cos", "tan", "log", "ln", "sqrt", "sqr", "inv", "abs", "√"
            )
        }
    }

    /**
     * Recursive descent over [Long]. Every operation is the bit-level one the mode
     * promises: `AND`/`&`, `OR`/`|`, `XOR`/`^`, `NOT`/`~`, `<<`, `>>` (arithmetic),
     * `%` (modulo), truncating `/`.
     */
    private class ProgrammerParser(private val text: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= text.length

        fun parse(): Long? = or()

        private fun or(): Long? {
            var value = xor() ?: return null
            while (true) {
                value = when {
                    text.startsWith("OR", i) -> { i += 2; value or (xor() ?: return null) }
                    text.startsWith("|", i) -> { i++; value or (xor() ?: return null) }
                    else -> return value
                }
            }
        }

        private fun xor(): Long? {
            var value = and() ?: return null
            while (true) {
                value = when {
                    text.startsWith("XOR", i) -> { i += 3; value xor (and() ?: return null) }
                    text.startsWith("^", i) -> { i++; value xor (and() ?: return null) }
                    else -> return value
                }
            }
        }

        private fun and(): Long? {
            var value = shift() ?: return null
            while (true) {
                value = when {
                    text.startsWith("AND", i) -> { i += 3; value and (shift() ?: return null) }
                    text.startsWith("&", i) -> { i++; value and (shift() ?: return null) }
                    else -> return value
                }
            }
        }

        private fun shift(): Long? {
            var value = additive() ?: return null
            while (true) {
                value = when {
                    text.startsWith("<<", i) -> { i += 2; value shl count(additive() ?: return null) }
                    text.startsWith(">>", i) -> { i += 2; value shr count(additive() ?: return null) }
                    else -> return value
                }
            }
        }

        private fun additive(): Long? {
            var value = multiplicative() ?: return null
            while (true) {
                value = when {
                    text.startsWith("+", i) -> { i++; value + (multiplicative() ?: return null) }
                    text.startsWith("-", i) -> { i++; value - (multiplicative() ?: return null) }
                    else -> return value
                }
            }
        }

        private fun multiplicative(): Long? {
            var value = unary() ?: return null
            while (true) {
                value = when {
                    text.startsWith("*", i) -> { i++; value * (unary() ?: return null) }
                    text.startsWith("%", i) -> {
                        i++
                        val divisor = unary() ?: return null
                        if (divisor == 0L) return null
                        value % divisor
                    }
                    text.startsWith("/", i) -> {
                        i++
                        val divisor = unary() ?: return null
                        if (divisor == 0L) return null
                        value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Long? {
            if (text.startsWith("NOT", i)) { i += 3; return unary()?.inv() }
            if (text.startsWith("~", i)) { i++; return unary()?.inv() }
            if (text.startsWith("-", i)) { i++; return unary()?.let { -it } }
            if (text.startsWith("+", i)) { i++; return unary() }
            return primary()
        }

        private fun primary(): Long? {
            if (i < text.length && text[i] == '(') {
                i++
                val value = or() ?: return null
                if (i >= text.length || text[i] != ')') return null
                i++
                return value
            }
            if (text.startsWith("0X", i)) { i += 2; return digits(16) }
            if (text.startsWith("0B", i)) { i += 2; return digits(2) }
            if (text.startsWith("0O", i)) { i += 2; return digits(8) }
            return digits(10)
        }

        /**
         * Reads the digits of one literal in the given base. A bare literal is
         * decimal: hex letters need their `0x`, so a mistyped `1F` is an error rather
         * than a silently different number.
         */
        private fun digits(radix: Int): Long? {
            val start = i
            while (i < text.length) {
                // `A` is a hex digit and also the first letter of `AND`, so a hex literal
                // would otherwise swallow the operator: `0x1F AND 0x0F` read as `0x1FA`.
                if (radix == 16 && text.startsWith("AND", i)) break
                val digit = Character.digit(text[i], radix)
                if (digit < 0) break
                i++
            }
            if (i == start) return null
            return java.lang.Long.parseUnsignedLong(text.substring(start, i), radix)
        }

        /** A shift count is a bit offset: only the low six bits are meaningful. */
        private fun count(value: Long): Int = (value and 63L).toInt()
    }
}
