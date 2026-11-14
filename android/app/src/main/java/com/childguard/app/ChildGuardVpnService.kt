package com.childguard.app

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.net.VpnEstablished
import android.os.Bundle
import android.os ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChildGuardVpnService : VpnService() {
    companion object {
        private const val TAG = "ChildGuardVPN"
        var instance: ChildGuardVpnService? = null
        var isVpnActive = false
            private set

        val trafficQueue = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_QUEUE = 500

        private val STRANGER_DOMAINS = listOf(
            "discord", "telegram", "snapchat", "kik", "skype", "tiktok",
            "instagram", "whatsapp", "facebook", "messenger", "line",
            "pinterest", "tumblr", "reddit", "4chan", "8chan", "gab",
            "parler", "truthsocial", "onlyfans", "telegram.me"
        )

        fun isSuspiciousHost(host: String): Boolean {
            val h = host.lowercase()
            if (h.isEmpty()) return false
            return STRANGER_DOMAINS.any { h.contains(it) }
        }
    }

    private var builder: Builder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "childguard.START_VPN" -> startVpn()
            "childguard.STOP_VPN" -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isVpnActive) return
        instance = this
        builder = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", "0.0.0.0")
            .addDnsServer("8.8.8.8")
            .setSession("ChildGuard")
            .setBlocking(false)

        try {
            pfd = builder?.establish()
            if (pfd != null) {
                isVpnActive = true
                Log.i(TAG, "VPN established")
                scope.launch { readLoop() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN establish failed: ${e.message}")
        }
    }

    private fun stopVpn() {
        isVpnActive = false
        try { pfd?.close() } catch (e: Exception) {}
        pfd = null
        job?.cancel()
    }

    private suspend fun readLoop() {
        val fd = pfd?.fileDescriptor ?: return
        val buf = ByteBuffer.allocate(32768)
        while (isVpnActive) {
            val n = fd.read(buf)
            if (n <= 0) break
            val data = ByteArray(n)
            buf.get(data)
            buf.clear()
            val host = extractHost(data)
            if (host.isNotEmpty()) {
                val obj = JSONObject().apply {
                    put("type", "traffic")
                    put("host", host)
                    put("suspicious", isSuspiciousHost(host))
                    put("ts", System.currentTimeMillis())
                }
                while (trafficQueue.size > MAX_QUEUE) trafficQueue.poll()
                trafficQueue.add(obj)
            }
        }
    }

    private fun extractHost(data: ByteArray): String {
        try {
            if (data.size < 42) return ""
            val ipProto = data[23].toInt() and 0xFF
            if (ipProto != 6 && ipProto != 17) return ""
            val src = data.copyOfRange(26, 42)
            val dst = data.copyOfRange(42, 58)
            val srcIp = joinBytes(src)
            val dstIp = joinBytes(dst)
            if (ipProto == 17) {
                val sport = (data[34].toInt() and 0xFF) shl 8 or (data[35].toInt() and 0xFF)
                val dport = (data[36].toInt() and 0xFF) shl 8 or (data[37].toInt() and 0xFF)
                return "$srcIp:$sport -> $dstIp:$dport"
            }
            return "$srcIp -> $dstIp"
        } catch (e: Exception) {
            return ""
        }
    }

    private fun joinBytes(b: ByteArray): String =
        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun IBinder(intent: Intent?): IBinder? = null
}