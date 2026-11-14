package com.childguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    }
}