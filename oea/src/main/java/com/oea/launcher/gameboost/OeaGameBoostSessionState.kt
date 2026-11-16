package com.oea.launcher.gameboost

import android.app.NotificationManager
import android.content.Context
import android.content.Intent

/**
 * What a Game Boost session takes from the phone — and what it owes back.
 *
 * The panel's switches are not app settings. Do Not Disturb and a screen
 * recording are *device* state, and device state outlives the process that
 * changed it. Two rules follow from that, and both of them were being broken:
 *
 *  * **Return it.** When the session ends, everything goes back to exactly what
 *    it was before the game. "The phone was already silenced" is not the same as
 *    "there is nothing to put back": with no recorded baseline the honest
 *    restore is *all interruptions*, because OEA is the one that muted the phone
 *    and only OEA knows why.
 *  * **Survive death.** The old baseline lived in a service field. A process the
 *    system killed — low memory, an OEM battery manager, a swipe from Recents —
 *    left Do Not Disturb on with nothing left alive to restore it, which is how
 *    the phone stayed silent long after the game was closed. The promise is now
 *    written to disk, so the next start, the next visit to Game Boost, or simply
 *    the service shutting down can settle the debt.
 *
 * The rules themselves are pure functions over plain ints, so they are unit
 * tested; only the reading and the writing below touch Android.
 */
internal object OeaGameBoostSessionState {

    /** Recorded when the real filter could not be read (no notification policy access). */
    const val FILTER_UNKNOWN = -1

    // ------------------------------------------------------------------ rules

    /**
     * The baseline worth remembering. [current] only means something when the
     * phone has granted notification policy access; without it OEA cannot read
     * the filter, and recording a guess would later "restore" the phone to a
     * mode the user never chose.
     */
    fun baselineFor(current: Int, hasAccess: Boolean): Int =
        if (hasAccess) current else FILTER_UNKNOWN

    /**
     * The filter to put back. A session that silenced the phone with no usable
     * baseline restores *all interruptions* — never silence. Losing the record
     * of what the phone was doing is not a licence to leave it muted.
     */
    fun restoreTarget(baseline: Int): Int =
        if (baseline == FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else baseline

    /** Whether the filter has to move at all; a filter already in place is left alone. */
    fun shouldApply(current: Int, wanted: Int): Boolean = current != wanted

    /**
     * Whether a restore is owed. A session that never actually moved the filter
     * has nothing to put back, so a phone the user silenced by hand stays
     * exactly as they left it when the game closes.
     */
    fun owesRestore(applied: Boolean, baseline: Int, current: Int): Boolean =
        applied && current != restoreTarget(baseline)

    /**
     * The filter the panel asks for: priority interruptions while gaming,
     * everything when that switch is off. Off must be *enforced*, not merely
     * skipped — turning the switch off mid-game has to undo a silence that is
     * already applied, or the panel is lying.
     */
    fun wantedFilter(dndEnabled: Boolean): Int =
        if (dndEnabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
        else NotificationManager.INTERRUPTION_FILTER_ALL

    // ----------------------------------------------------------------- ledger

    private const val KEY_OPEN = "session_ledger_open"
    private const val KEY_GAME = "session_ledger_game"
    private const val KEY_DND_BASELINE = "session_ledger_dnd_baseline"
    private const val KEY_DND_APPLIED = "session_ledger_dnd_applied"
    private const val KEY_RECORDING = "session_ledger_recording"

    private fun ledger(context: Context) = OeaGameBoostStore.prefs(context)

    /** True while a session holds something it has not given back. */
    fun isOpen(context: Context): Boolean = ledger(context).getBoolean(KEY_OPEN, false)

    private fun diagnostic(priority: Int, message: String) {
        android.util.Log.println(priority, "OeaGameBoost", message)
    }

    /**
     * Opens the record for a continuous session.
     *
     * Called every time a session starts, but only the first call writes: a
     * game switch inside one uninterrupted session must not overwrite the
     * pre-game baseline with a value Game Boost itself set.
     */
    fun open(context: Context, game: String) {
        val prefs = ledger(context)
        if (prefs.getBoolean(KEY_OPEN, false)) return
        prefs.edit()
            .putBoolean(KEY_OPEN, true)
            .putString(KEY_GAME, game)
            .putInt(KEY_DND_BASELINE, FILTER_UNKNOWN)
            .putBoolean(KEY_DND_APPLIED, false)
            .putBoolean(KEY_RECORDING, false)
            .apply()
        diagnostic(android.util.Log.INFO, "session state opened for game=$game")
    }

    /** Notes that a screen recording is running, so leaving the game stops it. */
    fun noteRecording(context: Context, recording: Boolean) {
        if (!isOpen(context)) return
        ledger(context).edit().putBoolean(KEY_RECORDING, recording).apply()
    }

    fun isRecording(context: Context): Boolean =
        isOpen(context) && ledger(context).getBoolean(KEY_RECORDING, false)

    /**
     * Applies the panel's choices to the phone, remembering what it changed.
     *
     * Runs on entry and again whenever the game is confirmed in front, so a
     * Do Not Disturb the user flipped by hand during a match — or a switch they
     * changed in the panel — is put back the way the panel says.
     */
    fun applyWhileGaming(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            // Without access neither the current filter nor a change to it can be
            // read back honestly. Leave the phone exactly as the user has it.
            return
        }
        val prefs = ledger(context)
        if (!prefs.getBoolean(KEY_OPEN, false)) open(context, OeaGameBoost.activeGame ?: "")
        // Capture the baseline once, before the first change of the session.
        if (prefs.getInt(KEY_DND_BASELINE, FILTER_UNKNOWN) == FILTER_UNKNOWN) {
            val baseline = baselineFor(nm.currentInterruptionFilter, hasAccess = true)
            prefs.edit().putInt(KEY_DND_BASELINE, baseline).apply()
            diagnostic(android.util.Log.INFO, "session DND baseline recorded filter=$baseline")
        }
        val wanted = wantedFilter(OeaGameBoostStore.prefs(context).getBoolean("dnd", true))
        val moved = shouldApply(nm.currentInterruptionFilter, wanted)
        if (moved) {
            runCatching { nm.setInterruptionFilter(wanted) }
                .onFailure { error ->
                    diagnostic(
                        android.util.Log.ERROR,
                        "session DND apply failed wanted=$wanted error=${error.javaClass.simpleName}:${error.message}"
                    )
                }
        }
        if (moved || prefs.getBoolean(KEY_DND_APPLIED, false)) {
            prefs.edit().putBoolean(KEY_DND_APPLIED, true).apply()
        }
    }

    /**
     * Puts back everything the session took, and closes the record.
     *
     * Returns true when the phone's Do Not Disturb was actually changed, so the
     * caller can say so instead of leaving the user to wonder.
     */
    fun restore(context: Context): Boolean {
        val prefs = ledger(context)
        if (!prefs.getBoolean(KEY_OPEN, false)) return false
        val game = prefs.getString(KEY_GAME, null)
        val applied = prefs.getBoolean(KEY_DND_APPLIED, false)
        val baseline = prefs.getInt(KEY_DND_BASELINE, FILTER_UNKNOWN)
        var restored = false
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.isNotificationPolicyAccessGranted) {
            val target = restoreTarget(baseline)
            if (owesRestore(applied, baseline, nm.currentInterruptionFilter)) {
                runCatching { nm.setInterruptionFilter(target) }
                    .onSuccess { restored = true }
                    .onFailure { error ->
                        diagnostic(
                            android.util.Log.ERROR,
                            "session DND restore failed target=$target error=${error.javaClass.simpleName}:${error.message}"
                        )
                    }
            }
        } else if (applied) {
            // Access was taken away mid-session: the silence cannot be undone
            // from here, and pretending otherwise would hide it.
            diagnostic(android.util.Log.WARN, "session DND restore skipped: notification policy access was revoked")
        }
        stopRecording(context)
        prefs.edit()
            .putBoolean(KEY_OPEN, false)
            .remove(KEY_GAME)
            .remove(KEY_DND_BASELINE)
            .remove(KEY_DND_APPLIED)
            .remove(KEY_RECORDING)
            .apply()
        diagnostic(
            android.util.Log.INFO,
            "session state settled game=$game dndBaseline=$baseline dndRestored=$restored"
        )
        return restored
    }

    /**
     * Settles a promise no live session can keep.
     *
     * This is the process-death safety net: anywhere OEA knows no game is in
     * front, an open record means the session that opened it died without
     * restoring, and the phone is still holding a game's settings.
     */
    fun restoreIfOrphaned(context: Context, sessionRunning: Boolean): Boolean {
        if (sessionRunning) return false
        if (!isOpen(context)) return false
        diagnostic(android.util.Log.WARN, "session state found a debt with no live session; restoring")
        return restore(context)
    }

    /**
     * Stops a screen recording the session is still running.
     *
     * A recording that outlives the game is the same leak as a Do Not Disturb
     * that does — the file is finished and saved, it is not discarded.
     */
    fun stopRecording(context: Context): Boolean {
        val prefs = ledger(context)
        val recorded = prefs.getBoolean(KEY_RECORDING, false) ||
            OeaGameBoostStore.prefs(context).getBoolean("recording", false)
        OeaGameBoostStore.prefs(context).edit()
            .putBoolean("recording", false)
            .putBoolean("capture_active", false)
            .remove("capture_mode")
            .apply()
        if (!recorded) return false
        runCatching {
            context.startService(
                Intent(context, OeaGameCaptureService::class.java).setAction(OeaGameCaptureService.ACTION_STOP)
            )
        }.onFailure { error ->
            diagnostic(
                android.util.Log.ERROR,
                "session recording stop failed error=${error.javaClass.simpleName}:${error.message}"
            )
        }
        return true
    }
}
