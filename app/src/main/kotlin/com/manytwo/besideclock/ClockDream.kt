package com.manytwo.besideclock

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.dreams.DreamService

class ClockDream : DreamService() {

    private lateinit var controller: ClockController
    private var dreamStartMs: Long = 0L
    private var startBatteryLevel: Int = -1

    // Saved so we can restore system settings when the dream ends
    private var savedDozeAlwaysOn: Int = -1
    private var savedDozePulseOnPickUp: Int = -1

    // Explicit wake lock as a final fallback for OEM ROMs that ignore window flags
    private var wakeLock: PowerManager.WakeLock? = null

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val elapsedSec = (System.currentTimeMillis() - dreamStartMs) / 1000L
            val h = elapsedSec / 3600; val m = (elapsedSec % 3600) / 60; val s = elapsedSec % 60
            val (level, watts, charging) = readBatteryInfo()
            val wStr = if (watts > 0f) " ${if (charging) "+" else "-"}%.1fW".format(watts) else ""
            val timeStr = "%02d:%02d:%02d".format(h, m, s)
            Logger.log("[heartbeat] battery=${level}%$wStr elapsed=$timeStr")
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30 * 60 * 1000L  // log every 30 minutes
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        // isInteractive = true so the settings button is tappable.
        // The root layout has NO click listener, so navigation buttons are not blocked.
        isInteractive = true
        setContentView(R.layout.dream_clock)

        Logger.init(this)

        // Belt-and-suspenders: keep screen on even if the OEM power manager
        // tries to override the DreamService's own internal wake lock.
        window?.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        controller = ClockController(this, window!!, findViewById(R.id.root_layout)) { wakeUp() }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreamStartMs = System.currentTimeMillis()
        Logger.init(this)
        val (startLevel, startWatts, startCharging) = readBatteryInfo()
        startBatteryLevel = startLevel
        val wStr = if (startWatts > 0.1f) " ${if (startCharging) "+" else "-"}${"%.1f".format(startWatts)}W" else ""
        val settings = ClockSettings(this)
        Logger.log("Clock started — font=${settings.fontFamily} size=${settings.fontSize.toInt()}sp brightness=${(settings.brightness * 100).toInt()}% battery=${startLevel}%$wStr")

        // Disable AOD so the DozeService cannot steal the display while we are dreaming
        try {
            savedDozeAlwaysOn = Settings.Secure.getInt(contentResolver, "doze_always_on", 0)
            Settings.Secure.putInt(contentResolver, "doze_always_on", 0)
            Logger.log("AOD disabled for dream session")
        } catch (e: Exception) {
            Logger.log("AOD disable skipped: ${e.message}")
        }
        // Disable pick-up/motion-triggered ambient pulse: otherwise DreamManagerService
        // can hand the dream slot to the system DozeService (a blank ambient screen)
        // whenever the device is moved, abandoning our clock until the user wakes it manually.
        try {
            savedDozePulseOnPickUp = Settings.Secure.getInt(contentResolver, "doze_pulse_on_pick_up", 0)
            Settings.Secure.putInt(contentResolver, "doze_pulse_on_pick_up", 0)
            Logger.log("Pick-up pulse disabled for dream session")
        } catch (e: Exception) {
            Logger.log("Pick-up pulse disable skipped: ${e.message}")
        }

        controller.start()

        // Explicit wake lock — last resort for ROMs that ignore FLAG_KEEP_SCREEN_ON
        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "BesideClock::KeepOn")
            .also { it.acquire() }

        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        val elapsedSec = (System.currentTimeMillis() - dreamStartMs) / 1000L
        val h = elapsedSec / 3600; val m = (elapsedSec % 3600) / 60; val s = elapsedSec % 60
        val (stopLevel, _, _) = readBatteryInfo()
        val deltaStr = if (startBatteryLevel >= 0 && stopLevel >= 0) {
            val d = stopLevel - startBatteryLevel
            " battery=${stopLevel}% (${if (d >= 0) "+$d" else "$d"}%)"
        } else ""
        val timeStr = "%02d:%02d:%02d".format(h, m, s)
        try { Logger.log("Clock stopped — elapsed $timeStr$deltaStr") } catch (_: Exception) {}

        controller.stop()
        wakeLock?.release(); wakeLock = null
        heartbeatHandler.removeCallbacks(heartbeatRunnable)

        // Restore AOD setting
        try {
            if (savedDozeAlwaysOn >= 0)
                Settings.Secure.putInt(contentResolver, "doze_always_on", savedDozeAlwaysOn)
        } catch (_: Exception) {}
        // Restore pick-up/motion-triggered ambient pulse setting
        try {
            if (savedDozePulseOnPickUp >= 0)
                Settings.Secure.putInt(contentResolver, "doze_pulse_on_pick_up", savedDozePulseOnPickUp)
        } catch (_: Exception) {}
    }

    private fun readBatteryInfo(): Triple<Int, Float, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val voltMV = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        val currentµA = try {
            val v = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (v == Integer.MIN_VALUE) 0 else v  // MIN_VALUE = "not supported"
        } catch (_: Exception) { 0 }
        val watts = if (voltMV > 0 && currentµA != 0)
            Math.abs(currentµA.toLong()).toFloat() / 1_000_000f * voltMV / 1000f else 0f
        return Triple(level, watts, charging)
    }
}
