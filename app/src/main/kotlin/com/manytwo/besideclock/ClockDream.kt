package com.manytwo.besideclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.service.dreams.DreamService
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class ClockDream : DreamService() {

    // Clock views
    private lateinit var layoutSingle: LinearLayout
    private lateinit var layoutGiant: LinearLayout
    private lateinit var tvTimeSingle: TextClock
    private lateinit var tvHour: TextClock
    private lateinit var tvMinute: TextClock
    private lateinit var tvDate: TextView
    private lateinit var tvDateGiant: TextView
    private lateinit var tvBattery: TextView

    // Settings overlay views
    private lateinit var overlaySettings: FrameLayout
    private lateinit var rowColors: LinearLayout
    private lateinit var rowFonts: LinearLayout
    private lateinit var rowSizes: LinearLayout
    private lateinit var sliderBrightness: SeekBar

    private lateinit var settings: ClockSettings
    private var dateTimer: Timer? = null

    // Saved so we can restore the system brightness when the dream ends
    private var savedBrightness: Int = -1
    private var savedBrightnessMode: Int = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                tvBattery.text = if (charging) "⚡ ${level * 100 / scale}%" else "${level * 100 / scale}%"
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        // isInteractive = true so the settings button is tappable.
        // The root layout has NO click listener, so navigation buttons are not blocked.
        isInteractive = true
        setContentView(R.layout.dream_clock)

        settings = ClockSettings(this)

        layoutSingle     = findViewById(R.id.layout_single)
        layoutGiant      = findViewById(R.id.layout_giant)
        tvTimeSingle     = findViewById(R.id.tv_time_single)
        tvHour           = findViewById(R.id.tv_hour)
        tvMinute         = findViewById(R.id.tv_minute)
        tvDate           = findViewById(R.id.tv_date)
        tvDateGiant      = findViewById(R.id.tv_date_giant)
        tvBattery        = findViewById(R.id.tv_battery)
        overlaySettings  = findViewById(R.id.overlay_settings)
        rowColors        = findViewById(R.id.row_colors)
        rowFonts         = findViewById(R.id.row_fonts)
        rowSizes         = findViewById(R.id.row_sizes)
        sliderBrightness = findViewById(R.id.slider_brightness)

        findViewById<TextView>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.backdrop).setOnClickListener { closeSettings() }
        findViewById<TextView>(R.id.btn_dismiss).setOnClickListener { wakeUp() }

        sliderBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                // Map 0–100 → 0.01–1.0, enforcing a dim floor at progress 1
                val level = if (progress == 0) 0.01f else progress / 100f
                settings.brightness = level
                applyBrightness(level)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        buildColorRow()
        buildFontRow()
        buildSizeRow()
        applyAll()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // Save current system brightness so we can restore it when the dream ends
        if (Settings.System.canWrite(this)) {
            savedBrightness = Settings.System.getInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            savedBrightnessMode = Settings.System.getInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        applyBrightness(settings.brightness)
        updateDates()
        dateTimer = Timer().also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { tvDate.post { updateDates() } }
            }, 60_000L - System.currentTimeMillis() % 60_000L, 60_000L)
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(batteryReceiver, filter)
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        dateTimer?.cancel(); dateTimer = null
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        // Restore system brightness to what it was before the dream started
        if (Settings.System.canWrite(this) && savedBrightness >= 0) {
            Settings.System.putInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, savedBrightnessMode)
            Settings.System.putInt(contentResolver,
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
    }

    private fun closeSettings() {
        overlaySettings.visibility = View.GONE
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
            rowColors.addView(View(this).apply {
                background = bg
                contentDescription = name
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = gap }
                setOnClickListener {
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
                settings.fontSize = size
                applyAll()
                buildSizeRow()
            })
        }
    }

    private fun chip(
        text: String,
        selected: Boolean,
        typeface: Typeface? = null,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun updateDates() {
        val s = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        tvDate.text = s
        tvDateGiant.text = s
    }

    private fun applyBrightness(level: Float) {
        val clamped = level.coerceIn(0.01f, 1f)

        // Primary: write directly to system brightness (reliable on all OEM ROMs)
        if (Settings.System.canWrite(this)) {
            Settings.System.putInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (clamped * 255).toInt().coerceIn(1, 255))
        }

        // Secondary: window-level brightness (works on stock Android / some OEMs)
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = clamped
            w.attributes = lp
        }
    }
}
