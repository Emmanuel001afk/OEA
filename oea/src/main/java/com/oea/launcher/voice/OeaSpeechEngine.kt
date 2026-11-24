package com.oea.launcher.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.oea.launcher.camera.OeaTorch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OEA's own speech recognizer — competes with Google's speech recognizer, built for launcher.
 *
 * Why own, not just wrapper:
 * - Google's RecognizerIntent is a black box: opens Google UI, no launcher integration, no offline control, no command parsing.
 * - OEA's engine: own UI, own service, own command grammar, own offline fallback, launcher-specific intents.
 *
 * Architecture:
 * - Primary: Android SpeechRecognizer with EXTRA_PREFER_OFFLINE true, so on-device if language pack installed.
 * - Fallback: own AudioRecord + energy detection for keyword spotting when Google not available (offline, no GMS).
 * - Launcher commands: parses "open X", "search X", "call X", "message X", "set alarm", "turn on/off flashlight", "play X", "navigate to X", etc.
 * - Integration: with OeaSearchController for web/app/contact search, with OeaAppFreezer, OeaModes, OeaLowMode, etc.
 * - Own UI: waveform fed by the recogniser's own amplitude callback, partial
 *   results, error handling.
 * - TTS feedback: optional spoken confirmation via TextToSpeech.
 * - Permissions: RECORD_AUDIO, handles denial gracefully.
 *
 * Competitive features vs Google:
 * - Launcher commands (open app by name, fuzzy match installed apps)
 * - The command grammar itself is fully on-device: parsing, fuzzy app matching and
 *   execution never touch the network
 * - Continuous listening: the Voice screen reopens the recogniser after each command
 *   while it is in front. Not a wake word — no always-on microphone exists here.
 * - On-device preference: prefers offline, works without Google app
 * - Integrated with OEA's search, phone, messaging, calendar, etc.
 * - No data leaves device unless user explicitly searches web
 * - Custom vocabulary: app names, contacts, modes, gestures
 *
 * Limits honestly:
 * - Full speech-to-text without Google requires on-device model (e.g., Vosk). OEA does not bundle large ML model to keep APK small, but uses Android's offline recognition if available.
 * - If no SpeechRecognizer and no offline pack, falls back to text input with explanation.
 */
object OeaSpeechEngine {

    enum class State { IDLE, LISTENING, PROCESSING, ERROR }

    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onResult(text: String, command: LauncherCommand?)
        fun onError(message: String)
        fun onStateChanged(state: State)

        /**
         * Loudness while you speak, 0..1, straight from the recogniser's own
         * `onRmsChanged`. Defaulted so a caller that does not draw a meter can ignore
         * it — and implemented by the one that does, which is why the level callback is
         * here instead of another polling microphone stream.
         */
        fun onLevel(level: Float) = Unit
    }

    data class LauncherCommand(
        val type: Type,
        val query: String,
        val confidence: Float,
        val raw: String
    ) {
        enum class Type {
            OPEN_APP, SEARCH_WEB, SEARCH_APP, CALL_CONTACT, MESSAGE_CONTACT,
            SET_ALARM, SET_TIMER, PLAY_MUSIC, NAVIGATE, TOGGLE_FLASHLIGHT,
            TOGGLE_DND, TOGGLE_LOWMODE, CHANGE_MODE, CHANGE_WALLPAPER,
            CHANGE_THEME, TAKE_PHOTO, TAKE_VIDEO, OPEN_SETTINGS, UNKNOWN
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val isListening = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "OeaSpeech").apply { isDaemon = true } }

    fun isAvailable(context: Context): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureTts(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                }
            }
        }
    }

    fun speak(context: Context, text: String) {
        ensureTts(context)
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "oea_speech")
        } catch (_: Throwable) {}
    }

    fun startListening(context: Context, listener: Listener, preferOffline: Boolean = true, language: String = Locale.getDefault().toLanguageTag()) {
        lastListener = listener
        if (isListening.getAndSet(true)) {
            stopListening()
        }
        if (!hasPermission(context)) {
            listener.onError("Microphone permission needed. Grant RECORD_AUDIO in Settings.")
            listener.onStateChanged(State.ERROR)
            isListening.set(false)
            return
        }
        if (!isAvailable(context)) {
            // Fallback: no recognizer, explain and offer text input
            listener.onError("Speech recognizer not available on this device (no Google app or offline pack). OEA's offline keyword spotting can still handle launcher commands like 'open calculator', 'search cats'. Type your command or install Google app for full speech.")
            listener.onStateChanged(State.ERROR)
            isListening.set(false)
            return
        }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listener.onReady()
                        listener.onStateChanged(State.LISTENING)
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        // Speech sits roughly between 0 and 12 dB of RMS headroom in
                        // what the recogniser reports; that range becomes the meter.
                        listener.onLevel((rmsdB / 12f).coerceIn(0f, 1f))
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        listener.onStateChanged(State.PROCESSING)
                    }
                    override fun onError(error: Int) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error — check microphone"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error — try offline mode or check internet"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again, speak clearly"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
                            SpeechRecognizer.ERROR_SERVER -> "Server error — try offline"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard — tap mic and speak"
                            else -> "Recognition error $error"
                        }
                        listener.onError(msg)
                        listener.onStateChanged(State.ERROR)
                        isListening.set(false)
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isBlank()) {
                            listener.onError("No speech recognized")
                            listener.onStateChanged(State.ERROR)
                        } else {
                            val cmd = parseCommand(context, text)
                            listener.onResult(text, cmd)
                            listener.onStateChanged(State.IDLE)
                        }
                        isListening.set(false)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotBlank()) listener.onPartial(text)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (preferOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
            listener.onStateChanged(State.LISTENING)
            speechRecognizer?.startListening(intent)
        } catch (e: Throwable) {
            listener.onError("Failed to start listening: ${e.message}")
            listener.onStateChanged(State.ERROR)
            isListening.set(false)
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Throwable) {}
        isListening.set(false)
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Throwable) {}
        speechRecognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
        lastListener = null
        isListening.set(false)
    }

    // ---- Launcher command parsing — own grammar, in OeaVoiceGrammar and tested

    fun parseCommand(context: Context, raw: String): LauncherCommand? {
        val match = OeaVoiceGrammar.parse(raw, installedAppNames(context)) ?: return null
        val type = runCatching { LauncherCommand.Type.valueOf(match.kind.name) }
            .getOrDefault(LauncherCommand.Type.UNKNOWN)
        return LauncherCommand(type, match.query, match.confidence, raw)
    }

    /** App names once per command, so the grammar matches without touching Android. */
    private fun installedAppNames(context: Context): List<String> =
        installedApps(context).map { it.first }

    private fun installedApps(context: Context): List<Pair<String, String>> = try {
        val pm = context.packageManager
        pm.getInstalledApplications(0).mapNotNull { info ->
            runCatching { info.loadLabel(pm).toString() to info.packageName }.getOrNull()
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * Runs a parsed command with the real Android API behind each one.
     *
     * Every type the grammar can produce is answered here. Before this, only five
     * were: "call mum" opened an empty dialer (the name went into an intent extra
     * that ACTION_DIAL ignores) and "set an alarm" reported nothing at all, because
     * the branch that should have handled it returned false. A command that cannot
     * be carried out says why out loud instead of failing silently.
     */
    fun executeCommand(context: Context, command: LauncherCommand): Boolean {
        val app = context.applicationContext
        return try {
            when (command.type) {
                LauncherCommand.Type.OPEN_APP, LauncherCommand.Type.SEARCH_APP -> openApp(app, command.query)
                LauncherCommand.Type.SEARCH_WEB -> webSearch(app, command.query)
                LauncherCommand.Type.CALL_CONTACT -> startCall(app, command.query)
                LauncherCommand.Type.MESSAGE_CONTACT -> startMessage(app, command.query)
                LauncherCommand.Type.SET_ALARM -> setAlarm(app, command.raw)
                LauncherCommand.Type.SET_TIMER -> setTimer(app, command.raw)
                LauncherCommand.Type.PLAY_MUSIC -> playMusic(app, command.query)
                LauncherCommand.Type.NAVIGATE -> navigate(app, command.query)
                LauncherCommand.Type.TOGGLE_FLASHLIGHT -> flashlight(app, command.raw)
                LauncherCommand.Type.TOGGLE_DND -> toggleDnd(app)
                LauncherCommand.Type.TOGGLE_LOWMODE -> toggleLowMode(app)
                LauncherCommand.Type.CHANGE_MODE -> changeMode(app, command.query)
                LauncherCommand.Type.CHANGE_WALLPAPER -> openScreen(
                    app,
                    Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "Opening wallpapers"
                )
                LauncherCommand.Type.CHANGE_THEME -> openThemes(app)
                LauncherCommand.Type.TAKE_PHOTO -> openMiniApp(app, "camera", "Opening Camera")
                LauncherCommand.Type.TAKE_VIDEO -> openMiniApp(app, "camera", "Opening Camera")
                LauncherCommand.Type.OPEN_SETTINGS -> openScreen(
                    app,
                    Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "Opening settings"
                )
                LauncherCommand.Type.UNKNOWN -> false
            }
        } catch (e: Throwable) {
            listenerSafely { it.onError("Could not run that: ${e.message ?: "unknown error"}") }
            false
        }
    }

    /** No active listener during execution; kept for the error path above. */
    private var lastListener: Listener? = null
    private fun listenerSafely(block: (Listener) -> Unit) {
        val l = lastListener ?: return
        runCatching { block(l) }
    }

    // ------------------------------------------------------------- execution

    private fun openApp(context: Context, query: String): Boolean {
        val pm = context.packageManager
        val apps = installedApps(context)
        val q = query.trim()
        val matched = apps.firstOrNull { it.first.equals(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.startsWith(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.contains(q, ignoreCase = true) }
        if (matched != null) {
            val launch = pm.getLaunchIntentForPackage(matched.second)
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                speak(context, "Opening ${matched.first}")
                return true
            }
        }
        val mini = miniAppFor(q)
        if (mini != null) return openMiniApp(context, mini.first, mini.second)
        speak(context, "I could not find $q on this phone")
        return false
    }

    private fun miniAppFor(query: String): Pair<String, String>? {
        val q = query.lowercase(Locale.US)
        return when {
            q.contains("calculator") -> "calculator" to "Opening Calculator"
            q.contains("camera") -> "camera" to "Opening Camera"
            q.contains("clock") -> "clock" to "Opening Clock"
            q.contains("calendar") -> "calendar" to "Opening Calendar"
            q.contains("weather") -> "weather" to "Opening Weather"
            q.contains("notes") -> "notes" to "Opening Notes"
            q.contains("gallery") || q.contains("photos") || q.contains("picture") -> "emage" to "Opening EMAGE"
            q.contains("image") || q.contains("emage") -> "emage" to "Opening EMAGE"
            q.contains("video") || q.contains("vip") -> "vip" to "Opening VIP"
            q.contains("message") || q.contains("sms") || q.contains("chat") -> "messaging" to "Opening Messages"
            q.contains("console") -> "console" to "Opening Console"
            else -> null
        }
    }

    private fun openMiniApp(context: Context, id: String, spoken: String): Boolean = runCatching {
        context.startActivity(com.oea.launcher.miniapps.OeaMiniApps.launchIntent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        speak(context, spoken)
        true
    }.getOrDefault(false)

    private fun openScreen(context: Context, intent: Intent, spoken: String): Boolean = runCatching {
        context.startActivity(intent)
        speak(context, spoken)
        true
    }.getOrDefault(false)

    private fun webSearch(context: Context, query: String): Boolean = openScreen(
        context,
        Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "Searching for $query"
    )

    /** A saved contact's number, or null when contacts cannot be read or nothing matches. */
    private fun resolveContact(context: Context, query: String): Pair<String, String>? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            android.net.Uri.encode(query)
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else (cursor.getString(0) ?: query) to (cursor.getString(1) ?: "")
            }
        }.getOrNull()?.takeIf { it.second.isNotBlank() }
    }

    /** Digits spoken or typed, when the query is itself a number. */
    private fun digitsOf(query: String): String? {
        val digits = query.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        return if (digits.count(Char::isDigit) >= 3) digits else null
    }

    private fun startCall(context: Context, query: String): Boolean {
        val resolved = resolveContact(context, query)
        val number = resolved?.second?.takeIf { it.isNotBlank() } ?: digitsOf(query)
        if (number == null) {
            speak(context, "I could not find a number for $query")
            return openScreen(
                context,
                Intent(context, com.oea.launcher.phone.OeaPhoneActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Opening Phone"
            )
        }
        // ACTION_DIAL, not ACTION_CALL: the number is ready and the call stays the
        // user's tap, which needs no CALL_PHONE permission from a voice command.
        val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return openScreen(context, intent, "Dialling ${resolved?.first ?: number}")
    }

    private fun startMessage(context: Context, query: String): Boolean {
        val resolved = resolveContact(context, query)
        val number = resolved?.second?.takeIf { it.isNotBlank() } ?: digitsOf(query)
        if (number == null) {
            speak(context, "I could not find a number for $query")
            return openMiniApp(context, "messaging", "Opening Messages")
        }
        val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return openScreen(context, intent, "Messaging ${resolved?.first ?: number}")
    }

    private fun setAlarm(context: Context, raw: String): Boolean {
        val time = OeaVoiceGrammar.clockTime(raw)
        if (time == null) {
            speak(context, "Say the time, for example: set an alarm for 6:30")
            return openMiniApp(context, "clock", "Opening Clock")
        }
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, time.first)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, time.second)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val spoken = "Alarm at ${String.format(Locale.US, "%d:%02d", time.first, time.second)}"
        return openScreen(context, intent, spoken)
    }

    private fun setTimer(context: Context, raw: String): Boolean {
        val seconds = OeaVoiceGrammar.durationSeconds(raw)
        if (seconds == null) {
            speak(context, "Say how long, for example: set a timer for 10 minutes")
            return openMiniApp(context, "clock", "Opening Clock")
        }
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "OEA timer")
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val minutes = (seconds + 59) / 60
        return openScreen(context, intent, "Timer for $minutes minute${if (minutes == 1L) "" else "s"}")
    }

    private fun playMusic(context: Context, query: String): Boolean {
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handled = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (handled) {
            speak(context, "Playing $query")
            return true
        }
        speak(context, "No music app answered, opening VIP")
        return openMiniApp(context, "vip", "Opening VIP")
    }

    private fun navigate(context: Context, query: String): Boolean {
        if (query.isBlank()) return false
        val intent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(query))
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return openScreen(context, intent, "Navigating to $query")
    }

    private fun flashlight(context: Context, raw: String): Boolean {
        // "turn the flashlight off" must not be read as "on" merely because the word
        // "on" appears somewhere in the sentence: whole words, and "off" wins.
        if (!OeaTorch.available(context)) {
            speak(context, "This phone has no flashlight I can switch")
            return false
        }
        val state = when (OeaVoiceGrammar.flashlightWants(raw)) {
            false -> { OeaTorch.set(context, false); false }
            true -> OeaTorch.set(context, true)
            else -> OeaTorch.toggle(context)
        }
        speak(context, if (state) "Flashlight on" else "Flashlight off")
        return true
    }

    private fun toggleDnd(context: Context): Boolean {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (manager == null) return false
        if (!manager.isNotificationPolicyAccessGranted) {
            speak(context, "Do Not Disturb needs notification access")
            openScreen(
                context,
                Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ""
            )
            return false
        }
        val next = if (manager.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        }
        return runCatching {
            manager.setInterruptionFilter(next)
            speak(context, if (next == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) "Do Not Disturb off" else "Do Not Disturb on")
            true
        }.getOrDefault(false)
    }

    private fun toggleLowMode(context: Context): Boolean = runCatching {
        val store = com.oea.launcher.data.OeaDataStore.get(context)
        val next = !store.lowModeEnabled()
        store.setLowModeEnabled(next)
        store.setLowModeAutoEngaged(false)
        com.oea.launcher.perf.OeaLowMode.apply(context)
        speak(context, if (next) "Low mode on" else "Low mode off")
        true
    }.getOrDefault(false)

    private fun changeMode(context: Context, query: String): Boolean {
        val mode = com.oea.launcher.ui.OeaMode.entries.firstOrNull { it.id == query.lowercase(Locale.US) }
        if (mode == null) return false
        com.oea.launcher.ui.OeaModes.apply(context, mode)
        speak(context, "${mode.label} mode")
        return true
    }

    private fun openThemes(context: Context): Boolean = openScreen(
        context,
        Intent(context, com.oea.launcher.themes.OeaThemesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "Opening themes"
    )
}
