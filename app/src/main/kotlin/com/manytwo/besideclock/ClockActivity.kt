package com.manytwo.besideclock

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Shows the clock face for manual daytime use — e.g. while the phone isn't
 * charging. A normal full-screen Activity rather than a DreamService preview,
 * since triggering a DreamService preview directly is a privileged system
 * API that third-party apps can't call (only Android's own Settings app can).
 */
class ClockActivity : Activity() {

    private lateinit var controller: ClockController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(R.layout.dream_clock)

        Logger.init(this)

        controller = ClockController(this, window, findViewById(R.id.root_layout)) { finish() }
    }

    override fun onResume() {
        super.onResume()
        controller.start()
    }

    override fun onPause() {
        controller.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }
}
