package com.manytwo.besideclock

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/**
 * Drives the clock face + settings overlay defined in dream_clock.xml.
 * Shared between ClockDream (overnight screensaver) and ClockActivity
 * (manual daytime use) so both present identical UI with no duplicated logic.
 *
 * Does NOT handle anything specific to running as an overnight dream
 * (wake lock, AOD/pick-up-pulse disable, session elapsed-time logging) —
 * that stays in ClockDream.
 */
class ClockController(
    private val context: Context,
    private val window: Window,
    private val root: View,
    private val onExit: () -> Unit
) {
    private val settings = ClockSettings(context)
    private val handler = Handler(Looper.getMainLooper())

    private val driftContainer: FrameLayout = root.findViewById(R.id.clock_drift_container)
    private val layoutSingle: LinearLayout = root.findViewById(R.id.layout_single)
    private val layoutGiant: LinearLayout = root.findViewById(R.id.layout_giant)
    private val tvTimeSingle: TextClock = root.findViewById(R.id.tv_time_single)
    private val tvHour: TextClock = root.findViewById(R.id.tv_hour)
    private val tvMinute: TextClock = root.findViewById(R.id.tv_minute)
    private val tvDate: TextView = root.findViewById(R.id.tv_date)
    private val tvDateGiant: TextView = root.findViewById(R.id.tv_date_giant)
    private val tvBattery: TextView = root.findViewById(R.id.tv_battery)
    private val overlaySettings: FrameLayout = root.findViewById(R.id.overlay_settings)
    private val rowColors: LinearLayout = root.findViewById(R.id.row_colors)
    private val rowFonts: LinearLayout = root.findViewById(R.id.row_fonts)
    private val rowSizes: LinearLayout = root.findViewById(R.id.row_sizes)
    private val sliderBrightness: SeekBar = root.findViewById(R.id.slider_brightness)
    private val tvVersion: TextView = root.findViewById(R.id.tv_version)
    private val btnCheckUpdate: TextView = root.findViewById(R.id.btn_check_update)

    private var dateTimer: Timer? = null

    // Saved so we can restore system brightness when the clock closes
    private var savedBrightness: Int = -1
    private var savedBrightnessMode: Int = -1

    private val driftRunnable = object : Runnable {
        override fun run() {
            driftClock()
            handler.postDelayed(this, DRIFT_INTERVAL_MS)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val voltMV = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                val pct = level * 100 / scale
                val currentµA = try {
                    val v = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    if (v == Integer.MIN_VALUE) 0 else v
                } catch (_: Exception) { 0 }
                val watts = if (voltMV > 0 && currentµA != 0)
                    Math.abs(currentµA.toLong()).toFloat() / 1_000_000f * voltMV / 1000f else 0f
                val wStr = if (watts > 0.1f)
                    " (${if (charging) "+" else "-"}${"%.1f".format(watts)}W)" else ""
                tvBattery.text = if (charging) "⚡ $pct%$wStr" else "$pct%$wStr"
            }
        }
    }

    companion object {
        private const val DRIFT_INTERVAL_MS = 3 * 60 * 1000L  // move every 3 minutes
        private const val DRIFT_DURATION_MS = 60 * 1000L       // glide over 60 seconds
        private const val DRIFT_MAX_DP      = 22               // max offset in either axis
    }

    fun start() {
        tvVersion.text = "v${BuildConfig.VERSION_NAME}  ·  ${BuildConfig.BUILD_DATE}"
        btnCheckUpdate.setOnClickListener { checkForUpdate() }

        root.findViewById<TextView>(R.id.btn_settings).setOnClickListener { openSettings() }
        root.findViewById<View>(R.id.backdrop).setOnClickListener { closeSettings() }
        root.findViewById<TextView>(R.id.btn_dismiss).setOnClickListener { closeSettings() }
        root.findViewById<TextView>(R.id.btn_exit_clock).setOnClickListener { onExit() }
        root.findViewById<View>(R.id.btn_exit_quick).setOnClickListener { onExit() }

        sliderBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                // Map 0–100 → 0.01–1.0, enforcing a dim floor at progress 1
                val level = if (progress == 0) 0.01f else progress / 100f
                settings.brightness = level
                applyBrightness(level)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                Logger.log("Brightness changed → ${sb.progress}%")
            }
        })

        // Save current system brightness so we can restore it when the clock closes
        if (Settings.System.canWrite(context)) {
            savedBrightness = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            savedBrightnessMode = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        applyBrightness(settings.brightness)

        buildColorRow()
        buildFontRow()
        buildSizeRow()
        applyAll()

        updateDates()
        dateTimer = Timer().also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { tvDate.post { updateDates() } }
            }, 60_000L - System.currentTimeMillis() % 60_000L, 60_000L)
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(batteryReceiver, filter)
        }

        handler.postDelayed(driftRunnable, DRIFT_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacks(driftRunnable)
        driftContainer.animate().cancel()
        dateTimer?.cancel(); dateTimer = null
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        // Restore system brightness to what it was before the clock was shown
        if (Settings.System.canWrite(context) && savedBrightness >= 0) {
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, savedBrightnessMode)
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, savedBrightness)
        }
    }

    // ── Apply all saved settings to the clock display ────────────────────────

    private fun applyAll() {
        val color    = settings.color
        val typeface = Typeface.create(settings.fontFamily, Typeface.NORMAL)
        val size     = settings.fontSize
        val giant    = size >= 150f

        layoutSingle.visibility = if (giant) View.GONE  else View.VISIBLE
        layoutGiant.visibility  = if (giant) View.VISIBLE else View.GONE

        val allText = listOf(tvTimeSingle, tvHour, tvMinute, tvDate, tvDateGiant, tvBattery)
        allText.forEach { it.setTextColor(color) }
        allText.forEach { it.typeface = typeface }

        if (!giant) tvTimeSingle.textSize = size
        // Giant clock uses the fixed 160sp set in XML; hour and minute lines
        // always show the same font as selected.
    }

    // ── Settings overlay ─────────────────────────────────────────────────────

    private fun openSettings() {
        sliderBrightness.progress = (settings.brightness * 100).toInt()
        buildColorRow()
        buildFontRow()
        buildSizeRow()
        overlaySettings.visibility = View.VISIBLE
        Logger.log("Settings opened")
    }

    private fun closeSettings() {
        overlaySettings.visibility = View.GONE
        Logger.log("Settings closed")
    }

    private fun buildColorRow() {
        val palette = listOf(
            0xFFFFAA00.toInt() to "Amber",
            0xFFFFFFFF.toInt() to "White",
            0xFFDDDDDD.toInt() to "Silver",
            0xFF88CCFF.toInt() to "Ice Blue",
            0xFF00E5FF.toInt() to "Cyan",
            0xFF1DE9B6.toInt() to "Teal",
            0xFF69FF47.toInt() to "Green",
            0xFFCCFF00.toInt() to "Lime",
            0xFFFFFF00.toInt() to "Yellow",
            0xFFFF6D00.toInt() to "Orange",
            0xFFFF5252.toInt() to "Red",
            0xFFFF4081.toInt() to "Pink",
            0xFFEA80FC.toInt() to "Purple",
            0xFF7C4DFF.toInt() to "Indigo",
        )
        rowColors.removeAllViews()
        val sz   = dp(44)
        val gap  = dp(10)
        val ring = dp(3)
        palette.forEach { (color, name) ->
            val selected = color == settings.color
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke(ring, Color.WHITE)
            }
            rowColors.addView(View(context).apply {
                background = bg
                contentDescription = name
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = gap }
                setOnClickListener {
                    Logger.log("Colour changed → $name (#%06X)".format(color and 0xFFFFFF))
                    settings.color = color
                    applyAll()
                    buildColorRow()
                }
            })
        }
    }

    private fun buildFontRow() {
        val fonts = listOf(
            "sans-serif-thin"   to "Thin",
            "sans-serif-light"  to "Light",
            "sans-serif"        to "Regular",
            "sans-serif-medium" to "Medium",
            "monospace"         to "Mono",
            "serif"             to "Serif",
        )
        rowFonts.removeAllViews()
        fonts.forEach { (family, label) ->
            rowFonts.addView(chip(label, family == settings.fontFamily,
                Typeface.create(family, Typeface.NORMAL)) {
                Logger.log("Font changed → $label ($family)")
                settings.fontFamily = family
                applyAll()
                buildFontRow()
            })
        }
    }

    private fun buildSizeRow() {
        val sizes = listOf(64f to "S", 96f to "M", 128f to "L", 160f to "XL")
        rowSizes.removeAllViews()
        sizes.forEach { (size, label) ->
            rowSizes.addView(chip(label, size == settings.fontSize) {
                Logger.log("Size changed → $label (${size.toInt()}sp)")
                settings.fontSize = size
                applyAll()
                buildSizeRow()
            })
        }
    }

    private fun checkForUpdate() {
        btnCheckUpdate.isClickable = false
        btnCheckUpdate.text = "Checking…"
        Thread {
            val (latestTag, releaseUrl) = fetchLatestRelease()
            handler.post {
                btnCheckUpdate.isClickable = true
                val latest = latestTag.removePrefix("v")
                if (latest.isNotEmpty() && latest != BuildConfig.VERSION_NAME) {
                    btnCheckUpdate.text = "Update: v$latest — tap to download"
                    btnCheckUpdate.setTextColor(Color.parseColor("#FFAA00"))
                    btnCheckUpdate.setOnClickListener {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                } else if (latest.isEmpty()) {
                    btnCheckUpdate.text = "Could not check — try again"
                    handler.postDelayed({ btnCheckUpdate.text = "Check for updates" }, 3_000)
                } else {
                    btnCheckUpdate.text = "Up to date ✓"
                    handler.postDelayed({
                        btnCheckUpdate.text = "Check for updates"
                        btnCheckUpdate.setOnClickListener { checkForUpdate() }
                    }, 3_000)
                }
            }
        }.start()
    }

    private fun chip(
        text: String,
        selected: Boolean,
        typeface: Typeface? = null,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        this.text = text
        this.typeface = typeface ?: Typeface.DEFAULT
        textSize = 14f
        setTextColor(if (selected) Color.WHITE else Color.parseColor("#999999"))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(if (selected) 0x44FFFFFF.toInt() else 0x14FFFFFF.toInt())
            setStroke(dp(1), if (selected) 0xBBFFFFFF.toInt() else 0x33FFFFFF.toInt())
        }
        val ph = dp(16); val pv = dp(7)
        setPadding(ph, pv, ph, pv)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    // ── OLED drift ────────────────────────────────────────────────────────────

    private fun driftClock() {
        val max = DRIFT_MAX_DP * context.resources.displayMetrics.density
        val tx = Random.nextFloat() * 2 * max - max
        val ty = Random.nextFloat() * 2 * max - max
        ObjectAnimator.ofPropertyValuesHolder(
            driftContainer,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, tx),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, ty)
        ).apply {
            duration = DRIFT_DURATION_MS
            interpolator = LinearInterpolator()
            start()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    private fun updateDates() {
        val s = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        tvDate.text = s
        tvDateGiant.text = s
    }

    private fun applyBrightness(level: Float) {
        val clamped = level.coerceIn(0.01f, 1f)

        // Primary: write directly to system brightness (reliable on all OEM ROMs)
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (clamped * 255).toInt().coerceIn(1, 255))
        }

        // Secondary: window-level brightness (works on stock Android / some OEMs)
        val lp = window.attributes
        lp.screenBrightness = clamped
        window.attributes = lp
    }
}
