package com.manytwo.besideclock

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private var logFile: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "bedside-clock.log")
    }

    fun log(message: String) {
        val ts = fmt.format(Date())
        try {
            logFile?.appendText("[$ts] $message\n")
        } catch (_: Exception) {}
    }

    fun getFile(): File? = logFile
}
