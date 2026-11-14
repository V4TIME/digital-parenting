package com.childguard.app

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.childguard.app.R

class WebMonitorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_monitor)

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val btnBack = findViewById<Button>(R.id.btn_back)

        tvTitle.text = "Web Monitor"

        val pm = packageManager
        val installed = ArrayList<Pair<String, String>>()
        val apps = pm.getInstalledApplications(PackageManager.GET_PERMISSIONS)
        for (app in apps) {
            val pi = pm.getPackageInfo(app.packageName, 0)
            val hasNet = pi.requestedPermissions?.any {
                it == "android.permission.INTERNET" || it == "android.permission.ACCESS_NETWORK_STATE"
            } == true
            if (hasNet) {
                installed.add(app.packageName to (app.loadLabel(pm)?.toString() ?: app.packageName))
            }
        }

        val sb = StringBuilder()
        sb.append("Internet-capable apps on device: ${installed.size}\n\n")
        installed.sortedBy { it.second.lowercase() }.forEach { (pkg, label) ->
            sb.append("• $label\n   $pkg\n")
        }
        tvStatus.text = sb.toString()

        btnBack.setOnClickListener { finish() }

        Toast.makeText(this, "Tap an app to view its traffic", Toast.LENGTH_LONG).show()
    }
}