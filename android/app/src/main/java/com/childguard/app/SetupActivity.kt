package com.childguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val prefs = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)

        val editServer = findViewById<EditText>(R.id.edit_server_url)
        val editToken = findViewById<EditText>(R.id.edit_token)
        val btnEnableAccessibility = findViewById<Button>(R.id.btn_enable_accessibility)
        val btnEnableNotifications = findViewById<Button>(R.id.btn_enable_notifications)
        val btnFinish = findViewById<Button>(R.id.btn_finish)

        editServer.setText(prefs.getString(ChildGuardApp.KEY_SERVER_URL, ChildGuardApp.DEFAULT_SERVER))
        editToken.setText(prefs.getString(ChildGuardApp.KEY_PARENT_TOKEN, ""))

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent("android.settings.ACCESSIBILITY_SETTINGS"))
            Toast.makeText(this, "Enable ChildGuard in Accessibility", Toast.LENGTH_LONG).show()
        }

        btnEnableNotifications.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Toast.makeText(this, "Enable ChildGuard in Notification access", Toast.LENGTH_LONG).show()
        }

        btnFinish.setOnClickListener {
            val server = editServer.text?.toString()?.trim().orEmpty()
                .ifEmpty { ChildGuardApp.DEFAULT_SERVER }
            val token = editToken.text?.toString()?.trim().orEmpty()
            // A host typed without a scheme used to be saved verbatim, and
            // URL(...) then failed on every upload with no visible error.
            val normalized = MonitorService.normalizeHttpUrl(server)
            if (normalized == null) {
                editServer.error = "Use an http:// or https:// address"
                Toast.makeText(
                    this,
                    "Server URL must be an http:// or https:// address",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString(ChildGuardApp.KEY_SERVER_URL, normalized)
                .putString(ChildGuardApp.KEY_PARENT_TOKEN, token)
                .putBoolean("setup_done", true)
                .apply()
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }
}
