package com.oea.launcher.miniapps

import android.content.Context
import android.net.Uri

/**
 * What VIP remembers: playlists, how far you got in every clip, the last speed
 * and aspect you chose, subtitle preferences, and whether audio may keep playing
 * when the player is hidden.
 *
 * All of it is a handful of preference rows rather than a database — a player has
 * no business shipping a schema for six integers, and prefs survive an app update
 * exactly as well as one would.
 */
object OeaVipStore {

    private const val PREFS = "oea_vip_v3"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_SPEED = "speed"
    private const val KEY_ASPECT = "aspect"
    private const val KEY_LOOP = "loop"
    private const val KEY_AUTO_NEXT = "auto_next"
    private const val KEY_BG_AUDIO = "background_audio"
    private const val KEY_SUB_OFFSET = "subtitle_offset"
    private const val KEY_SUB_SIZE = "subtitle_size"
    private const val KEY_SUB_ON = "subtitle_enabled"
    private const val KEY_SUBTITLES = "subtitle_files"
    private const val KEY_HIDE_CONTROLS = "hide_controls_ms"
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Playlist(val name: String, val uris: List<String>) {
        val size: Int get() = uris.size
    }

    enum class Aspect(val label: String, val blurb: String) {
        FIT("Fit", "Whole picture, bars if needed"),
        FILL("Fill", "Fill the screen, crop the edges"),
        STRETCH("Stretch", "Fill the screen, change the shape"),
        SIXTEEN_NINE("16:9", "Force a wide frame"),
        FOUR_THREE("4:3", "Force an old frame")
    }

    enum class Loop(val label: String) {
        OFF("No repeat"),
        ONE("Repeat this clip"),
        ALL("Repeat the queue")
    }

    // ---------------------------------------------------------------- playlists

    fun playlists(context: Context): List<Playlist> {
        val raw = prefs(context).getString(KEY_PLAYLISTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(SEP_ROW).mapNotNull { row ->
            val parts = row.split(SEP_FIELD)
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            Playlist(parts[0], parts[1].split('|').filter { it.isNotBlank() })
        }
    }

    fun savePlaylists(context: Context, list: List<Playlist>) {
        prefs(context).edit().putString(
            KEY_PLAYLISTS,
            list.filter { it.name.isNotBlank() }.joinToString(SEP_ROW) { "${it.name}$SEP_FIELD${it.uris.joinToString("|")}" }
        ).apply()
    }

    fun createPlaylist(context: Context, name: String): Boolean {
        val list = playlists(context).toMutableList()
        if (name.isBlank() || list.any { it.name.equals(name.trim(), ignoreCase = true) }) return false
        list.add(Playlist(name.trim(), emptyList()))
        savePlaylists(context, list)
        return true
    }

    fun renamePlaylist(context: Context, from: String, to: String): Boolean {
        val list = playlists(context).toMutableList()
        val index = list.indexOfFirst { it.name == from }
        if (index < 0 || to.isBlank()) return false
        list[index] = Playlist(to.trim(), list[index].uris)
        savePlaylists(context, list)
        return true
    }

    fun deletePlaylist(context: Context, name: String) {
        savePlaylists(context, playlists(context).filterNot { it.name == name })
    }

    fun addToPlaylist(context: Context, name: String, uri: Uri): Boolean {
        val list = playlists(context).toMutableList()
        val index = list.indexOfFirst { it.name == name }
        if (index < 0) return false
        val current = list[index]
        if (current.uris.contains(uri.toString())) return false
        list[index] = Playlist(current.name, current.uris + uri.toString())
        savePlaylists(context, list)
        return true
    }

    fun removeFromPlaylist(context: Context, name: String, uri: Uri) {
        val list = playlists(context).toMutableList()
        val index = list.indexOfFirst { it.name == name }
        if (index < 0) return
        val current = list[index]
        list[index] = Playlist(current.name, current.uris.filterNot { it == uri.toString() })
        savePlaylists(context, list)
    }

    /** Moves an entry one step through a playlist — the reorder a list needs. */
    fun moveInPlaylist(context: Context, name: String, uri: Uri, up: Boolean) {
        val list = playlists(context).toMutableList()
        val index = list.indexOfFirst { it.name == name }
        if (index < 0) return
        val uris = list[index].uris.toMutableList()
        val at = uris.indexOf(uri.toString())
        val to = if (up) at - 1 else at + 1
        if (at < 0 || to < 0 || to >= uris.size) return
        val moved = uris.removeAt(at)
        uris.add(to, moved)
        list[index] = Playlist(name, uris)
        savePlaylists(context, list)
    }

    fun itemsOf(context: Context, name: String, all: List<OeaMediaLibrary.Item>): List<OeaMediaLibrary.Item> {
        val playlist = playlists(context).firstOrNull { it.name == name } ?: return emptyList()
        val byKey = all.associateBy { it.key }
        return playlist.uris.mapNotNull { byKey[it] }
    }

    // -------------------------------------------------------------- subtitles

    /**
     * The subtitle file chosen for one clip. Kept per clip on purpose: a shared
     * "last subtitle" would happily put the wrong show's cues over your film.
     */
    fun subtitleFor(context: Context, clip: Uri?): Uri? {
        val key = clip?.toString() ?: return null
        val raw = prefs(context).getString(KEY_SUBTITLES, "") ?: ""
        val value = raw.split(SEP_ROW)
            .firstOrNull { it.startsWith("$key$SEP_FIELD") }
            ?.substringAfter(SEP_FIELD)
            ?.takeIf { it.isNotBlank() && it != "none" } ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    fun rememberSubtitle(context: Context, clip: Uri?, subtitle: Uri?) {
        val key = clip?.toString() ?: return
        val rows = (prefs(context).getString(KEY_SUBTITLES, "") ?: "").split(SEP_ROW).toMutableList()
        rows.removeAll { it.startsWith("$key$SEP_FIELD") }
        rows.add(0, "$key$SEP_FIELD${subtitle ?: "none"}")
        prefs(context).edit().putString(KEY_SUBTITLES, rows.filter { it.isNotBlank() }.take(100).joinToString(SEP_ROW)).apply()
    }

    // ------------------------------------------------------------------ history

    /** Where you stopped, per clip, so "Continue watching" is a real row. */
    fun position(context: Context, uri: Uri): Long =
        prefs(context).getString(KEY_PROGRESS, "")?.split(SEP_ROW)
            ?.firstOrNull { it.startsWith("${uri}$SEP_FIELD") }
            ?.substringAfter(SEP_FIELD)?.toLongOrNull() ?: 0L

    fun rememberPosition(context: Context, uri: Uri, positionMs: Long, durationMs: Long) {
        val rows = (prefs(context).getString(KEY_PROGRESS, "") ?: "").split(SEP_ROW).toMutableList()
        rows.removeAll { it.startsWith("${uri}$SEP_FIELD") }
        val finished = durationMs > 0 && positionMs >= durationMs - 4_000L
        if (!finished && positionMs > 3_000L) rows.add(0, "$uri$SEP_FIELD$positionMs")
        prefs(context).edit().putString(KEY_PROGRESS, rows.filter { it.isNotBlank() }.take(200).joinToString(SEP_ROW)).apply()
    }

    fun continueWatching(context: Context, all: List<OeaMediaLibrary.Item>): List<Pair<OeaMediaLibrary.Item, Long>> {
        val rows = (prefs(context).getString(KEY_PROGRESS, "") ?: "").split(SEP_ROW)
            .mapNotNull { row ->
                val parts = row.split(SEP_FIELD)
                if (parts.size < 2) return@mapNotNull null
                parts[0] to (parts[1].toLongOrNull() ?: 0L)
            }
        val byUri = all.associateBy { it.key }
        return rows.mapNotNull { (uri, at) -> byUri[uri]?.let { it to at } }
    }

    // -------------------------------------------------------------- player state

    fun speedOf(context: Context): Float = prefs(context).getFloat(KEY_SPEED, 1f).coerceIn(0.25f, 4f)
    fun setSpeed(context: Context, value: Float) =
        prefs(context).edit().putFloat(KEY_SPEED, value.coerceIn(0.25f, 4f)).apply()

    fun aspectOf(context: Context): Aspect = runCatching {
        Aspect.valueOf(prefs(context).getString(KEY_ASPECT, Aspect.FIT.name) ?: Aspect.FIT.name)
    }.getOrDefault(Aspect.FIT)

    fun setAspect(context: Context, value: Aspect) = prefs(context).edit().putString(KEY_ASPECT, value.name).apply()

    fun loopOf(context: Context): Loop = runCatching {
        Loop.valueOf(prefs(context).getString(KEY_LOOP, Loop.OFF.name) ?: Loop.OFF.name)
    }.getOrDefault(Loop.OFF)

    fun setLoop(context: Context, value: Loop) = prefs(context).edit().putString(KEY_LOOP, value.name).apply()

    fun autoNext(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_NEXT, true)
    fun setAutoNext(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_NEXT, value).apply()

    fun backgroundAudio(context: Context): Boolean = prefs(context).getBoolean(KEY_BG_AUDIO, false)
    fun setBackgroundAudio(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BG_AUDIO, value).apply()

    fun subtitleOffset(context: Context): Long = prefs(context).getLong(KEY_SUB_OFFSET, 0L)
    fun setSubtitleOffset(context: Context, value: Long) = prefs(context).edit().putLong(KEY_SUB_OFFSET, value).apply()

    fun subtitleSize(context: Context): Float = prefs(context).getFloat(KEY_SUB_SIZE, 1f).coerceIn(0.6f, 2.4f)
    fun setSubtitleSize(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_SUB_SIZE, value.coerceIn(0.6f, 2.4f)).apply()

    fun subtitlesOn(context: Context): Boolean = prefs(context).getBoolean(KEY_SUB_ON, true)
    fun setSubtitlesOn(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SUB_ON, value).apply()

    fun hideControlsAfter(context: Context): Long = prefs(context).getLong(KEY_HIDE_CONTROLS, 3800L)
    fun setHideControlsAfter(context: Context, value: Long) = prefs(context).edit().putLong(KEY_HIDE_CONTROLS, value).apply()

    /** The label a list shows beside a clip: "12:03 of 48:10" or "Unwatched". */
    fun watchedLabel(positionMs: Long, durationMs: Long): String {
        if (durationMs <= 0L) return "New"
        if (positionMs <= 0L) return "Not started"
        val percent = (positionMs * 100L / durationMs).toInt().coerceIn(1, 99)
        return "$percent% watched • ${OeaMediaLibrary.formatDuration(positionMs)} in"
    }

    /** The speeds the sheet offers — fine control at the bottom, TV speed at the top. */
    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f)

    fun speedLabel(value: Float): String =
        if (value == 1f) "Normal" else "${value.toString().trimEnd('0').trimEnd('.')}×"
}
