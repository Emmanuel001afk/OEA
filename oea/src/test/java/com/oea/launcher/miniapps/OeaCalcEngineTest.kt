package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The arithmetic the calculator can get wrong without crashing.
 *
 * A calculator fails quietly: `2^3^2` evaluating to 64 instead of 512, a tangent at a
 * right angle printing 1.6e16, a comma-decimal phone printing a result its own parser
 * cannot read back, a programmer mode promising four bases and showing none. None of
 * that throws, so all of it is pinned here.
 */
class OeaCalcEngineTest {

    // ---------------------------------------------------------------- formatting

    @Test
    fun formatKeepsTwelveSignificantDigits() {
        assertEquals("0.333333333333", OeaCalcEngine.format(1.0 / 3.0))
        assertEquals("0.666666666667", OeaCalcEngine.format(2.0 / 3.0))
        assertEquals("1.41421356237", OeaCalcEngine.format(kotlin.math.sqrt(2.0)))
        assertEquals("3.14285714286", OeaCalcEngine.format(22.0 / 7.0))
    }

    @Test
    fun formatHidesBinaryDust() {
        assertEquals("0.3", OeaCalcEngine.format(0.1 + 0.2))
        assertEquals("1", OeaCalcEngine.format(1.0 / 3.0 * 3.0))
    }

    @Test
    fun formatNeverUsesAnExponentOrAComma() {
        // The old formatter used the device locale and switched to `%.8f`, which the
        // parser cannot read back; this one always emits plain digits with a dot.
        assertEquals("100000000000000000000", OeaCalcEngine.format(1e20))
        assertEquals("0.000000001", OeaCalcEngine.format(1e-9))
        assertEquals("479001600", OeaCalcEngine.format(479001600.0))
        assertEquals("-2.5", OeaCalcEngine.format(-2.5))
    }

    @Test
    fun formatCallsDustZero() {
        assertEquals("0", OeaCalcEngine.format(1e-13))
        assertEquals("0", OeaCalcEngine.format(-0.0))
        assertEquals("0", OeaCalcEngine.format(0.0))
    }

    @Test
    fun formatSaysErrorOnlyForNonNumbers() {
        assertEquals("Error", OeaCalcEngine.format(Double.NaN))
        assertEquals("Error", OeaCalcEngine.format(Double.POSITIVE_INFINITY))
        assertEquals("Error", OeaCalcEngine.format(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun everyFormattedNumberCanBeUsedAgain() {
        listOf(1.0 / 3.0, 22.0 / 7.0, -0.125, 1e-9, 123456.789, 1e18).forEach { value ->
            val text = OeaCalcEngine.format(value)
            assertTrue("$text should parse back", OeaCalcEngine.reusable(text))
            assertEquals(value, OeaCalcEngine.evaluate(text)!!, kotlin.math.abs(value) * 1e-11 + 1e-12)
        }
        assertFalse(OeaCalcEngine.reusable("Error"))
    }

    @Test
    fun commaDecimalPhonesGetDotDecimals() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            // The bug this pins: `"%.8f".format(1.5)` is "1,50000000" in Germany, the
            // result was written back into the entry field, and the parser — which only
            // knows `.` — could not read it, so the next operator produced "Error".
            assertEquals("1.5", OeaCalcEngine.format(1.5))
            assertEquals(2.5, OeaCalcEngine.evaluate("1.5+1")!!, 1e-9)
            assertEquals("0.5", OeaCalcEngine.format(OeaCalcEngine.evaluate("1÷2")!!))
            // The converter's formatter shares the rule, which is why "send to calculator"
            // now hands over a number instead of "1,609344" or "1.609344e+00".
            assertEquals("1.5", OeaUnits.show(1.5))
            assertEquals("1609.344", OeaUnits.show(1609.344))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ----------------------------------------------------------------- evaluation

    @Test
    fun multiplicationBindsTighterThanAddition() {
        assertEquals(14.0, OeaCalcEngine.evaluate("2+3×4")!!, 1e-9)
        assertEquals(20.0, OeaCalcEngine.evaluate("(2+3)×4")!!, 1e-9)
        assertEquals(41.0, OeaCalcEngine.evaluate("6×7−2÷2")!!, 1e-9)
    }

    @Test
    fun powerIsRightAssociative() {
        // 2^(3^2) = 512. The old parser looped left to right and said 64 — and the
        // launcher's search box said 512, so one expression had two answers.
        assertEquals(512.0, OeaCalcEngine.evaluate("2^3^2")!!, 1e-9)
        assertEquals(64.0, OeaCalcEngine.evaluate("(2^3)^2")!!, 1e-9)
    }

    @Test
    fun aMinusSignBindsLooserThanAPower() {
        assertEquals(-4.0, OeaCalcEngine.evaluate("-2^2")!!, 1e-9)
        assertEquals(-8.0, OeaCalcEngine.evaluate("-2^3")!!, 1e-9)
        assertEquals(0.125, OeaCalcEngine.evaluate("2^-3")!!, 1e-9)
        assertEquals(4.0, OeaCalcEngine.evaluate("(-2)^2")!!, 1e-9)
    }

    @Test
    fun factorialBindsToTheNumberNotTheSign() {
        assertEquals(6.0, OeaCalcEngine.evaluate("3!")!!, 1e-9)
        assertEquals(-6.0, OeaCalcEngine.evaluate("-3!")!!, 1e-9)
        assertEquals(3628800.0, OeaCalcEngine.evaluate("10!")!!, 1e-9)
        assertEquals(1.0, OeaCalcEngine.evaluate("0!")!!, 1e-9)
        assertNull(OeaCalcEngine.evaluate("3.5!"))
        assertNull(OeaCalcEngine.evaluate("(-1)!"))
        assertNull(OeaCalcEngine.evaluate("171!"))
    }

    @Test
    fun aTrailingPercentIsPercentOfTheLeftSide() {
        // The reason anyone opens a calculator: VAT, a discount, a tip.
        assertEquals(220.0, OeaCalcEngine.evaluate("200+10%")!!, 1e-9)
        assertEquals(180.0, OeaCalcEngine.evaluate("200−10%")!!, 1e-9)
        assertEquals(220.0, OeaCalcEngine.evaluate("200+(10%)")!!, 1e-9)
        assertEquals(105.0, OeaCalcEngine.evaluate("100+5%")!!, 1e-9)
        assertEquals(225.0, OeaCalcEngine.evaluate("200+10%+5")!!, 1e-9)
    }

    @Test
    fun percentInAProductIsJustAHundredth() {
        assertEquals(20.0, OeaCalcEngine.evaluate("200×10%")!!, 1e-9)
        // A term that merely ends in a percentage is not a percentage of the left side.
        assertEquals(2.3, OeaCalcEngine.evaluate("2+3×10%")!!, 1e-9)
        assertEquals(0.5, OeaCalcEngine.evaluate("50%")!!, 1e-9)
        assertEquals(5.1, OeaCalcEngine.evaluate("10%+5")!!, 1e-9)
    }

    @Test
    fun trigonometryFollowsTheChosenAngleUnit() {
        assertEquals(0.5, OeaCalcEngine.evaluate("sin(30)")!!, 1e-9)
        assertEquals(1.0, OeaCalcEngine.evaluate("tan(45)")!!, 1e-9)
        assertEquals(90.0, OeaCalcEngine.evaluate("asin(1)")!!, 1e-9)
        assertEquals(1.0, OeaCalcEngine.evaluate("sin(π÷2)", isDeg = false)!!, 1e-9)
        assertEquals(kotlin.math.PI / 2.0, OeaCalcEngine.evaluate("asin(1)", isDeg = false)!!, 1e-9)
    }

    @Test
    fun aTangentAtARightAngleIsNotANumber() {
        // tan(90°) is 1.633e16 if you trust the identity's dust; the true tangent does
        // not exist, and printing that number was a lie.
        assertNull(OeaCalcEngine.evaluate("tan(90)"))
        assertNull(OeaCalcEngine.evaluate("tan(270)"))
        assertNull(OeaCalcEngine.evaluate("tan(-90)"))
    }

    @Test
    fun answersOutsideTheDomainAreNull() {
        assertNull(OeaCalcEngine.evaluate("√(-1)"))
        assertNull(OeaCalcEngine.evaluate("sqrt(-1)"))
        assertNull(OeaCalcEngine.evaluate("log(0)"))
        assertNull(OeaCalcEngine.evaluate("ln(0)"))
        assertNull(OeaCalcEngine.evaluate("asin(2)"))
        assertNull(OeaCalcEngine.evaluate("1÷0"))
        assertNull(OeaCalcEngine.evaluate("0÷0"))
    }

    @Test
    fun aHalfTypedExpressionIsNotAnError() {
        assertEquals(5.0, OeaCalcEngine.evaluate("5+")!!, 1e-9)
        assertEquals(5.0, OeaCalcEngine.evaluate("5×")!!, 1e-9)
        assertEquals(5.0, OeaCalcEngine.evaluate("5^")!!, 1e-9)
    }

    @Test
    fun nonsenseIsNullRatherThanAGuess() {
        assertNull(OeaCalcEngine.evaluate(""))
        assertNull(OeaCalcEngine.evaluate("   "))
        assertNull(OeaCalcEngine.evaluate("5+×3"))
        assertNull(OeaCalcEngine.evaluate("(5"))
        assertNull(OeaCalcEngine.evaluate("foo(2)"))
        assertNull(OeaCalcEngine.evaluate("Error"))
    }

    @Test
    fun constantsAreTheRealOnes() {
        assertEquals(kotlin.math.PI * 2, OeaCalcEngine.evaluate("π×2")!!, 1e-9)
        assertEquals(kotlin.math.E, OeaCalcEngine.evaluate("e")!!, 1e-9)
        assertEquals(1.0, OeaCalcEngine.evaluate("ln(e)")!!, 1e-9)
    }

    @Test
    fun theSecondPageFunctionsExist() {
        assertEquals(100.0, OeaCalcEngine.evaluate("10^(2)")!!, 1e-9)
        assertEquals(9.0, OeaCalcEngine.evaluate("sqr(3)")!!, 1e-9)
        assertEquals(0.25, OeaCalcEngine.evaluate("inv(4)")!!, 1e-9)
        assertEquals(7.0, OeaCalcEngine.evaluate("abs(-7)")!!, 1e-9)
        assertEquals(60.0, OeaCalcEngine.evaluate("acos(0.5)")!!, 1e-9)
        assertTrue(OeaCalcEngine.evaluate("sinh(1)")!! > 1.17)
        assertTrue(OeaCalcEngine.evaluate("tanh(1)")!! < 0.77)
    }

    // ------------------------------------------------------------------- graphing

    @Test
    fun theGraphPlotsInRadians() {
        // The old graph evaluated with isDeg = true, so its own default `sin(x)` was a
        // dozen degrees wide and looked like a straight line.
        assertEquals(1.0, OeaCalcEngine.sample("sin(x)", kotlin.math.PI / 2)!!, 1e-9)
        assertEquals(0.0, OeaCalcEngine.sample("sin(x)", kotlin.math.PI)!!, 1e-9)
    }

    @Test
    fun theGraphBracketsTheSampleItSubstitutes() {
        assertEquals(9.0, OeaCalcEngine.sample("x^2", -3.0)!!, 1e-9)
        assertEquals(4.0, OeaCalcEngine.sample("x^2", 2.0)!!, 1e-9)
        assertEquals(-4.0, OeaCalcEngine.sample("-x^2", 2.0)!!, 1e-9)
    }

    @Test
    fun theGraphHasNoPointWhereTheFunctionHasNone() {
        assertNull(OeaCalcEngine.sample("1/x", 0.0))
        assertNull(OeaCalcEngine.sample("√(x)", -1.0))
        assertNull(OeaCalcEngine.sample("   ", 1.0))
    }

    // ----------------------------------------------------------------- programmer

    @Test
    fun literalsWorkInEveryBase() {
        assertEquals(31L, OeaCalcEngine.programmer("0x1F")!!)
        assertEquals(10L, OeaCalcEngine.programmer("0b1010")!!)
        assertEquals(15L, OeaCalcEngine.programmer("0o17")!!)
        assertEquals(255L, OeaCalcEngine.programmer("0xFF")!!)
    }

    @Test
    fun programmerArithmeticIsIntegerArithmetic() {
        assertEquals(3L, OeaCalcEngine.programmer("7 ÷ 2")!!)
        assertEquals(-3L, OeaCalcEngine.programmer("-7 ÷ 2")!!)
        assertEquals(1L, OeaCalcEngine.programmer("10 % 3")!!)
        assertNull(OeaCalcEngine.programmer("5 NOT 3"))
    }

    @Test
    fun moduloIsModuloAndNotPercent() {
        // `%` on the programmer keypad means modulo; as percent it made 10 % 3 an error.
        assertEquals(1L, OeaCalcEngine.programmer("10 % 3")!!)
        assertEquals(0L, OeaCalcEngine.programmer("9 % 3")!!)
        assertNull(OeaCalcEngine.programmer("10 % 0"))
        assertNull(OeaCalcEngine.programmer("10 ÷ 0"))
    }

    @Test
    fun bitwiseOperatorsFollowTheDocumentedPrecedence() {
        assertEquals(15L, OeaCalcEngine.programmer("0x1F AND 0x0F")!!)
        assertEquals(15L, OeaCalcEngine.programmer("0b1010 OR 0b0101")!!)
        assertEquals(0L, OeaCalcEngine.programmer("0b1100 XOR 0b1100")!!)
        assertEquals(1L, OeaCalcEngine.programmer("1 OR 2 AND 0")!!)
        assertEquals(7L, OeaCalcEngine.programmer("1 + 2 * 3")!!)
        assertEquals(9L, OeaCalcEngine.programmer("(1 + 2) * 3")!!)
        assertEquals(8L, OeaCalcEngine.programmer("1 << 2 + 1")!!)
        assertEquals(15L, OeaCalcEngine.programmer("255 >> 4")!!)
    }

    @Test
    fun notInvertsAllSixtyFourBits() {
        assertEquals(-1L, OeaCalcEngine.programmer("NOT 0")!!)
        assertEquals(-6L, OeaCalcEngine.programmer("NOT 5")!!)
    }

    @Test
    fun shiftsMaskTheirCountAndOverflowWraps() {
        assertEquals(1L, OeaCalcEngine.programmer("1 << 64")!!)
        assertEquals(2L, OeaCalcEngine.programmer("1 << 65")!!)
        assertEquals(Long.MIN_VALUE, OeaCalcEngine.programmer("0x7FFFFFFFFFFFFFFF + 1")!!)
        assertEquals(-1L, OeaCalcEngine.programmer("0xFFFFFFFFFFFFFFFF")!!)
    }

    @Test
    fun hexDigitsNeedTheirPrefix() {
        assertEquals(33L, OeaCalcEngine.programmer("0x1F + 2")!!)
        assertNull(OeaCalcEngine.programmer("1F"))
        assertNull(OeaCalcEngine.programmer("0x"))
        assertNull(OeaCalcEngine.programmer("AND 5"))
        assertNull(OeaCalcEngine.programmer("5 AND"))
        assertNull(OeaCalcEngine.programmer(""))
    }

    @Test
    fun theFourBasesDescribeTheSameNumber() {
        val bases = OeaCalcEngine.bases(31L)
        assertEquals(listOf("HEX", "DEC", "OCT", "BIN"), bases.map { it.label })
        assertEquals("0x1F", bases[0].text)
        assertEquals("31", bases[1].text)
        assertEquals("0o37", bases[2].text)
        assertEquals("0b0001 1111", bases[3].text)
        // Negative values are shown as the bit pattern the operators act on.
        val negative = OeaCalcEngine.bases(-1L)
        assertEquals("0xFFFFFFFFFFFFFFFF", negative[0].text)
        assertEquals("-1", negative[1].text)
        assertEquals(64, negative[3].text.removePrefix("0b").replace(" ", "").length)
    }

    @Test
    fun binaryIsGroupedInNibbles() {
        assertEquals("1010", OeaCalcEngine.nibbles("1010"))
        assertEquals("0001 1111", OeaCalcEngine.nibbles("11111"))
        assertEquals("1111 0000 1010", OeaCalcEngine.nibbles("111100001010"))
    }

    // -------------------------------------------------------------- editing, tape

    @Test
    fun backspaceRemovesAWholeToken() {
        // Deleting one character from `sin(` leaves `si`, which is not an expression.
        assertEquals("2+", OeaCalcEngine.backspace("2+sin("))
        assertEquals("", OeaCalcEngine.backspace("sin("))
        assertEquals("2+", OeaCalcEngine.backspace("2+asin("))
        assertEquals("1", OeaCalcEngine.backspace("12"))
        assertEquals("", OeaCalcEngine.backspace(""))
        assertEquals("5 ", OeaCalcEngine.backspace("5 AND"))
        assertEquals("0x1", OeaCalcEngine.backspace("0x1F"))
    }

    @Test
    fun aTapeLineKeepsItsExpressionAndItsAnswer() {
        val line = OeaCalcEngine.tapeLine("128+64", 192.0)
        assertEquals("128+64 = 192", line)
        assertEquals("128+64" to "192", OeaCalcEngine.tapeParts(line))
    }

    @Test
    fun aTapeLineSplitsFromTheRight() {
        assertEquals("1 = 2" to "3", OeaCalcEngine.tapeParts("1 = 2 = 3"))
        assertEquals("5" to "", OeaCalcEngine.tapeParts("5"))
        assertEquals("" to "7", OeaCalcEngine.tapeParts(" = 7"))
    }

    @Test
    fun theTapeKeepsTheNewestLinesAndItsLimit() {
        assertEquals("a = 1", OeaCalcEngine.pushTape(null, "a = 1"))
        assertEquals("a = 1\nb = 2", OeaCalcEngine.pushTape("b = 2", "a = 1"))
        assertEquals("c = 3\na = 1", OeaCalcEngine.pushTape("a = 1\nb = 2", "c = 3", limit = 2))
        val full = (1..5).joinToString("\n") { "x = $it" }
        assertEquals(3, OeaCalcEngine.pushTape(full, "new = 0", limit = 3).lines().size)
    }

    @Test
    fun theTapeCanBeSearched() {
        val tape = "1+2 = 3\n7×6 = 42\nsin(30) = 0.5"
        assertEquals(3, OeaCalcEngine.tapeLines(tape).size)
        assertEquals(listOf("7×6 = 42"), OeaCalcEngine.tapeLines(tape, "42"))
        assertEquals(listOf("7×6 = 42"), OeaCalcEngine.tapeLines(tape, "×"))
        assertEquals(listOf("sin(30) = 0.5"), OeaCalcEngine.tapeLines(tape, "SIN"))
        assertEquals(emptyList<String>(), OeaCalcEngine.tapeLines(tape, "nothing"))
        assertEquals(emptyList<String>(), OeaCalcEngine.tapeLines(null))
        assertEquals(1, OeaCalcEngine.tapeLines(tape, "", limit = 1).size)
    }
}
