package com.childguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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
        tvStatus.text = "Scanning installed apps…"

        // PackageManager enumerates every app and loads labels; doing that on
        // the UI thread can jank or ANR on a phone with hundreds of apps.
        Thread {
            val rows = scanInternetApps()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val sb = StringBuilder()
                sb.append("Internet-capable apps on device: ${rows.size}\n\n")
                rows.forEach { (pkg, label) ->
                    sb.append("• $label\n   $pkg\n")
                }
                tvStatus.text = sb.toString()
            }
        }.apply { isDaemon = true }.start()

        btnBack.setOnClickListener { finish() }
    }

    /**
     * Every app that can reach the network. `getInstalledPackages` returns the
     * permission list in one binder call, so this avoids a per-app
     * getPackageInfo() round-trip and cannot throw NameNotFoundException if an
     * app is uninstalled mid-scan.
     */
    internal fun scanInternetApps(): List<Pair<String, String>> {
        val pm = packageManager
        val out = ArrayList<Pair<String, String>>()
        val packages = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (t: Throwable) {
            return out
        }
        for (pi in packages) {
            val perms = pi.requestedPermissions ?: continue
            val hasNet = perms.any {
                it == Manifest.permission.INTERNET ||
                    it == Manifest.permission.ACCESS_NETWORK_STATE
            }
            if (!hasNet) continue
            val app = pi.applicationInfo ?: continue
            val label = try {
                app.loadLabel(pm)?.toString()
            } catch (t: Throwable) {
                null
            } ?: app.packageName
            out.add(app.packageName to label)
        }
        return out.sortedBy { it.second.lowercase() }
    }
}
