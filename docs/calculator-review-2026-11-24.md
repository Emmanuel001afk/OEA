# Calculator review — 24 November 2026

The calculator is the one mini-app whose bugs are silent. Nothing crashes when `2^3^2`
is 64, when a tangent at a right angle prints 1.6e16, or when a phone set to German
writes a result its own parser cannot read back — the screen just shows a wrong number,
or an "Error" where a number was expected.

The file's header made claims worth testing: "Programmer: binary/hex/octal/decimal,
bitwise AND/OR/XOR/NOT, shifts", "Scientific: … hyperbolic, inverse", "Graphing with
pinch zoom", "Tape that saves every result, searchable, exportable". Four of those were
false in ways a user meets on the first tap.

Everything in §1 changed here. §2 is what was already right, §3 what is deliberately
still missing, §4 how it is verified, §5 what only a phone can settle.

The arithmetic now lives in `miniapps/OeaCalcEngine.kt` — one evaluator, one formatter,
one programmer mode — and `OeaCalculatorApp.kt` is the screen around it. That split is
the reason the fixes below could be pinned by tests at all.

---

## 1. What was wrong, and what changed

### 1.1 The launcher had two calculators, and they disagreed

`OeaSearchController` carried its **own** parser (`Parser(private val s: String)`), and
it associated `^` differently from the one in the calculator. So typing `2^3^2`:

* in the launcher's search box → **512**,
* in the Calculator app → **64**.

One expression, two answers, one launcher. The calculator's `power()` looped left to
right (`while (text[i] == '^') base = base.pow(factor())`), which is the wrong
associativity for a power; mathematics and every other calculator read `2^3^2` as
`2^9 = 512`.

**Changed.** `OeaCalcEngine.evaluate` is the only evaluator in the app; `calculate()`
in the search controller now asks it (`search/OeaSearchController.kt:171`) and its own
`Parser` is gone. `power()` recurses into `unary()`, so `2^3^2` is 512 everywhere.

### 1.2 `-2^2` was 4

Because the minus sign was folded into the *factor* before the power was applied, `-2^2`
evaluated as `(-2)^2`. Mathematics writes `-(2^2) = -4`, and the difference matters the
moment anyone types a formula they will later copy.

**Changed.** The sign lives in `unary()` *above* `power()`, and the exponent is parsed
as a unary too, so `-2^2` is -4 while `(-2)^2` stays 4 and `2^-3` still works.

### 1.3 `tan(90)` printed 16,331,239,353,195,370

`tan(90°)` is not a number; the double precision identity returns 1.633e16, and the old
parser passed that straight to the formatter, which printed it as an answer. The same
path made `asin(2)` and `√(-1)` show **"not a number"** — a string the entry field then
accepted as if it were an expression.

**Changed.** `tan` refuses a right angle (`|cos| < 1e-12`), and `evaluate` returns null
for anything non-finite, so every domain error is the same honest "Error" and never a
number that looks like a result.

### 1.4 On a comma-decimal phone, every result was unusable

`format()` was `"%.8f".format(rounded)` with **no locale**. On a phone set to German,
French, Spanish, Portuguese or most of Africa's francophone locales, `1÷3` produced
`0,33333333`, which was then:

* written into the tape,
* and — after `=` — written **back into the entry field**,

where the parser, which only accepts `.`, could not read it. The next operator turned
the screen to "Error". The same bug hit `OeaUnits.setRate`
(`miniapps/OeaUnits.kt:254`), which stored rates as `USD:1550,5`; `rates()` reads
them back with `toDoubleOrNull()`, gets null, and **silently restores the seed rate** —
so a user's edited exchange rate vanished on the next open.

**Changed.** `OeaCalcEngine.format` is locale independent and `setRate` stores through
it. `OeaCalcEngineTest.commaDecimalPhonesGetDotDecimals` runs the whole suite of those
calls with `Locale.GERMANY` set.

### 1.5 …and on every phone, "send to calculator" could produce garbage

`OeaUnits.show()` used `"%.6e"` for large and small values and `"%.2f"` otherwise. The
converter's **Send to Calculator** row copied that straight into the expression, so
`1 mi → nm` (9.46e24) arrived as `9.460730e+24`, and the evaluator's `e` substitution
turned it into `2.718…`-based nonsense — a plausible-looking wrong answer, which is the
worst kind.

**Changed.** One formatter for the app: plain digits, `.` as the decimal mark, no
exponent, twelve significant digits — and `reusable()` checks that a result parses back
before `=` hands it to the entry field.

### 1.6 The programmer mode was not a programmer mode

* `%` on its keypad went through the **double** evaluator's percent rule, so `10 % 3`
  was an Error instead of 1.
* `÷` returned a fraction (`7 ÷ 2` = 3.5) in a mode about bit patterns.
* The hex letters A–F were on the keypad but only ever parsed inside a complete `0x…`
  literal, so `0x1F + 2` was an Error.
* The mode's own hint promised "HEX (0x) · BIN (0b) · OCT (0o) · DEC" and **never
  displayed a base**. The whole point of the mode was missing.
* A `.` key existed for numbers that cannot have a fraction.

**Changed.** `OeaCalcEngine.programmer` is a recursive-descent parser over `Long`:
64-bit wraparound, truncating division, `%` as modulo, `0x`/`0b`/`0o` literals anywhere
in the expression, `AND`/`OR`/`XOR`/`NOT`/`&`/`|`/`^`/`~`/`<<`/`>>` with documented
precedence and masked shift counts. `bases()` returns the current value in all four
bases, shown under the display as you type (`OeaCalculatorApp.kt:412`), with binary
grouped in nibbles. The `.` key is gone.

### 1.7 Half the scientific functions had no key

The old parser implemented `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh` — and the
keypad produced none of them. The header advertised "hyperbolic, inverse", and the only
way to reach one was to type it, which a phone keypad cannot do.

**Changed.** A `2nd` key (`OeaCalculatorApp.kt:385`) flips the function page: the six
primary keys become `sin⁻¹(`, `cos⁻¹(`, `tan⁻¹(`, `10^(`, `e^(`, `x²`; the hyperbolic
row, `1/x` and factorial sit on the first page. `Ans` is new in both keypads and reuses
the last answer. Pretty labels map to real syntax through one table (`KEY_INSERT`), so
`1/x` inserts `inv(` and `x²` inserts `sqr(`.

### 1.8 The graph plotted degrees, drew its grid from the wrong origin, and could not be touched

Three separate faults in one 40-line view:

1. `evaluateFunction` called the evaluator with **`isDeg = true`**, so the default
   `sin(x)` was a dozen degrees wide across the whole screen — a nearly flat line
   dressed up as a wave;
2. the grid started at the view's corner (`offsetX % scale`) while the curve was drawn
   from the middle, so a gridline was never at the value it implied;
3. `offsetX`/`offsetY` were declared and never changed and the view had no
   `onTouchEvent`, so "Graphing with pinch zoom" was a promise with no code behind it.
   (`lastYValid` was dead as well.)

**Changed.** `OeaCalcEngine.sample` plots in **radians** and brackets each substituted
`x`, so `x^2` at -3 is 9 and `-x^2` is -4. `GraphView` (`OeaCalculatorApp.kt:635`) anchors
its grid on the origin, labels the x-axis, breaks the curve at a pole instead of drawing
across it, and handles `ScaleGestureDetector` zoom about the fingers plus drag-to-pan,
with a Reset. The plot also updates as you type, and says when an expression cannot be
read instead of quietly keeping the old curve.

### 1.9 The unit converter could not accept 1.5

`OeaKit.field(numeric = true)` set `InputType.TYPE_CLASS_NUMBER`, whose keyboard has no
decimal point and which the `EditText` itself rejects the `.` for. So the **unit
converter** — the one screen where a fraction is the normal case — could convert 1 and 2
but not 1.5. The same field type was behind the currency rate prompt, so a rate like
1550.5 could not be typed either.

**Changed.** `field(…, decimal = true)` (`ui/OeaKit.kt:889`), used by the converter and
the rate editor; `prompt` passes it through. Every other numeric field keeps its exactly
old behaviour.

### 1.10 The tape was neither searchable nor exportable — and the sheet hid 70 lines

The header says "Tape that saves every result, searchable, exportable". The sheet had no
search, no export, and `lines.take(30)` against a store that keeps 100, so seventy
answers were unreachable. `substringBefore(" = ")` also split an expression at its
first ` = ` rather than its last.

**Changed.** A search box filters the tape live; **Share tape** hands all of it to any
text app (`ACTION_SEND`, no permission, no file to manage); the whole tape scrolls;
`tapeParts` splits from the right. The reading, prepending and capping of the tape moved
into `pushTape`/`tapeLines` so it can be tested.

### 1.11 The calculator widget always said "Calculator"

`OeaCalculatorWidgetProvider` read `getSharedPreferences("oea_calculator")` key
`last_result` — a file and a key **nothing in the app writes** (the app uses
`oea_calc_v2`). So the headline was the literal word "Calculator" on every phone, for
ever. It also rendered the *clock* layout, and carried `last ?: "0"`, a null check on a
value that had a default.

**Changed.** `=` now stores the answer under `oea_calc_v2`/`last_result`, refreshes the
widget, and the widget reads that store and its own layout
(`res/layout/oea_widget_calculator.xml`, given the `widget_root` id it was missing), with
the newest tape line underneath the answer.

### 1.12 Smaller faults fixed on the way

* **Backspace deleted one character**, so `sin(` became `si` — not an expression, and not
  what the user asked to remove. It now deletes a whole token (`OeaCalcEngine.backspace`).
* **`5×` previewed as `= 5`**: the live preview ran on an expression whose last thing
  typed was an operator. The preview is now suppressed until the expression can be one.
* **A long expression had no `maxLines`**, so it wrapped and pushed the keypad down;
  the display now shows two lines with the start ellipsised.
* **Currency opened as `1 USD = 1 USD`**; it opens on USD → EUR.
* **A very long conversion** (a light year in nanometres) overflowed the result box; it
  now shrinks to two lines.

---

## 2. What was already right

* **The converter's model** — one exact factor per unit against a single base, with
  temperature the only offset family — is the correct shape, and it survived unchanged.
* **Currency is deliberately not fetched.** It is a family whose numbers you set, with a
  note saying so; that honesty is the best thing in the file and it was left alone.
* **The tape's cap, memory persistence and mode persistence** already worked.
* **The app shell** (nav, back handling for Programmer, sheet/row/group idioms) was
  consistent with the rest of the mini-apps.
* **Search's arithmetic filter** — only digits and operators, at least one operator and
  at least one digit — is the right gate for a search box, and it still is.

---

## 3. What this pass did not change, and why

* **64-bit words only.** A programmer calculator often offers 8/16/32/64-bit views. The
  mode now shows all four *bases* correctly, but not a word-size switch: that changes
  the meaning of `NOT` and shifts, and it needs a UI of its own to say which width is
  in force. Better absent than ambiguous.
* **No RPN / no programming/equation entry.** Standard and scientific infix covers what
  the header claims; a second input model is a different feature, not a fix.
* **No complex numbers.** `√(-1)` is an honest error, as the header always implied.
* **No exchange-rate fetch.** Unchanged on purpose: a rate OEA does not know is not a
  rate it will invent.
* **The tape does not sync or back up.** It lives on the phone, like the rest of the
  mini-app state; sharing is the export path.

---

## 4. Verification

* **`OeaCalcEngineTest` — 37 test methods** covering: twelve-significant-digit
  formatting and the no-exponent/no-comma rule; parse-back of every formatted number;
  `Locale.GERMANY` for the formatter, the parser and `OeaUnits.show`; precedence and
  brackets; right-associative `^`; the sign above the power; factorial binding; percent
  of the left side *and* percent as a plain hundredth (including `2+3×10%` = 2.3, the
  case that made the rule need a second pass); degree and radian trig; `tan` at a right
  angle; every domain error; half-typed tails; nonsense; constants; the `2nd`-page
  functions; radians and bracketing in the graph sampler; every programmer literal and
  operator, precedence, masked shifts, wraparound, and the four-base display; token
  backspace; and the tape's line format, right-splitting, cap, prepend and search.
* **`Verify the calculator work` — 42 checks** in CI, positives and negatives: the engine
  object and its signatures, the app's deleted `Evaluator`/`ProgrammerEvaluator`, the
  search controller calling the engine and its own `Parser` gone, `MathContext(12, …)`,
  the *absence* of `"%.8f".format(` and `"%.6f".format(`, the precedence lines, the
  tangent guard, the four-base display and the removed `.` key, the tape's search and
  share, the widget reading `oea_calc_v2` and not `oea_calculator`, `@+id/widget_root`,
  the decimal input type, the graph's sampler call and its touch handler.
* **`tools/check-kotlin.py`** — `STATIC CHECK OK — 311 sources`.

Nothing was compiled here (no Android SDK or Kotlin compiler in this workspace): the
test expectations were cross-checked against a Python model of the same grammar before
being written down, every guard was run locally, and CI is the compile signal.

## 5. What to check on a device

1. **`2^3^2`** in the calculator and in the launcher search box: both 512. `-2^2`: -4.
2. **Percent.** `200 + 10%` = 220, `200 − 10%` = 180, `200 × 10%` = 20, `2 + 3 × 10%` = 2.3.
3. **A comma-decimal phone** (set the language to German): `1 ÷ 3` = 0.333333333333, and
   pressing `+ 1 =` afterwards gives 1.333333333333 rather than Error.
4. **Programmer.** `0x1F` shows `HEX 0x1F · DEC 31 · OCT 0o37 · BIN 0b0001 1111`; `10 % 3`
   = 1; `7 ÷ 2` = 3; `NOT 0` = -1.
5. **Scientific.** `2nd`, then `sin⁻¹(1)` = 90 in DEG and 1.57079632679 in RAD; `x²`
   inserts `sqr(` and reads back.
6. **Graph.** The default `sin(x)` is a wave, not a line; pinch zooms about the fingers,
   drag pans, Reset restores; type `1/x` and see the curve break at 0.
7. **Converter.** Type `1.5` (the keyboard must offer a decimal point), check inches to
   cm; use Send to Calculator and confirm the value lands as a number.
8. **History.** Search the tape for part of an expression, tap a line to reuse its
   answer, then Share tape and check the text that reaches the share sheet.
9. **The widget.** Do a calculation, go Home: the widget shows that answer with the
   expression under it. It must not say "Calculator" after a calculation.
10. **Backspace.** Type `sin(` and press ⌫ once: the whole function goes.
