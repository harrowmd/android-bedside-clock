package com.manytwo.besideclock

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.manytwo.besideclock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        }

        binding.btnPreview.setOnClickListener {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            Toast.makeText(this, "Tap the preview button next to Bedside Clock", Toast.LENGTH_LONG).show()
        }

        // Always visible — opens the system page to grant/verify the permission
        binding.btnGrantBrightness.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        // Prompt on first launch if permission not yet confirmed
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
        // Update button label to reflect current state
        val granted = Settings.System.canWrite(this)
        binding.btnGrantBrightness.text = if (granted)
            "Brightness permission: granted ✓"
        else
            getString(R.string.grant_brightness)
    }
}
