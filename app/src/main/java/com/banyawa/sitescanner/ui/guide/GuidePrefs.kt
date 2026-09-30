package com.banyawa.sitescanner.ui.guide

import android.content.Context
import android.content.SharedPreferences

/**
 * Which guidance the user has already seen, in the "guide" preferences file. The scan
 * coach keeps its own keys in the same file, so only single keys are ever written here.
 */
object GuidePrefs {
    private const val FILE = "guide"
    private const val INTRO_DONE = "intro_done"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True once the first-launch intro was finished or skipped. */
    fun introDone(context: Context): Boolean = prefs(context).getBoolean(INTRO_DONE, false)

    fun setIntroDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(INTRO_DONE, done).apply()
    }
}
