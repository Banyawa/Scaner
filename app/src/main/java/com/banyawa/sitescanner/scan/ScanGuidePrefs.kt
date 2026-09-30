package com.banyawa.sitescanner.scan

import android.content.Context

/**
 * What the user has already been taught about scanning: whether the briefing before a
 * scan is still wanted, and how many recordings they have made (the coach shows itself
 * for the first few). Shares the "guide" preferences with the intro and user guide.
 */
object ScanGuidePrefs {
    private const val FILE = "guide"
    private const val BRIEFING_OFF = "scan_briefing_off"
    private const val RECORDINGS = "coach_scans"
    private const val NARRATION_OFF = "howto_narration_off"

    /** Recordings after which the coach no longer opens by itself. */
    const val COACHED_RECORDINGS = 3

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun briefingWanted(context: Context): Boolean = !prefs(context).getBoolean(BRIEFING_OFF, false)

    fun setBriefingWanted(context: Context, wanted: Boolean) {
        prefs(context).edit().putBoolean(BRIEFING_OFF, !wanted).apply()
    }

    /** Whether the coach should be open when the scan screen starts. */
    fun coachByDefault(context: Context): Boolean = prefs(context).getInt(RECORDINGS, 0) < COACHED_RECORDINGS

    /** Whether the how-to demonstration reads its captions aloud. */
    fun narrationWanted(context: Context): Boolean = !prefs(context).getBoolean(NARRATION_OFF, false)

    fun setNarrationWanted(context: Context, wanted: Boolean) {
        prefs(context).edit().putBoolean(NARRATION_OFF, !wanted).apply()
    }

    fun noteRecording(context: Context) {
        val p = prefs(context)
        p.edit().putInt(RECORDINGS, p.getInt(RECORDINGS, 0) + 1).apply()
    }
}
