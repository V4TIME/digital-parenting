package com.childguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE).edit()

        val btnEnableAccessibility = findViewById<Button>(R.id.btn_enable_accessibility)
        val btnEnableNotifications = findViewById<Button>(R.id.btn_enable_notifications)
        val btnFinish = findViewById<Button>(R.id.btn_finish)

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent("android.settings.ACCESSIBILITY_SETTINGS"))
            Toast.makeText(this, "Enable ChildGuard in Accessibility", Toast.LENGTH_LONG).show()
        }

        btnEnableNotifications.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Toast.makeText(this, "Enable ChildGuard in Notification access", Toast.LENGTH_LONG).show()
        }

        btnFinish.setOnClickListener {
            sp.putBoolean("setup_done", true).apply()
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }
}
