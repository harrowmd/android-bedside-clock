package com.manytwo.besideclock

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private var logFile: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private const val MAX_LINES = 200

    fun init(context: Context) {
        logFile = resolveFile(context)
    }

    fun log(message: String) {
        val newLine = "[${fmt.format(Date())}] $message"
        val file = logFile ?: return
        try {
            val existing = if (file.exists()) file.readLines() else emptyList()
            val lines = (listOf(newLine) + existing).take(MAX_LINES)
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    fun getFile(): File? = logFile

    // Returns the path used, for display in the UI.
    fun filePath(): String = logFile?.absolutePath ?: "not initialised"

    private fun resolveFile(context: Context): File {
        // Prefer public Documents so the user can find it in any file manager.
        // MANAGE_EXTERNAL_STORAGE must be granted for this to work on Android 11+.
        if (Environment.isExternalStorageManager()) {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (docs.exists() || docs.mkdirs()) {
                return File(docs, "bedside-clock.log")
            }
        }
        // Fall back to app-specific external storage (shareable via the Share button).
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "bedside-clock.log")
    }
}
