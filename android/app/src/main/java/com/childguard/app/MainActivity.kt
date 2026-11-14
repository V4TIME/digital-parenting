package com.childguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.childguard.app.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val setupDone = sp.getBoolean("setup_done", false)

        if (!setupDone) {
            startActivity(Intent(this, SetupActivity::class.java))
        } else {
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        val btnSetup = findViewById<Button>(R.id.btn_setup)
        val btnDashboard = findViewById<Button>(R.id.btn_dashboard)

        btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        btnDashboard.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        requestNotificationPermission()

        // Auto-start monitoring foreground service once setup is complete.
        if (setupDone && sp.getBoolean(ChildGuardApp.KEY_MONITORING, true)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitorService::class.java).setAction("childguard.START")
            )
        }
    }

    /** Android 13+ requires runtime permission before posting any notification. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "Notifications permission granted" else "Notifications permission denied",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
