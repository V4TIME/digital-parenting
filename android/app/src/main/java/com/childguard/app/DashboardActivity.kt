package com.childguard.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val childName = sp.getString(ChildGuardApp.KEY_CHILD_NAME, "Child") ?: "Child"

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val btnToggle = findViewById<Button>(R.id.btn_toggle)
        val btnOpen = findViewById<Button>(R.id.btn_open_web)

        tvTitle.text = "ChildGuard — $childName"
        val monitoring = sp.getBoolean(ChildGuardApp.KEY_MONITORING, true)
        tvStatus.text = if (monitoring) "Monitoring: ON" else "Monitoring: OFF"
        btnToggle.text = if (monitoring) "Stop Monitoring" else "Start Monitoring"

        // The first-run flow goes SetupActivity -> DashboardActivity, which
        // used to show "Monitoring: ON" (the preference default) without ever
        // starting MonitorService; only MainActivity did that. Start it here
        // too, so the label always matches reality. START is idempotent.
        if (monitoring) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitorService::class.java).setAction("childguard.START")
            )
        }

        btnToggle.setOnClickListener {
            val next = !sp.getBoolean(ChildGuardApp.KEY_MONITORING, true)
            sp.edit().putBoolean(ChildGuardApp.KEY_MONITORING, next).apply()
            if (next) {
                ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).setAction("childguard.START"))
            } else {
                stopService(Intent(this, MonitorService::class.java).setAction("childguard.STOP"))
            }
            tvStatus.text = if (next) "Monitoring: ON" else "Monitoring: OFF"
            btnToggle.text = if (next) "Stop Monitoring" else "Start Monitoring"
            Toast.makeText(this, if (next) "Monitoring started" else "Monitoring stopped", Toast.LENGTH_SHORT).show()
        }

        btnOpen.setOnClickListener {
            startActivity(Intent(this, WebMonitorActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Uploads run in a background thread while this screen is open, so
        // re-read on every resume instead of only in onCreate.
        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val tvUpload = findViewById<TextView>(R.id.tv_upload)
        val uploadWarning = MonitorService.uploadWarning(sp)
        if (uploadWarning == null) {
            tvUpload.visibility = View.GONE
        } else {
            tvUpload.text = uploadWarning
            tvUpload.visibility = View.VISIBLE
        }

        val tvDropped = findViewById<TextView>(R.id.tv_dropped)
        val warning = MonitorService.droppedWarning(sp)
        if (warning == null) {
            tvDropped.visibility = View.GONE
        } else {
            tvDropped.text = warning
            tvDropped.visibility = View.VISIBLE
        }
    }
}
