package com.childguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.childguard.app.R

class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE).edit()

        val btnEnableAccessibility = findViewById<Button>(R.id.btn_enable_accessibility)
        val btnEnableNotifications = findViewById<Button>(R.id.btn_enable_notifications)
        val btnEnableVpn = findViewById<Button>(R.id.btn_enable_vpn)
        val btnChildName = findViewById<Button>(R.id.btn_child_name)
        val btnFinish = findViewById<Button>(R.id.btn_finish)

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(android.accessibility.AccessibilitySettings::class.java))
            Toast.makeText(this, "Enable ChildGuard Monitoring in Accessibility", Toast.LENGTH_LONG).show()
        }

        btnEnableNotifications.setOnClickListener {
            startActivity(Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
            })
            Toast.makeText(this, "Allow notification access for ChildGuard", Toast.LENGTH_LONG).show()
        }

        btnEnableVpn.setOnClickListener {
            Toast.makeText(this, "Tap 'VPN' in the notification shade to start traffic monitoring", Toast.LENGTH_LONG).show()
        }

        btnChildName.setOnClickListener {
            val name = android.widget.TextInputEditText(findById(R.id.edit_child_name)).text?.toString().orEmpty()
            if (name.isNotEmpty()) sp.putString(ChildGuardApp.KEY_CHILD_NAME, name)
        }

        btnFinish.setOnClickListener {
            sp.putBoolean("setup_done", true).apply()
            Toast.makeText(this, "Setup complete", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }
}