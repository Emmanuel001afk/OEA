# Weather review — 24 November 2026

Weather is the one mini-app that talks to a service, so the ways it can be wrong are
different from the ones in the [Calendar](calendar-review-2026-11-24.md) or the
[mini-app response pass](miniapp-response-review-2026-11-23.md): not missing features
but **numbers that lie quietly** — a screen that says °F and "12 km/h", a cache that
belongs to the wrong city, an old reading presented as this morning's.

The file's own header makes promises: "the wind and rain figures follow the same choice,
because an app that shows 90 °F next to 12 km/h is half converted and therefore wrong",
and "the home-screen weather widget reads the cache written here, so the two never
disagree". Both were false before this pass.

Everything in §1 changed here. §2 is what was checked and already right, §3 what is
deliberately still missing, §4 how it is verified, §5 what only a phone can settle.

---

## 1. What was wrong, and what changed

### 1.1 The day sheet was half converted

The main card converted wind; the **day sheet did not convert anything**. In Fahrenheit
mode — the screen the header says must never be half converted — tapping a day showed:

* `Rain · 3.2 mm total, 40% chance` (millimetres, in an app set to inches),
* `Gusts · 71 km/h` (kilometres per hour, in an app set to miles per hour),
* and high/low printed straight from the metric value as `12.34° / 5.67°`.

**Changed.** `OeaWeatherFormat` (`miniapps/OeaWeatherFormat.kt`) is now the only place a
number is converted or a unit is written: `degrees()`, `speed()`, `rain()`,
`pressure()`. The card, the hourly rail, the seven-day rows, the day sheet and the
advisories all call it, so there is no second path that can forget.

### 1.2 Every temperature was truncated, not rounded

`shown()` was `value.toInt()`, so 21.9 °C read **21** and 71.4 °F read **71**; worse,
between -1 and 0 the sign flipped a degree early. Every other weather app on the phone
was a degree off from this one for most of the day.

**Changed.** `temperature()` rounds (`roundToInt`), including towards zero for negatives
(-0.4 → 0, -1.6 → -2).

### 1.3 The widget and the app disagreed about the unit — with two stored settings

`OeaWidgetStudio.weatherUsesFahrenheit` read a pref in the **widget's own** preference
file; the Weather screen read `oea_weather.unit_celsius`. Tapping °F in one changed only
that one, so the home-screen widget could show 71°F while the app showed 21°C for the
same reading — the exact disagreement the header promises cannot happen.

**Changed.** One stored value: `OeaWidgetStudio.weatherUsesFahrenheit` now reads
`oea_weather.unit_celsius` and, on first use after upgrading, **adopts** the old
widget pref once so nobody's setting flips under them. Tapping the unit in Weather also
repaints the widget. Both directions share one key.

### 1.4 Two vocabularies for the same weather code

The app said `Rain showers`, `Freezing drizzle`, `Thunderstorm with hail`; the widget had
its own shorter table and said `Showers`, and for 56 or 96 fell through to
`Weather 56` / `Weather 96`.

**Changed.** `OeaWeatherFormat.description(code)` is the only table; the widget uses it.

### 1.5 A cached reading was not tied to a place

One `cache_json` and one `last_fetch` covered every location. The sequence that hurt:
pick Tokyo, go offline, reopen Weather — the card printed **"Tokyo" above Lagos's
temperature**, with Lagos's sunrise and a timestamp that made it look current. The air
quality card had the same bug on its own cache.

**Changed.** The cache stores the place it belongs to (`OeaWeatherFormat.placeKey`, three
decimals ≈ 110 m) and is only used when the place matches. Offline with a reading for
*this* place still works — that is what the cache is for — and a reading for another
place is treated as no reading at all, because a stale reading is honest and a
mislabelled one is not. The widget applies the same rule (and, when the app is following
the device fix rather than a saved city, trusts the cache's own place).

### 1.6 "Updated 09:12" could mean yesterday morning

The cache has no expiry for display: a reading from last week was shown with an
`Updated 09:12` line and nothing else. The `.ics`-style honesty the app claims elsewhere
was missing exactly where age matters most.

**Changed.** `age()` says it in words (`just now`, `5 min ago`, `2 h ago`, `3 days ago`)
in the subtitle, the footnote, and the widget's second line; `stale()` (older than a day)
raises a visible banner: "Showing a reading from 22 Nov, 09:12 — that is 2 days old."

### 1.7 A slow answer could repaint over a newer one

Every reload started a task and the last one to **finish** painted. Tap refresh twice,
or switch city while the first request is in flight, and the older response could land
after the newer one — the screen settling on the previous city's forecast. The geocoder
had the same race per keystroke, with no debounce at all: typing "lagos" fired six
requests and whichever returned last filled the list.

**Changed.** A generation counter in the screen; a fetch paints only if it is still the
newest (`isCurrent`). The city search debounces 280 ms and carries a token, so only the
newest question may paint an answer.

### 1.8 No timeout on any request

`URL.readText()` uses the platform default and has no timeout at all: on a captive portal
or in a tunnel the worker waits for minutes and the screen sits on "Updating forecast…"
with nothing to press, because the Retry button only exists after a failure.

**Changed.** `readUrl()` — 10 s connect, 15 s read, JSON accept header, explicit
disconnect — used by the forecast, the air-quality call and the geocoder.

### 1.9 The cache timestamp only moved when the bytes changed

`if (cached != text)` guarded the write of `last_fetch` too, so a forecast that returned
byte-identical JSON (very common for a stable day) never refreshed its timestamp: the
cache looked stale forever and every open of the app re-downloaded.

**Changed.** A successful request always updates the timestamp, whether or not the body
changed.

### 1.10 Smaller faults fixed on the way

* the air-quality card's PM2.5 value had no unit while PM10's had one, and both used the
  device locale for the decimal separator in a mixed string;
* the advisory sentences were computed in °C and printed raw ("High of 40°") inside a
  screen showing °F — they now convert, while the thresholds stay metric (the source
  data is metric; a threshold that changed with the unit would be a different rule);
* the connectivity check ran on the UI thread in the failure path — it is a binder call
  and now runs on the worker;
* `isOnline` carried a pre-Marshmallow branch that cannot run on this app's minimum
  (API 26) and used the deprecated `activeNetworkInfo`;
* there was no resume hook, so returning to Weather after hours showed whatever had been
  painted; a resume now re-checks the half-hour cache.

---

## 2. What was already right

* **The air-quality call was already separate, cached and non-fatal**, and a phone in an
  uncovered country simply gets no card — that design survived unchanged.
* **The hourly rail already started at the current hour** rather than midnight, which is
  the bug most weather apps ship.
* **The units were passed down explicitly** (a `celsius: Boolean`), not read ad hoc in
  each view, which is why the fix was a matter of routing through one object rather than
  plumbing a theme.
* **The network and JSON parsing were already off the main thread**, and results were
  posted back with an `isDestroyed` check.
* **The advisories were already OEA's own and labelled as such** — thresholds in metric,
  wording that says where the number came from, and no pretence of an official warning
  feed.

---

## 3. What this pass did not change, and why

* **No reverse geocoding for the device fix.** With device location the card says
  "6.524, 3.379". A place name needs a second service and a privacy decision; the
  coordinates are honest and the search box puts a name in one tap.
* **No background refresh.** Weather updates when you open it, when you resume it, when
  you ask, and when the widget's system interval fires. A foreground service for a
  forecast is not worth the battery, and `WorkManager`-style periodic refresh is a
  scheduling question that belongs with the app's other background work.
* **The 30-minute cache is unchanged.** It is the reason opening Weather offline still
  tells you something; §1.6 makes the age visible instead of shortening it.
* **No per-hour air quality or pollen.** The endpoint offers neither for free, and
  inventing them is the one thing this app refuses to do.

---

## 4. Verification

* `OeaWeatherFormatTest` — 18 JVM cases: rounding at the .5 and negative edges,
  Fahrenheit conversion, km/h↔mph at the advisory threshold itself, mm↔in, pressure,
  the place key (stability, difference, locale-independence), one vocabulary per code,
  the air-quality band edges, every advisory threshold at 59.9/60.0, 94/95/99, 7.9/8.0,
  2.1/2.0, 19.9/20.0, 37.9/38.0, the same advisories rendered in both units, `age()` in
  four bands and `stale()` across a day.
* `Verify the weather work` — 28 guard checks: the format object's functions, the app
  calling it for temperature/rain/gusts/pressure, the dead helpers gone, `readUrl` with
  timeouts and **no** bare `URL(url).readText()`, the generation guard and resume hook,
  the place-keyed cache in both the app and the widget, the shared weather description,
  the search debounce, and the one-unit rule in `OeaWidgetStudio`.
* `tools/check-kotlin.py` — `STATIC CHECK OK — 309 sources`.

Nothing was compiled on this machine (no Android SDK or Kotlin compiler in this
workspace); CI is the compile signal and every guard ran locally against the tree first.
The run that carries this change set is named in the pull request.

## 5. What to check on a device

1. **Units everywhere.** Switch to °F and open a day in the seven-day list. Rain must be
   inches, gusts mph, high/low °F, and the advisory sentences (°F) must agree with the
   card. Switch back and check the same numbers invert cleanly.
2. **The widget follows.** With a weather widget on Home, tap the unit in Weather. The
   widget must flip without being re-added, and the two must show the same number for
   the same reading.
3. **The cache belongs to the city.** Load Lagos, then search Tokyo and open it; then
   turn on airplane mode and reopen Weather. It must show Tokyo only if Tokyo was
   actually fetched, otherwise the offline error — never Tokyo's name over Lagos's
   numbers.
4. **Age.** Turn the phone's clock forward two days with airplane mode on and reopen:
   the card must say how old the reading is and raise the "showing a reading from…"
   banner.
5. **A dead network.** Put the phone on a captive-portal Wi-Fi (connects, no internet).
   The screen must fail in about fifteen seconds with "Forecast unavailable" and a
   working Retry — not sit on "Updating forecast…".
6. **Races.** Tap refresh three times quickly; then tap a city in the search results
   while another city's load is running. The screen must settle on the last thing you
   asked for.
7. **The city search.** Type "lagos" fast: one request should leave after you stop
   typing, and the list must match the text in the box even if you type more.
8. **Air quality.** In a covered country the card shows AQI with PM2.5 and PM10 in
   µg/m³; in an uncovered one the card is simply absent and nothing else changes.
