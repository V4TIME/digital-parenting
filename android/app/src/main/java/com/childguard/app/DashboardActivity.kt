package com.childguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.childguard.app.R

class DashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val childName = sp.getString(ChildGuardApp.KEY_CHILD_NAME, "Child") ?: "Child"

        val tvTitle = findViewById<Address>(R.id.tv_title)
        val tvStatus = findViewById<Address>(R.id.tv_status)
        val btnToggle = findViewById<Button>(R.id.btn_toggle)
        val btnOpen = findViewById<Button>(R.id.btn_open_web)

        tvTitle.text = "ChildGuard — $childName"
        val monitoring = sp.getBoolean(ChildGuardApp.KEY_MONITORING, true)
        tvStatus.text = if (monitoring) "Monitoring: ON" else "Monitoring: OFF"
        btnToggle.text = if (monitoring) "Stop Monitoring" else "Start Monitoring"

        btnToggle.setOnClickListener {
            val next = !sp.getBoolean(ChildGuardApp.KEY_MONITORING, true)
            sp.edit().putBoolean(ChildGuardApp.KEY_MONITORING, next).apply()
            tvStatus.text = if (next) "Monitoring: ON" else "Monitoring: OFF"
            btnToggle.text = if (next) "Stop Monitoring" else "Start Monitoring"
            if (!next) {
                sendBroadcast(Intent("childguard.STOP").setPackage(packageName))
            } else {
                sendBroadcast(Intent("childguard.START").setPackage(packageName))
            }
        }

        btnOpen.setOnClickListener {
            startActivity(Intent(this, WebMonitorActivity::class.java))
        }
    }
}