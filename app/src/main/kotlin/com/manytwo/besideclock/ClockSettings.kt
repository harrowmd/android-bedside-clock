package com.manytwo.besideclock

import android.content.Context
import android.graphics.Color

class ClockSettings(context: Context) {
    private val prefs = context.getSharedPreferences("clock_prefs", Context.MODE_PRIVATE)

    var color: Int
        get() = prefs.getInt("color", Color.parseColor("#FFAA00"))
        set(v) { prefs.edit().putInt("color", v).apply() }

    var fontFamily: String
        get() = prefs.getString("font", "sans-serif-thin") ?: "sans-serif-thin"
        set(v) { prefs.edit().putString("font", v).apply() }

    var fontSize: Float
        get() = prefs.getFloat("size", 96f)
        set(v) { prefs.edit().putFloat("size", v).apply() }

    var brightness: Float
        get() = prefs.getFloat("brightness", 0.15f)
        set(v) { prefs.edit().putFloat("brightness", v).apply() }
}
