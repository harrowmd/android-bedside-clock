package com.manytwo.besideclock

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.manytwo.besideclock.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Logger.init(this)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}  ·  ${BuildConfig.BUILD_DATE}"

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        }

        binding.btnPreview.setOnClickListener {
            startActivity(Intent(this, ClockActivity::class.java))
        }

        binding.btnGrantBrightness.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        binding.btnGrantStorage.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }

        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnShareLog.setOnClickListener { shareLog() }
        binding.btnOpenDisplaySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        binding.btnOpenSecuritySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }

        if (!Settings.System.canWrite(this)) {
            AlertDialog.Builder(this)
                .setTitle("Brightness control")
                .setMessage(
                    "To let the clock slider control screen brightness, " +
                    "Android requires one extra step.\n\n" +
                    "Tap \"Open Settings\", find Bedside Clock in the list, " +
                    "and turn the toggle ON."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.init(this) // re-resolve in case storage permission was just granted

        val brightnessGranted = Settings.System.canWrite(this)
        binding.btnGrantBrightness.text = if (brightnessGranted)
            "Brightness permission: granted ✓"
        else
            getString(R.string.grant_brightness)

        val storageGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        binding.btnGrantStorage.text = if (storageGranted)
            "Log file: Documents/bedside-clock.log ✓"
        else
            "Grant log file access (Documents)"

        updatePhoneSettings()
    }

    private fun updatePhoneSettings() {
        val cr = contentResolver

        val timeoutMs = Settings.System.getLong(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1L)
        binding.tvSettingScreenTimeout.text = "Screen timeout: ${formatDuration(timeoutMs)}"

        val lockAfterMs = try {
            Settings.Secure.getLong(cr, "lock_screen_lock_after_timeout", -1L)
        } catch (_: Exception) { -1L }
        val lockText = when {
            lockAfterMs < 0 -> "Lock after timeout: unknown"
            lockAfterMs == 0L -> "Lock after timeout: Immediately ⚠ (clock may not start)"
            else -> "Lock after timeout: ${formatDuration(lockAfterMs)} ✓"
        }
        binding.tvSettingLockAfter.text = lockText
        binding.tvSettingLockAfter.setTextColor(
            if (lockAfterMs == 0L) 0xFFFF6600.toInt() else android.graphics.Color.GRAY
        )

        val adaptiveSleep = try {
            Settings.Secure.getInt(cr, "adaptive_sleep", -1)
        } catch (_: Exception) { -1 }
        binding.tvSettingAdaptiveSleep.text = when (adaptiveSleep) {
            0 -> "Adaptive sleep: off ✓"
            1 -> "Adaptive sleep: on ⚠ (may delay clock start)"
            else -> "Adaptive sleep: not available"
        }
        binding.tvSettingAdaptiveSleep.setTextColor(
            if (adaptiveSleep == 1) 0xFFFF6600.toInt() else android.graphics.Color.GRAY
        )
    }

    private fun formatDuration(ms: Long): String = when {
        ms <= 0 -> "unknown"
        ms < 60_000 -> "${ms / 1000} seconds"
        ms < 3_600_000 -> "${ms / 60_000} minutes"
        else -> "${ms / 3_600_000} hours"
    }

    private fun shareLog() {
        val file = Logger.getFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, "No log yet — start the clock first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Bedside Clock log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share log via"
        ))
    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "Checking…"
        Thread {
            val (latestTag, releaseUrl) = fetchLatestRelease()
            runOnUiThread {
                binding.btnCheckUpdate.isEnabled = true
                val latest = latestTag.removePrefix("v")
                if (latest.isNotEmpty() && latest != BuildConfig.VERSION_NAME) {
                    binding.btnCheckUpdate.text = "Update available: v$latest"
                    AlertDialog.Builder(this)
                        .setTitle("Update available")
                        .setMessage("Version $latest is available.\nYou have ${BuildConfig.VERSION_NAME}.")
                        .setPositiveButton("Open download page") { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else if (latest.isEmpty()) {
                    binding.btnCheckUpdate.text = "Check for Updates"
                    Toast.makeText(this, "Could not check for updates", Toast.LENGTH_SHORT).show()
                } else {
                    binding.btnCheckUpdate.text = "Up to date ✓"
                    binding.root.postDelayed({
                        binding.btnCheckUpdate.text = "Check for Updates"
                    }, 3_000)
                }
            }
        }.start()
    }
}

fun fetchLatestRelease(): Pair<String, String> {
    return try {
        val conn = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode != 200) return "" to ""
        val body = conn.inputStream.bufferedReader().readText()
        val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
        val url = Regex(""""html_url"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
        tag to url
    } catch (_: Exception) {
        "" to ""
    }
}
